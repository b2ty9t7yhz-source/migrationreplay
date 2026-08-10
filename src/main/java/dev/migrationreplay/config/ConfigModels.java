package dev.migrationreplay.config;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public final class ConfigModels {
    private ConfigModels() {}

    public enum RunState {
        BASELINE("baseline"),
        AFTER_UP("after_up"),
        AFTER_DOWN("after_down");

        private final String key;

        RunState(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static RunState fromKey(String value) {
            for (RunState state : values()) {
                if (state.key.equals(value)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("Unknown run state: " + value);
        }
    }

    public enum ExpectedOutcome {
        SUCCESS,
        ERROR
    }

    public enum ComparisonMode {
        PRESERVE,
        RECORD_ONLY
    }

    public enum RowOrder {
        ORDERED,
        UNORDERED
    }

    public enum ValueType {
        NULL,
        INTEGER,
        REAL,
        TEXT,
        BLOB
    }

    public record ParameterValue(ValueType type, Object value) {
        public void bind(PreparedStatement statement, int index) throws SQLException {
            switch (type) {
                case NULL -> statement.setObject(index, null);
                case INTEGER -> statement.setLong(index, (Long) value);
                case REAL -> statement.setDouble(index, (Double) value);
                case TEXT -> statement.setString(index, (String) value);
                case BLOB -> statement.setBytes(index, (byte[]) value);
            }
        }
    }

    public record Outcomes(Map<RunState, ExpectedOutcome> values) {
        public ExpectedOutcome expected(RunState state) {
            return values.getOrDefault(state, ExpectedOutcome.SUCCESS);
        }
    }

    public record CompareSpec(
            ComparisonMode baselineToUp,
            ComparisonMode baselineToDown,
            RowOrder rowOrder) {}

    public record AssertionSpec(List<String> nonNull, List<String> uniqueBy) {}

    public record PlanSpec(List<String> warnOnFullScan) {}

    public record QuerySpec(
            String id,
            String sql,
            Map<String, ParameterValue> parameters,
            Outcomes outcomes,
            CompareSpec compare,
            AssertionSpec assertions,
            PlanSpec plan) {}

    public record SchemaAssertion(RunState state, String indexExists) {}

    public record QueriesConfig(
            int version, List<QuerySpec> queries, List<SchemaAssertion> schemaAssertions) {}
}
