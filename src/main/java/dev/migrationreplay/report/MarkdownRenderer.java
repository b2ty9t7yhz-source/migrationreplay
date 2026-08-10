package dev.migrationreplay.report;

import dev.migrationreplay.replay.ReplayModels.QueryObservation;
import dev.migrationreplay.replay.ReplayModels.StateSnapshot;
import dev.migrationreplay.report.ReportModels.ComparisonReport;
import dev.migrationreplay.report.ReportModels.QueryDifference;
import dev.migrationreplay.report.ReportModels.RunReport;
import dev.migrationreplay.report.ReportModels.Violation;
import dev.migrationreplay.report.ReportModels.Warning;
import java.util.Comparator;
import java.util.Map;

public final class MarkdownRenderer {
    public String render(RunReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# MigrationReplay Report\n\n");
        markdown.append("**Status:** ").append(report.status()).append("\n\n");
        markdown.append("## Summary\n\n");
        markdown.append("- Configured queries: ").append(report.summary().configuredQueries()).append('\n');
        markdown.append("- Captured states: ").append(report.summary().capturedStates()).append('\n');
        markdown.append("- Violations: ").append(report.summary().violations()).append('\n');
        markdown.append("- Warnings: ").append(report.summary().warnings()).append("\n\n");

        markdown.append("## Runtime\n\n");
        markdown.append("- Java: `").append(escapeCode(report.runtime().javaVersion())).append("`\n");
        markdown.append("- SQLite: `").append(escapeCode(report.runtime().sqliteVersion())).append("`\n\n");

        markdown.append("## Input fingerprints\n\n");
        markdown.append("| File | SHA-256 |\n|---|---|\n");
        report.inputs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> markdown
                        .append("| ").append(escape(entry.getKey()))
                        .append(" | `").append(escapeCode(entry.getValue())).append("` |\n"));
        markdown.append('\n');

        appendViolations(markdown, report);
        appendWarnings(markdown, report);
        appendStates(markdown, report);
        appendComparisons(markdown, report);
        return markdown.toString();
    }

    private static void appendViolations(StringBuilder markdown, RunReport report) {
        markdown.append("## Violations\n\n");
        if (report.violations().isEmpty()) {
            markdown.append("None.\n\n");
            return;
        }
        markdown.append("| Code | State | Query | Message |\n|---|---|---|---|\n");
        for (Violation violation : report.violations()) {
            markdown.append("| ").append(escape(violation.code()))
                    .append(" | ").append(escape(violation.state()))
                    .append(" | ").append(escape(violation.queryId()))
                    .append(" | ").append(escape(violation.message())).append(" |\n");
        }
        markdown.append('\n');
    }

    private static void appendWarnings(StringBuilder markdown, RunReport report) {
        markdown.append("## Warnings\n\n");
        if (report.warnings().isEmpty()) {
            markdown.append("None.\n\n");
            return;
        }
        markdown.append("| Code | State | Query | Message |\n|---|---|---|---|\n");
        for (Warning warning : report.warnings()) {
            markdown.append("| ").append(escape(warning.code()))
                    .append(" | ").append(escape(warning.state()))
                    .append(" | ").append(escape(warning.queryId()))
                    .append(" | ").append(escape(warning.message())).append(" |\n");
        }
        markdown.append('\n');
    }

    private static void appendStates(StringBuilder markdown, RunReport report) {
        markdown.append("## Captured states\n\n");
        report.states().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendState(markdown, entry.getValue()));
    }

    private static void appendState(StringBuilder markdown, StateSnapshot state) {
        markdown.append("### ").append(escape(state.state())).append("\n\n");
        markdown.append("Table row counts:\n\n");
        if (state.schema().rowCounts().isEmpty()) {
            markdown.append("- None\n\n");
        } else {
            state.schema().rowCounts().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> markdown.append("- `")
                            .append(escapeCode(entry.getKey())).append("`: ")
                            .append(entry.getValue()).append('\n'));
            markdown.append('\n');
        }
        markdown.append("| Query | Outcome | Rows | Error |\n|---|---|---:|---|\n");
        state.queries().stream()
                .sorted(Comparator.comparing(QueryObservation::queryId))
                .forEach(query -> markdown.append("| ").append(escape(query.queryId()))
                        .append(" | ").append(query.outcome())
                        .append(" | ").append(query.rows().size())
                        .append(" | ")
                        .append(query.error() == null ? "" : escape(query.error().category()))
                        .append(" |\n"));
        markdown.append('\n');
    }

    private static void appendComparisons(StringBuilder markdown, RunReport report) {
        markdown.append("## Comparisons\n\n");
        if (report.comparisons().isEmpty()) {
            markdown.append("None.\n");
            return;
        }
        report.comparisons().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendComparison(markdown, entry.getKey(), entry.getValue()));
    }

    private static void appendComparison(
            StringBuilder markdown, String name, ComparisonReport comparison) {
        markdown.append("### ").append(escape(name)).append("\n\n");
        markdown.append("| Query | Equivalent | Policy | Differences |\n|---|---|---|---|\n");
        comparison.queryDifferences().stream()
                .sorted(Comparator.comparing(QueryDifference::queryId))
                .forEach(difference -> markdown.append("| ")
                        .append(escape(difference.queryId()))
                        .append(" | ").append(difference.equivalent())
                        .append(" | ").append(escape(difference.policy()))
                        .append(" | ").append(escape(String.join(", ", difference.categories())))
                        .append(" |\n"));
        markdown.append('\n');
        markdown.append("Schema objects added: ")
                .append(escape(comparison.schemaDifference().addedObjects().toString())).append("\n\n");
        markdown.append("Schema objects removed: ")
                .append(escape(comparison.schemaDifference().removedObjects().toString())).append("\n\n");
        markdown.append("Schema objects changed: ")
                .append(escape(comparison.schemaDifference().changedObjects().toString())).append("\n\n");
        markdown.append("Row-count changes: ")
                .append(escape(comparison.schemaDifference().rowCountChanges().toString())).append("\n\n");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String escapeCode(String value) {
        return value == null ? "" : value.replace("`", "\\`").replace("\n", " ");
    }
}
