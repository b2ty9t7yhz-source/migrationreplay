package dev.migrationreplay.engine;

import dev.migrationreplay.config.ConfigModels.ComparisonMode;
import dev.migrationreplay.config.ConfigModels.ExpectedOutcome;
import dev.migrationreplay.config.ConfigModels.QueriesConfig;
import dev.migrationreplay.config.ConfigModels.QuerySpec;
import dev.migrationreplay.config.ConfigModels.RowOrder;
import dev.migrationreplay.config.ConfigModels.RunState;
import dev.migrationreplay.config.ConfigModels.SchemaAssertion;
import dev.migrationreplay.replay.ReplayModels.CanonicalRow;
import dev.migrationreplay.replay.ReplayModels.CanonicalValue;
import dev.migrationreplay.replay.ReplayModels.QueryObservation;
import dev.migrationreplay.replay.ReplayModels.QueryOutcome;
import dev.migrationreplay.replay.ReplayModels.SchemaObject;
import dev.migrationreplay.replay.ReplayModels.SchemaSnapshot;
import dev.migrationreplay.replay.ReplayModels.StateSnapshot;
import dev.migrationreplay.report.ReportModels.ComparisonReport;
import dev.migrationreplay.report.ReportModels.QueryDifference;
import dev.migrationreplay.report.ReportModels.RowCountChange;
import dev.migrationreplay.report.ReportModels.SchemaDifference;
import dev.migrationreplay.report.ReportModels.Violation;
import dev.migrationreplay.report.ReportModels.Warning;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ComparisonEngine {
    public void validateState(
            RunState state,
            StateSnapshot snapshot,
            QueriesConfig config,
            List<Violation> violations,
            List<Warning> warnings) {
        validateIntegrity(state, snapshot.schema(), violations);
        Map<String, QueryObservation> observations = snapshot.queries().stream()
                .collect(Collectors.toMap(QueryObservation::queryId, Function.identity()));
        for (QuerySpec query : config.queries()) {
            QueryObservation observation = observations.get(query.id());
            if (observation == null) {
                violations.add(new Violation(
                        "QUERY_NOT_CAPTURED", state.key(), query.id(), "Query observation is missing."));
                continue;
            }
            validateOutcome(state, query, observation, violations);
            if (observation.outcome() == QueryOutcome.SUCCESS) {
                validateAssertions(state, query, observation, violations);
                validateFullScans(state, query, observation, warnings);
            }
        }
        validateSchemaAssertions(state, snapshot.schema(), config.schemaAssertions(), violations);
    }

    public ComparisonReport compare(
            RunState targetState,
            StateSnapshot baseline,
            StateSnapshot target,
            QueriesConfig config,
            List<Violation> violations,
            List<Warning> warnings) {
        Map<String, QueryObservation> before = byQueryId(baseline.queries());
        Map<String, QueryObservation> after = byQueryId(target.queries());
        List<QueryDifference> queryDifferences = new ArrayList<>();

        for (QuerySpec query : config.queries()) {
            QueryObservation left = before.get(query.id());
            QueryObservation right = after.get(query.id());
            List<String> categories = differenceCategories(left, right, query.compare().rowOrder());
            boolean equivalent = categories.isEmpty();
            ComparisonMode mode = targetState == RunState.AFTER_UP
                    ? query.compare().baselineToUp()
                    : query.compare().baselineToDown();
            queryDifferences.add(new QueryDifference(
                    query.id(), equivalent, mode.name().toLowerCase(Locale.ROOT), categories));
            if (!equivalent) {
                String message = "Behavior differs from baseline: " + String.join(", ", categories);
                if (mode == ComparisonMode.PRESERVE) {
                    violations.add(new Violation(
                            "QUERY_BEHAVIOR_CHANGED", targetState.key(), query.id(), message));
                } else {
                    warnings.add(new Warning(
                            "RECORDED_QUERY_DIFFERENCE", targetState.key(), query.id(), message));
                }
            }
            if (left != null
                    && right != null
                    && !planDetails(left).equals(planDetails(right))) {
                warnings.add(new Warning(
                        "QUERY_PLAN_CHANGED",
                        targetState.key(),
                        query.id(),
                        "EXPLAIN QUERY PLAN details differ from baseline."));
            }
        }

        SchemaDifference schemaDifference = schemaDifference(baseline.schema(), target.schema());
        if (targetState == RunState.AFTER_DOWN && !schemaDifference.equivalent()) {
            violations.add(new Violation(
                    "SCHEMA_ROUND_TRIP_MISMATCH",
                    targetState.key(),
                    "",
                    "Schema or row counts were not restored to the baseline state."));
        }
        return new ComparisonReport(
                RunState.BASELINE.key(), targetState.key(), queryDifferences, schemaDifference);
    }

    private static void validateIntegrity(
            RunState state, SchemaSnapshot schema, List<Violation> violations) {
        for (String issue : schema.integrityIssues()) {
            violations.add(new Violation(
                    "SQLITE_INTEGRITY_CHECK_FAILED", state.key(), "", issue));
        }
        schema.foreignKeyViolations().forEach(issue -> violations.add(new Violation(
                "FOREIGN_KEY_VIOLATION",
                state.key(),
                "",
                "table=" + issue.table() + ", row_id=" + issue.rowId()
                        + ", parent=" + issue.parent() + ", fk_id=" + issue.foreignKeyId())));
    }

    private static void validateOutcome(
            RunState state,
            QuerySpec query,
            QueryObservation observation,
            List<Violation> violations) {
        ExpectedOutcome expected = query.outcomes().expected(state);
        boolean matches = (expected == ExpectedOutcome.SUCCESS
                        && observation.outcome() == QueryOutcome.SUCCESS)
                || (expected == ExpectedOutcome.ERROR
                        && observation.outcome() == QueryOutcome.ERROR);
        if (!matches) {
            String detail = observation.error() == null ? "" : ": " + observation.error().category();
            violations.add(new Violation(
                    "QUERY_OUTCOME_MISMATCH",
                    state.key(),
                    query.id(),
                    "Expected " + expected.name().toLowerCase(Locale.ROOT)
                            + " but observed " + observation.outcome().name().toLowerCase(Locale.ROOT)
                            + detail));
        }
    }

    private static void validateAssertions(
            RunState state,
            QuerySpec query,
            QueryObservation observation,
            List<Violation> violations) {
        Map<String, Integer> columns = uniqueColumnPositions(
                state, query.id(), observation.columns(), violations);
        for (String name : query.assertions().nonNull()) {
            Integer position = columns.get(name);
            if (position == null) {
                violations.add(new Violation(
                        "ASSERTION_COLUMN_MISSING", state.key(), query.id(),
                        "non_null column is missing or ambiguous: " + name));
                continue;
            }
            for (int row = 0; row < observation.rows().size(); row++) {
                if (observation.rows().get(row).values().get(position).type().equals("null")) {
                    violations.add(new Violation(
                            "UNEXPECTED_NULL", state.key(), query.id(),
                            "Column " + name + " is NULL at canonical row " + row + "."));
                }
            }
        }

        if (!query.assertions().uniqueBy().isEmpty()) {
            List<Integer> positions = new ArrayList<>();
            boolean missing = false;
            for (String name : query.assertions().uniqueBy()) {
                Integer position = columns.get(name);
                if (position == null) {
                    missing = true;
                    violations.add(new Violation(
                            "ASSERTION_COLUMN_MISSING", state.key(), query.id(),
                            "unique_by column is missing or ambiguous: " + name));
                } else {
                    positions.add(position);
                }
            }
            if (!missing) {
                Set<String> keys = new HashSet<>();
                for (int row = 0; row < observation.rows().size(); row++) {
                    String key = uniqueKey(observation.rows().get(row), positions);
                    if (!keys.add(key)) {
                        violations.add(new Violation(
                                "DUPLICATE_QUERY_KEY", state.key(), query.id(),
                                "unique_by columns repeat at canonical row " + row + "."));
                    }
                }
            }
        }
    }

    private static Map<String, Integer> uniqueColumnPositions(
            RunState state,
            String queryId,
            List<String> columns,
            List<Violation> violations) {
        Map<String, Integer> positions = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            if (positions.putIfAbsent(column, index) != null) {
                ambiguous.add(column);
            }
        }
        for (String column : ambiguous) {
            positions.remove(column);
            violations.add(new Violation(
                    "AMBIGUOUS_QUERY_COLUMN", state.key(), queryId,
                    "Duplicate result column label: " + column));
        }
        return positions;
    }

    private static void validateFullScans(
            RunState state,
            QuerySpec query,
            QueryObservation observation,
            List<Warning> warnings) {
        Set<String> scans = observation.plan().fullScans().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        for (String table : query.plan().warnOnFullScan()) {
            if (scans.contains(table.toLowerCase(Locale.ROOT))) {
                warnings.add(new Warning(
                        "FULL_TABLE_SCAN",
                        state.key(),
                        query.id(),
                        "EXPLAIN QUERY PLAN reports a full scan of " + table + "."));
            }
        }
    }

    private static void validateSchemaAssertions(
            RunState state,
            SchemaSnapshot schema,
            List<SchemaAssertion> assertions,
            List<Violation> violations) {
        Set<String> indexNames = new HashSet<>(schema.indexNames());
        for (SchemaAssertion assertion : assertions) {
            if (assertion.state() == state && !indexNames.contains(assertion.indexExists())) {
                violations.add(new Violation(
                        "REQUIRED_INDEX_MISSING",
                        state.key(),
                        "",
                        "Required index does not exist: " + assertion.indexExists()));
            }
        }
    }

    private static List<String> differenceCategories(
            QueryObservation left, QueryObservation right, RowOrder rowOrder) {
        List<String> categories = new ArrayList<>();
        if (left == null || right == null) {
            return List.of("missing_observation");
        }
        if (left.outcome() != right.outcome()) {
            categories.add("outcome");
            return List.copyOf(categories);
        }
        if (left.outcome() == QueryOutcome.ERROR) {
            if (!errorEquivalent(left, right)) {
                categories.add("error");
            }
            return List.copyOf(categories);
        }
        if (!left.columns().equals(right.columns())) {
            categories.add("columns");
        }
        if (!rowsEquivalent(left.rows(), right.rows(), rowOrder)) {
            categories.add("rows");
        }
        return List.copyOf(categories);
    }

    static boolean rowsEquivalent(
            List<CanonicalRow> left, List<CanonicalRow> right, RowOrder rowOrder) {
        if (rowOrder == RowOrder.ORDERED) {
            return left.equals(right);
        }
        List<String> leftKeys = left.stream().map(CanonicalRow::canonicalKey).sorted().toList();
        List<String> rightKeys = right.stream().map(CanonicalRow::canonicalKey).sorted().toList();
        return leftKeys.equals(rightKeys);
    }

    private static boolean errorEquivalent(QueryObservation left, QueryObservation right) {
        if (left.error() == null || right.error() == null) {
            return left.error() == right.error();
        }
        return left.error().code() == right.error().code()
                && Objects.equals(left.error().category(), right.error().category())
                && Objects.equals(left.error().sqlState(), right.error().sqlState());
    }

    private static List<String> planDetails(QueryObservation observation) {
        return observation.plan().nodes().stream().map(node -> node.detail()).toList();
    }

    private static String uniqueKey(CanonicalRow row, List<Integer> positions) {
        StringBuilder key = new StringBuilder();
        for (Integer position : positions) {
            CanonicalValue value = row.values().get(position);
            String part = value.canonicalKey();
            key.append(part.length()).append(':').append(part);
        }
        return key.toString();
    }

    private static Map<String, QueryObservation> byQueryId(List<QueryObservation> observations) {
        return observations.stream()
                .collect(Collectors.toMap(QueryObservation::queryId, Function.identity()));
    }

    private static SchemaDifference schemaDifference(
            SchemaSnapshot before, SchemaSnapshot after) {
        Map<String, SchemaObject> left = schemaObjectsByKey(before.objects());
        Map<String, SchemaObject> right = schemaObjectsByKey(after.objects());
        List<String> added = right.keySet().stream()
                .filter(key -> !left.containsKey(key))
                .sorted()
                .toList();
        List<String> removed = left.keySet().stream()
                .filter(key -> !right.containsKey(key))
                .sorted()
                .toList();
        List<String> changed = left.keySet().stream()
                .filter(right::containsKey)
                .filter(key -> !left.get(key).equals(right.get(key)))
                .sorted()
                .toList();

        Map<String, RowCountChange> rowCounts = new TreeMap<>();
        Set<String> tables = new HashSet<>(before.rowCounts().keySet());
        tables.addAll(after.rowCounts().keySet());
        for (String table : tables) {
            Long leftCount = before.rowCounts().get(table);
            Long rightCount = after.rowCounts().get(table);
            if (!Objects.equals(leftCount, rightCount)) {
                rowCounts.put(table, new RowCountChange(leftCount, rightCount));
            }
        }
        return new SchemaDifference(added, removed, changed, rowCounts);
    }

    private static Map<String, SchemaObject> schemaObjectsByKey(List<SchemaObject> objects) {
        Map<String, SchemaObject> values = new LinkedHashMap<>();
        for (SchemaObject object : objects) {
            values.put(object.type() + ":" + object.name(), object);
        }
        return values;
    }
}
