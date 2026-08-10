package dev.migrationreplay.report;

import dev.migrationreplay.replay.ReplayModels.StateSnapshot;
import java.util.List;
import java.util.Map;

public final class ReportModels {
    private ReportModels() {}

    public record Violation(String code, String state, String queryId, String message) {}

    public record Warning(String code, String state, String queryId, String message) {}

    public record QueryDifference(
            String queryId, boolean equivalent, String policy, List<String> categories) {
        public QueryDifference {
            categories = List.copyOf(categories);
        }
    }

    public record RowCountChange(Long before, Long after) {}

    public record SchemaDifference(
            List<String> addedObjects,
            List<String> removedObjects,
            List<String> changedObjects,
            Map<String, RowCountChange> rowCountChanges) {
        public SchemaDifference {
            addedObjects = List.copyOf(addedObjects);
            removedObjects = List.copyOf(removedObjects);
            changedObjects = List.copyOf(changedObjects);
            rowCountChanges = Map.copyOf(rowCountChanges);
        }

        public boolean equivalent() {
            return addedObjects.isEmpty()
                    && removedObjects.isEmpty()
                    && changedObjects.isEmpty()
                    && rowCountChanges.isEmpty();
        }
    }

    public record ComparisonReport(
            String fromState,
            String toState,
            List<QueryDifference> queryDifferences,
            SchemaDifference schemaDifference) {
        public ComparisonReport {
            queryDifferences = List.copyOf(queryDifferences);
        }
    }

    public record RuntimeInfo(String javaVersion, String sqliteVersion) {}

    public record Summary(
            int configuredQueries,
            int capturedStates,
            int violations,
            int warnings) {}

    public record RunReport(
            String reportVersion,
            String status,
            Map<String, String> inputs,
            RuntimeInfo runtime,
            Map<String, StateSnapshot> states,
            Map<String, ComparisonReport> comparisons,
            List<Violation> violations,
            List<Warning> warnings,
            Summary summary) {
        public RunReport {
            inputs = Map.copyOf(inputs);
            states = Map.copyOf(states);
            comparisons = Map.copyOf(comparisons);
            violations = List.copyOf(violations);
            warnings = List.copyOf(warnings);
        }
    }
}
