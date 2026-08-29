package dev.migrationreplay.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.migrationreplay.report.ReportModels.RunReport;
import dev.migrationreplay.report.ReportModels.RuntimeInfo;
import dev.migrationreplay.report.ReportModels.Summary;
import dev.migrationreplay.report.ReportModels.Violation;
import dev.migrationreplay.report.ReportModels.Warning;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SarifRendererTest {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void writesGitHubCompatibleRulesResultsAndRepositoryRelativeLocations() throws Exception {
        RunReport report = report(
                List.of(
                        new Violation(
                                "QUERY_BEHAVIOR_CHANGED",
                                "after_up",
                                "active-users",
                                "Query rows changed"),
                        new Violation(
                                "STATE_CAPTURE_FAILED",
                                "",
                                "",
                                "State capture failed")),
                List.of(
                        new Warning(
                                "FULL_TABLE_SCAN",
                                "baseline",
                                "active-users",
                                "Full table scan detected"),
                        new Warning(
                                "QUERY_PLAN_CHANGED",
                                "after_down",
                                "active-users",
                                "Query plan changed")));
        Path output = temporaryDirectory.resolve("reports");

        new ReportWriter().write(output, report, Path.of("examples/data-loss-regression"));

        JsonNode root = JSON_MAPPER.readTree(output.resolve("report.sarif").toFile());
        assertEquals("2.1.0", root.path("version").asText());
        JsonNode run = root.path("runs").path(0);
        assertEquals("MigrationReplay", run.path("tool").path("driver").path("name").asText());
        assertEquals(18, run.path("tool").path("driver").path("rules").size());
        assertEquals(4, run.path("results").size());

        JsonNode queryRule = rule(run, "QUERY_BEHAVIOR_CHANGED");
        assertEquals("error", queryRule.path("defaultConfiguration").path("level").asText());
        assertEquals(
                "A preserve-mode query changed its observable behavior from baseline.",
                queryRule.path("fullDescription").path("text").asText());
        assertEquals(
                "warning",
                rule(run, "FULL_TABLE_SCAN")
                        .path("defaultConfiguration")
                        .path("level")
                        .asText());

        JsonNode queryChange = result(run, "QUERY_BEHAVIOR_CHANGED");
        assertEquals("error", queryChange.path("level").asText());
        assertEquals(
                "examples/data-loss-regression/migration_up.sql",
                artifactUri(queryChange));
        assertEquals(
                "active-users",
                queryChange.path("properties").path("migrationReplayQueryId").asText());
        assertTrue(queryChange
                .path("partialFingerprints")
                .path("primaryLocationLineHash")
                .asText()
                .matches("[0-9a-f]{64}:1"));

        assertEquals(
                "examples/data-loss-regression/migration_down.sql",
                artifactUri(result(run, "QUERY_PLAN_CHANGED")));
        assertEquals(
                "examples/data-loss-regression/queries.yaml",
                artifactUri(result(run, "FULL_TABLE_SCAN")));
        assertEquals(
                "examples/data-loss-regression/baseline_schema.sql",
                artifactUri(result(run, "STATE_CAPTURE_FAILED")));
    }

    @Test
    void omitsAbsoluteTemporaryPathsFromSarif() throws Exception {
        RunReport report = report(
                List.of(new Violation(
                        "QUERY_BEHAVIOR_CHANGED", "after_up", "items", "Rows changed")),
                List.of());
        Path output = temporaryDirectory.resolve("reports");
        Path externalBundle = temporaryDirectory.resolve("bundle");

        new ReportWriter().write(output, report, externalBundle);

        String sarif = Files.readString(output.resolve("report.sarif"));
        JsonNode root = JSON_MAPPER.readTree(sarif);
        assertFalse(sarif.contains(temporaryDirectory.toString()));
        assertEquals(
                "migration_up.sql",
                artifactUri(root.path("runs").path(0).path("results").path(0)));
    }

    @Test
    void writesAnEmptySuccessfulSarifRunForPassingReports() throws Exception {
        Path output = temporaryDirectory.resolve("reports");

        new ReportWriter().write(output, report(List.of(), List.of()), Path.of("examples/pass"));

        JsonNode run = JSON_MAPPER.readTree(output.resolve("report.sarif").toFile())
                .path("runs")
                .path(0);
        assertTrue(run.path("results").isEmpty());
        assertEquals(18, run.path("tool").path("driver").path("rules").size());
        assertEquals(
                "AMBIGUOUS_QUERY_COLUMN",
                run.path("tool").path("driver").path("rules").path(0).path("id").asText());
        assertEquals(
                "UNEXPECTED_NULL",
                run.path("tool").path("driver").path("rules").path(17).path("id").asText());
        assertTrue(run.path("invocations").path(0).path("executionSuccessful").asBoolean());
    }

    @Test
    void rendersMissingMessagesWithoutFailingReportGeneration() throws Exception {
        RunReport report = report(
                List.of(new Violation("STATE_CAPTURE_FAILED", "", "", null)),
                List.of());
        Path output = temporaryDirectory.resolve("reports");

        new ReportWriter().write(output, report, Path.of("examples/pass"));

        JsonNode result = JSON_MAPPER.readTree(output.resolve("report.sarif").toFile())
                .path("runs")
                .path(0)
                .path("results")
                .path(0);
        assertEquals("", result.path("message").path("text").asText());
    }

    @Test
    void rejectsFindingCodesMissingFromTheStableCatalog() {
        RunReport report = report(
                List.of(new Violation("UNREGISTERED_FINDING", "after_up", "items", "Changed")),
                List.of());
        Path output = temporaryDirectory.resolve("reports");

        IOException exception = assertThrows(
                IOException.class,
                () -> new ReportWriter().write(output, report, Path.of("examples/pass")));

        assertEquals(
                "Unknown MigrationReplay finding code: UNREGISTERED_FINDING",
                exception.getMessage());
        assertFalse(Files.exists(output.resolve("report.json")));
        assertFalse(Files.exists(output.resolve("report.sarif")));
    }

    private static JsonNode result(JsonNode run, String ruleId) {
        for (JsonNode result : run.path("results")) {
            if (result.path("ruleId").asText().equals(ruleId)) {
                return result;
            }
        }
        throw new AssertionError("Missing SARIF result " + ruleId);
    }

    private static JsonNode rule(JsonNode run, String ruleId) {
        for (JsonNode rule : run.path("tool").path("driver").path("rules")) {
            if (rule.path("id").asText().equals(ruleId)) {
                return rule;
            }
        }
        throw new AssertionError("Missing SARIF rule " + ruleId);
    }

    private static String artifactUri(JsonNode result) {
        return result.path("locations")
                .path(0)
                .path("physicalLocation")
                .path("artifactLocation")
                .path("uri")
                .asText();
    }

    private static RunReport report(List<Violation> violations, List<Warning> warnings) {
        return new RunReport(
                "1",
                violations.isEmpty() ? "PASS" : "FAIL",
                Map.of("queries.yaml", "hash"),
                new RuntimeInfo("21", "3"),
                Map.of(),
                Map.of(),
                violations,
                warnings,
                new Summary(1, 0, violations.size(), warnings.size()));
    }
}
