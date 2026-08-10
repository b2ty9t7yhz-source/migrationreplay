package dev.migrationreplay.replay;

import dev.migrationreplay.config.ConfigModels.ParameterValue;
import dev.migrationreplay.config.ConfigModels.QuerySpec;
import dev.migrationreplay.replay.ReplayModels.CanonicalRow;
import dev.migrationreplay.replay.ReplayModels.CanonicalValue;
import dev.migrationreplay.replay.ReplayModels.PlanNode;
import dev.migrationreplay.replay.ReplayModels.QueryError;
import dev.migrationreplay.replay.ReplayModels.QueryObservation;
import dev.migrationreplay.replay.ReplayModels.QueryOutcome;
import dev.migrationreplay.replay.ReplayModels.QueryPlan;
import dev.migrationreplay.sql.NamedParameterSql;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.sqlite.SQLiteException;

public final class QueryRunner {
    private static final int QUERY_TIMEOUT_SECONDS = 5;
    private static final int MAX_RESULT_ROWS = 100_000;
    private static final Pattern FULL_SCAN = Pattern.compile(
            "^SCAN\\s+(?:TABLE\\s+)?([^\\s]+)", Pattern.CASE_INSENSITIVE);

    public List<QueryObservation> runAll(Connection connection, List<QuerySpec> queries)
            throws SQLException {
        List<QueryObservation> observations = new ArrayList<>();
        for (QuerySpec query : queries) {
            observations.add(run(connection, query));
        }
        return List.copyOf(observations);
    }

    public QueryObservation run(Connection connection, QuerySpec query) throws SQLException {
        NamedParameterSql compiled;
        try {
            compiled = NamedParameterSql.compile(query.sql());
        } catch (dev.migrationreplay.config.ConfigurationException exception) {
            throw new SQLException("Validated query could not be compiled.", exception);
        }

        try (PreparedStatement statement = connection.prepareStatement(compiled.jdbcSql())) {
            configure(statement);
            bind(statement, compiled, query);
            if (!statement.execute()) {
                throw new SQLException("Read-only query did not produce a result set.");
            }
            List<String> columns;
            List<CanonicalRow> rows;
            try (ResultSet result = statement.getResultSet()) {
                ResultSetMetaData metadata = result.getMetaData();
                columns = columns(metadata);
                rows = rows(result, metadata.getColumnCount());
            }
            QueryPlan plan = explain(connection, compiled, query);
            return new QueryObservation(
                    query.id(), QueryOutcome.SUCCESS, columns, rows, null, plan);
        } catch (SQLException exception) {
            return new QueryObservation(
                    query.id(),
                    QueryOutcome.ERROR,
                    List.of(),
                    List.of(),
                    normalizeError(exception),
                    new QueryPlan(List.of(), List.of()));
        }
    }

    private QueryPlan explain(
            Connection connection, NamedParameterSql compiled, QuerySpec query) throws SQLException {
        List<PlanNode> nodes = new ArrayList<>();
        List<String> fullScans = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "EXPLAIN QUERY PLAN " + compiled.jdbcSql())) {
            configure(statement);
            bind(statement, compiled, query);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String detail = rows.getString(4);
                    nodes.add(new PlanNode(rows.getInt(1), rows.getInt(2), detail));
                    Matcher matcher = FULL_SCAN.matcher(detail == null ? "" : detail);
                    if (matcher.find()) {
                        fullScans.add(stripIdentifierQuotes(matcher.group(1)));
                    }
                }
            }
        }
        return new QueryPlan(nodes, fullScans.stream().distinct().sorted().toList());
    }

    private static void configure(PreparedStatement statement) throws SQLException {
        statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        statement.setMaxRows(MAX_RESULT_ROWS + 1);
    }

    private static void bind(
            PreparedStatement statement, NamedParameterSql compiled, QuerySpec query)
            throws SQLException {
        for (int index = 0; index < compiled.parameterNames().size(); index++) {
            String name = compiled.parameterNames().get(index);
            ParameterValue value = query.parameters().get(name);
            if (value == null) {
                throw new SQLException("Missing validated parameter: " + name);
            }
            value.bind(statement, index + 1);
        }
    }

    private static List<String> columns(ResultSetMetaData metadata) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            columns.add(metadata.getColumnLabel(index));
        }
        return List.copyOf(columns);
    }

    private static List<CanonicalRow> rows(ResultSet result, int columnCount)
            throws SQLException {
        List<CanonicalRow> rows = new ArrayList<>();
        while (result.next()) {
            if (rows.size() >= MAX_RESULT_ROWS) {
                throw new SQLException("Query exceeded the V1 row limit of " + MAX_RESULT_ROWS + ".");
            }
            List<CanonicalValue> values = new ArrayList<>();
            for (int column = 1; column <= columnCount; column++) {
                values.add(canonicalValue(result, column));
            }
            rows.add(new CanonicalRow(values));
        }
        return List.copyOf(rows);
    }

    static CanonicalValue canonicalValue(ResultSet result, int column) throws SQLException {
        Object value = result.getObject(column);
        if (value == null || result.wasNull()) {
            return new CanonicalValue("null", null);
        }
        if (value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger) {
            return new CanonicalValue("integer", value.toString());
        }
        if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) {
            double number = ((Number) value).doubleValue();
            String encoded;
            if (Double.isNaN(number)) {
                encoded = "NaN";
            } else if (number == Double.POSITIVE_INFINITY) {
                encoded = "Infinity";
            } else if (number == Double.NEGATIVE_INFINITY) {
                encoded = "-Infinity";
            } else {
                encoded = Double.toHexString(number);
            }
            return new CanonicalValue("real", encoded);
        }
        if (value instanceof byte[] bytes) {
            return new CanonicalValue("blob", Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof String text) {
            return new CanonicalValue("text", text);
        }
        throw new SQLException(
                "Unsupported JDBC result type: " + value.getClass().getName());
    }

    private static QueryError normalizeError(SQLException exception) {
        String category = exception.getClass().getSimpleName();
        if (exception instanceof SQLiteException sqliteException
                && sqliteException.getResultCode() != null) {
            category = sqliteException.getResultCode().name();
        }
        String message = exception.getMessage();
        return new QueryError(
                category,
                exception.getErrorCode(),
                exception.getSQLState(),
                message == null ? "" : message.replaceAll("\\s+", " ").trim());
    }

    private static String stripIdentifierQuotes(String value) {
        String result = value;
        if (result.length() >= 2) {
            char first = result.charAt(0);
            char last = result.charAt(result.length() - 1);
            if ((first == '"' && last == '"')
                    || (first == '`' && last == '`')
                    || (first == '[' && last == ']')) {
                result = result.substring(1, result.length() - 1);
            }
        }
        return result.toLowerCase(Locale.ROOT);
    }
}
