package dev.migrationreplay.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.migrationreplay.config.ConfigModels.AssertionSpec;
import dev.migrationreplay.config.ConfigModels.CompareSpec;
import dev.migrationreplay.config.ConfigModels.ComparisonMode;
import dev.migrationreplay.config.ConfigModels.ExpectedOutcome;
import dev.migrationreplay.config.ConfigModels.Outcomes;
import dev.migrationreplay.config.ConfigModels.ParameterValue;
import dev.migrationreplay.config.ConfigModels.PlanSpec;
import dev.migrationreplay.config.ConfigModels.QueriesConfig;
import dev.migrationreplay.config.ConfigModels.QuerySpec;
import dev.migrationreplay.config.ConfigModels.RowOrder;
import dev.migrationreplay.config.ConfigModels.RunState;
import dev.migrationreplay.config.ConfigModels.SchemaAssertion;
import dev.migrationreplay.config.ConfigModels.ValueType;
import dev.migrationreplay.sql.NamedParameterSql;
import dev.migrationreplay.sql.SqlPolicy;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern QUERY_ID = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    public QueriesConfig load(String yaml) throws ConfigurationException {
        final JsonNode root;
        try {
            root = YAML_MAPPER.readTree(yaml);
        } catch (JsonProcessingException exception) {
            throw new ConfigurationException(
                    "INVALID_YAML", "queries.yaml could not be parsed: " + exception.getOriginalMessage(), exception);
        }

        requireObject(root, "queries.yaml");
        rejectUnknownFields(root, Set.of("version", "queries", "schema_assertions"), "queries.yaml");
        int version = requiredInteger(root, "version", "queries.yaml");
        if (version != 1) {
            throw new ConfigurationException(
                    "UNSUPPORTED_CONFIG_VERSION", "Only queries.yaml version 1 is supported.");
        }

        JsonNode queryNodes = requiredArray(root, "queries", "queries.yaml");
        if (queryNodes.isEmpty()) {
            throw new ConfigurationException("EMPTY_QUERY_CORPUS", "queries must not be empty.");
        }

        List<QuerySpec> queries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < queryNodes.size(); index++) {
            QuerySpec query = parseQuery(queryNodes.get(index), index);
            if (!ids.add(query.id())) {
                throw new ConfigurationException(
                        "DUPLICATE_QUERY_ID", "Duplicate query id: " + query.id());
            }
            queries.add(query);
        }

        List<SchemaAssertion> schemaAssertions = parseSchemaAssertions(root.get("schema_assertions"));
        return new QueriesConfig(version, List.copyOf(queries), List.copyOf(schemaAssertions));
    }

    private QuerySpec parseQuery(JsonNode node, int index) throws ConfigurationException {
        String context = "queries[" + index + "]";
        requireObject(node, context);
        rejectUnknownFields(
                node,
                Set.of("id", "sql", "parameters", "outcomes", "compare", "assertions", "plan"),
                context);

        String id = requiredText(node, "id", context);
        if (!QUERY_ID.matcher(id).matches()) {
            throw new ConfigurationException(
                    "INVALID_QUERY_ID",
                    context + ".id must match " + QUERY_ID.pattern() + ": " + id);
        }
        String sql = requiredText(node, "sql", context);
        SqlPolicy.validateQuery(sql);

        Map<String, ParameterValue> parameters = parseParameters(node.get("parameters"), context);
        NamedParameterSql compiled = NamedParameterSql.compile(sql);
        Set<String> referenced = Set.copyOf(compiled.parameterNames());
        if (!referenced.equals(parameters.keySet())) {
            throw new ConfigurationException(
                    "PARAMETER_MISMATCH",
                    context + " referenced parameters " + referenced
                            + " but configured parameters " + parameters.keySet());
        }

        Outcomes outcomes = parseOutcomes(node.get("outcomes"), context);
        CompareSpec compare = parseCompare(node.get("compare"), context);
        if (compare.rowOrder() == RowOrder.ORDERED && !SqlPolicy.hasExplicitOrderBy(sql)) {
            throw new ConfigurationException(
                    "ORDERED_QUERY_WITHOUT_ORDER_BY",
                    context + " uses row_order: ordered without an explicit ORDER BY.");
        }
        AssertionSpec assertions = parseAssertions(node.get("assertions"), context);
        PlanSpec plan = parsePlan(node.get("plan"), context);
        return new QuerySpec(id, sql, parameters, outcomes, compare, assertions, plan);
    }

    private Map<String, ParameterValue> parseParameters(JsonNode node, String context)
            throws ConfigurationException {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        requireObject(node, context + ".parameters");
        Map<String, ParameterValue> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String name = field.getKey();
            if (!IDENTIFIER.matcher(name).matches()) {
                throw new ConfigurationException(
                        "INVALID_PARAMETER_NAME", "Invalid parameter name: " + name);
            }
            String parameterContext = context + ".parameters." + name;
            JsonNode parameter = field.getValue();
            requireObject(parameter, parameterContext);
            rejectUnknownFields(parameter, Set.of("type", "value"), parameterContext);
            String typeText = requiredText(parameter, "type", parameterContext);
            ValueType type = parseEnum(ValueType.class, typeText, parameterContext + ".type");
            JsonNode rawValue = parameter.get("value");
            values.put(name, new ParameterValue(type, parseParameterValue(type, rawValue, parameterContext)));
        }
        return Map.copyOf(values);
    }

    private Object parseParameterValue(ValueType type, JsonNode value, String context)
            throws ConfigurationException {
        return switch (type) {
            case NULL -> {
                if (value != null && !value.isNull()) {
                    throw new ConfigurationException(
                            "INVALID_PARAMETER_VALUE", context + " type null requires a null value.");
                }
                yield null;
            }
            case INTEGER -> {
                if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
                    throw new ConfigurationException(
                            "INVALID_PARAMETER_VALUE", context + " requires a signed 64-bit integer.");
                }
                yield value.longValue();
            }
            case REAL -> {
                if (value == null || !value.isNumber()) {
                    throw new ConfigurationException(
                            "INVALID_PARAMETER_VALUE", context + " requires a real number.");
                }
                double number = value.doubleValue();
                if (!Double.isFinite(number)) {
                    throw new ConfigurationException(
                            "INVALID_PARAMETER_VALUE", context + " requires a finite real number.");
                }
                yield number;
            }
            case TEXT -> {
                if (value == null || !value.isTextual()) {
                    throw new ConfigurationException(
                            "INVALID_PARAMETER_VALUE", context + " requires a text value.");
                }
                yield value.textValue();
            }
            case BLOB -> {
                if (value == null || !value.isTextual()) {
                    throw new ConfigurationException(
                            "INVALID_PARAMETER_VALUE", context + " requires Base64 text.");
                }
                try {
                    yield Base64.getDecoder().decode(value.textValue());
                } catch (IllegalArgumentException exception) {
                    throw new ConfigurationException(
                            "INVALID_PARAMETER_VALUE", context + " contains invalid Base64.", exception);
                }
            }
        };
    }

    private Outcomes parseOutcomes(JsonNode node, String context) throws ConfigurationException {
        EnumMap<RunState, ExpectedOutcome> outcomes = new EnumMap<>(RunState.class);
        for (RunState state : RunState.values()) {
            outcomes.put(state, ExpectedOutcome.SUCCESS);
        }
        if (node == null || node.isNull()) {
            return new Outcomes(Map.copyOf(outcomes));
        }
        requireObject(node, context + ".outcomes");
        rejectUnknownFields(node, Set.of("baseline", "after_up", "after_down"), context + ".outcomes");
        Iterator<Map.Entry<String, JsonNode>> fields = node.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                throw new ConfigurationException(
                        "INVALID_OUTCOME", context + ".outcomes." + field.getKey() + " must be text.");
            }
            outcomes.put(
                    RunState.fromKey(field.getKey()),
                    parseEnum(
                            ExpectedOutcome.class,
                            field.getValue().textValue(),
                            context + ".outcomes." + field.getKey()));
        }
        return new Outcomes(Map.copyOf(outcomes));
    }

    private CompareSpec parseCompare(JsonNode node, String context) throws ConfigurationException {
        ComparisonMode up = ComparisonMode.PRESERVE;
        ComparisonMode down = ComparisonMode.PRESERVE;
        RowOrder order = RowOrder.UNORDERED;
        if (node != null && !node.isNull()) {
            requireObject(node, context + ".compare");
            rejectUnknownFields(
                    node,
                    Set.of("baseline_to_up", "baseline_to_down", "row_order"),
                    context + ".compare");
            if (node.has("baseline_to_up")) {
                up = parseEnum(
                        ComparisonMode.class,
                        requiredText(node, "baseline_to_up", context + ".compare"),
                        context + ".compare.baseline_to_up");
            }
            if (node.has("baseline_to_down")) {
                down = parseEnum(
                        ComparisonMode.class,
                        requiredText(node, "baseline_to_down", context + ".compare"),
                        context + ".compare.baseline_to_down");
            }
            if (node.has("row_order")) {
                order = parseEnum(
                        RowOrder.class,
                        requiredText(node, "row_order", context + ".compare"),
                        context + ".compare.row_order");
            }
        }
        return new CompareSpec(up, down, order);
    }

    private AssertionSpec parseAssertions(JsonNode node, String context)
            throws ConfigurationException {
        if (node == null || node.isNull()) {
            return new AssertionSpec(List.of(), List.of());
        }
        requireObject(node, context + ".assertions");
        rejectUnknownFields(node, Set.of("non_null", "unique_by"), context + ".assertions");
        return new AssertionSpec(
                textArray(node.get("non_null"), context + ".assertions.non_null"),
                textArray(node.get("unique_by"), context + ".assertions.unique_by"));
    }

    private PlanSpec parsePlan(JsonNode node, String context) throws ConfigurationException {
        if (node == null || node.isNull()) {
            return new PlanSpec(List.of());
        }
        requireObject(node, context + ".plan");
        rejectUnknownFields(node, Set.of("warn_on_full_scan"), context + ".plan");
        return new PlanSpec(textArray(node.get("warn_on_full_scan"), context + ".plan.warn_on_full_scan"));
    }

    private List<SchemaAssertion> parseSchemaAssertions(JsonNode node)
            throws ConfigurationException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConfigurationException(
                    "INVALID_CONFIG_TYPE", "schema_assertions must be an array.");
        }
        List<SchemaAssertion> assertions = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode assertion = node.get(index);
            String context = "schema_assertions[" + index + "]";
            requireObject(assertion, context);
            rejectUnknownFields(assertion, Set.of("state", "index_exists"), context);
            RunState state = parseRunState(requiredText(assertion, "state", context), context + ".state");
            String indexName = requiredText(assertion, "index_exists", context);
            if (!IDENTIFIER.matcher(indexName).matches()) {
                throw new ConfigurationException(
                        "INVALID_INDEX_NAME", context + ".index_exists is invalid: " + indexName);
            }
            assertions.add(new SchemaAssertion(state, indexName));
        }
        return assertions;
    }

    private static List<String> textArray(JsonNode node, String context)
            throws ConfigurationException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ConfigurationException("INVALID_CONFIG_TYPE", context + " must be an array.");
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < node.size(); index++) {
            if (!node.get(index).isTextual() || node.get(index).textValue().isBlank()) {
                throw new ConfigurationException(
                        "INVALID_CONFIG_TYPE", context + "[" + index + "] must be nonblank text.");
            }
            values.add(node.get(index).textValue());
        }
        if (new HashSet<>(values).size() != values.size()) {
            throw new ConfigurationException("DUPLICATE_CONFIG_VALUE", context + " contains duplicates.");
        }
        return List.copyOf(values);
    }

    private static <T extends Enum<T>> T parseEnum(
            Class<T> type, String value, String context) throws ConfigurationException {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    "INVALID_ENUM_VALUE", context + " has unsupported value: " + value, exception);
        }
    }

    private static RunState parseRunState(String value, String context)
            throws ConfigurationException {
        try {
            return RunState.fromKey(value);
        } catch (IllegalArgumentException exception) {
            throw new ConfigurationException(
                    "INVALID_ENUM_VALUE", context + " has unsupported value: " + value, exception);
        }
    }

    private static void requireObject(JsonNode node, String context) throws ConfigurationException {
        if (node == null || !node.isObject()) {
            throw new ConfigurationException("INVALID_CONFIG_TYPE", context + " must be an object.");
        }
    }

    private static JsonNode requiredArray(JsonNode node, String field, String context)
            throws ConfigurationException {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new ConfigurationException(
                    "INVALID_CONFIG_TYPE", context + "." + field + " must be an array.");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field, String context)
            throws ConfigurationException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new ConfigurationException(
                    "INVALID_CONFIG_TYPE", context + "." + field + " must be nonblank text.");
        }
        return value.textValue();
    }

    private static int requiredInteger(JsonNode node, String field, String context)
            throws ConfigurationException {
        JsonNode value = node.get(field);
        if (value == null || !value.isInt()) {
            throw new ConfigurationException(
                    "INVALID_CONFIG_TYPE", context + "." + field + " must be an integer.");
        }
        return value.intValue();
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed, String context)
            throws ConfigurationException {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new ConfigurationException(
                        "UNKNOWN_CONFIG_FIELD", context + " contains unknown field: " + field);
            }
        }
    }
}
