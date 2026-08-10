package dev.migrationreplay.replay;

import java.util.List;
import java.util.Map;

public final class ReplayModels {
    private ReplayModels() {}

    public enum QueryOutcome {
        SUCCESS,
        ERROR
    }

    public record CanonicalValue(String type, String value) {
        public String canonicalKey() {
            String content = value == null ? "" : value;
            return type + ":" + content.length() + ":" + content;
        }
    }

    public record CanonicalRow(List<CanonicalValue> values) {
        public CanonicalRow {
            values = List.copyOf(values);
        }

        public String canonicalKey() {
            StringBuilder key = new StringBuilder();
            for (CanonicalValue value : values) {
                String valueKey = value.canonicalKey();
                key.append(valueKey.length()).append(':').append(valueKey);
            }
            return key.toString();
        }
    }

    public record QueryError(String category, int code, String sqlState, String message) {}

    public record PlanNode(int id, int parent, String detail) {}

    public record QueryPlan(List<PlanNode> nodes, List<String> fullScans) {
        public QueryPlan {
            nodes = List.copyOf(nodes);
            fullScans = List.copyOf(fullScans);
        }
    }

    public record QueryObservation(
            String queryId,
            QueryOutcome outcome,
            List<String> columns,
            List<CanonicalRow> rows,
            QueryError error,
            QueryPlan plan) {
        public QueryObservation {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    public record SchemaObject(String type, String name, String tableName, String sql) {}

    public record ForeignKeyViolation(String table, String rowId, String parent, int foreignKeyId) {}

    public record SchemaSnapshot(
            List<SchemaObject> objects,
            Map<String, Long> rowCounts,
            List<String> integrityIssues,
            List<ForeignKeyViolation> foreignKeyViolations,
            List<String> indexNames) {
        public SchemaSnapshot {
            objects = List.copyOf(objects);
            rowCounts = Map.copyOf(rowCounts);
            integrityIssues = List.copyOf(integrityIssues);
            foreignKeyViolations = List.copyOf(foreignKeyViolations);
            indexNames = List.copyOf(indexNames);
        }
    }

    public record StateSnapshot(
            String state, SchemaSnapshot schema, List<QueryObservation> queries) {
        public StateSnapshot {
            queries = List.copyOf(queries);
        }
    }
}
