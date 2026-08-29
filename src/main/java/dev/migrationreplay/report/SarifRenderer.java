package dev.migrationreplay.report;

import dev.migrationreplay.report.ReportModels.RunReport;
import dev.migrationreplay.report.ReportModels.Violation;
import dev.migrationreplay.report.ReportModels.Warning;
import dev.migrationreplay.report.SarifRuleCatalog.LocationPolicy;
import dev.migrationreplay.report.SarifRuleCatalog.Rule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SarifRenderer {
    private static final String SCHEMA =
            "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String INFORMATION_URI =
            "https://github.com/b2ty9t7yhz-source/migrationreplay";

    Map<String, Object> render(RunReport report, Path bundleDirectory) throws IOException {
        List<Map<String, Object>> rules = rules();
        Map<String, Integer> ruleIndexes = new LinkedHashMap<>();
        for (int index = 0; index < rules.size(); index++) {
            ruleIndexes.put((String) rules.get(index).get("id"), index);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (Violation violation : report.violations()) {
            results.add(result(
                    violation.code(),
                    "error",
                    violation.state(),
                    violation.queryId(),
                    violation.message(),
                    ruleIndexes.get(violation.code()),
                    bundleDirectory));
        }
        for (Warning warning : report.warnings()) {
            results.add(result(
                    warning.code(),
                    "warning",
                    warning.state(),
                    warning.queryId(),
                    warning.message(),
                    ruleIndexes.get(warning.code()),
                    bundleDirectory));
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "MigrationReplay");
        driver.put("informationUri", INFORMATION_URI);
        driver.put("rules", rules);

        Map<String, Object> tool = Map.of("driver", driver);
        Map<String, Object> invocation = Map.of("executionSuccessful", true);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("migrationReplayReportVersion", report.reportVersion());
        properties.put("migrationReplayStatus", report.status());

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", tool);
        run.put("invocations", List.of(invocation));
        run.put("results", results);
        run.put("properties", properties);

        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", SCHEMA);
        sarif.put("version", "2.1.0");
        sarif.put("runs", List.of(run));
        return sarif;
    }

    private static List<Map<String, Object>> rules() {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Rule catalogRule : SarifRuleCatalog.rules()) {
            Map<String, Object> configuration = Map.of("level", catalogRule.level());
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("id", catalogRule.id());
            rule.put("name", catalogRule.name());
            rule.put("shortDescription", Map.of("text", catalogRule.name()));
            rule.put("fullDescription", Map.of("text", catalogRule.description()));
            rule.put("defaultConfiguration", configuration);
            rules.add(rule);
        }
        return rules;
    }

    private static Map<String, Object> result(
            String code,
            String level,
            String state,
            String queryId,
            String message,
            Integer ruleIndex,
            Path bundleDirectory)
            throws IOException {
        Rule rule = requireRule(code, level);
        if (ruleIndex == null) {
            throw new IOException("Missing SARIF rule index for finding code: " + code);
        }
        String stableMessage = message == null ? "" : message;
        String artifact = artifactFor(rule.locationPolicy(), state, queryId);
        String artifactUri = repositoryRelativeUri(bundleDirectory, artifact);

        Map<String, Object> artifactLocation = Map.of("uri", artifactUri);
        Map<String, Object> physicalLocation = Map.of("artifactLocation", artifactLocation);
        Map<String, Object> location = Map.of("physicalLocation", physicalLocation);

        Map<String, Object> properties = new LinkedHashMap<>();
        if (state != null && !state.isBlank()) {
            properties.put("migrationReplayState", state);
        }
        if (queryId != null && !queryId.isBlank()) {
            properties.put("migrationReplayQueryId", queryId);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", code);
        result.put("ruleIndex", ruleIndex);
        result.put("level", level);
        result.put("message", Map.of("text", stableMessage));
        result.put("locations", List.of(location));
        result.put(
                "partialFingerprints",
                Map.of("primaryLocationLineHash", fingerprint(code, state, queryId, stableMessage)));
        result.put("properties", properties);
        return result;
    }

    private static String artifactFor(
            LocationPolicy locationPolicy, String state, String queryId) {
        if (locationPolicy == LocationPolicy.MIGRATION_UP) {
            return "migration_up.sql";
        }
        if (locationPolicy == LocationPolicy.MIGRATION_DOWN) {
            return "migration_down.sql";
        }
        if (locationPolicy == LocationPolicy.BASELINE_SCHEMA) {
            return "baseline_schema.sql";
        }
        String normalizedState = state == null ? "" : state;
        return switch (normalizedState) {
            case "after_up", "migration up" -> "migration_up.sql";
            case "after_down", "migration down" -> "migration_down.sql";
            case "baseline" -> queryId == null || queryId.isBlank()
                    ? "baseline_schema.sql"
                    : "queries.yaml";
            default -> queryId == null || queryId.isBlank()
                    ? "baseline_schema.sql"
                    : "queries.yaml";
        };
    }

    private static String repositoryRelativeUri(Path bundleDirectory, String artifact) {
        Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
        Path absoluteArtifact = bundleDirectory
                .toAbsolutePath()
                .normalize()
                .resolve(artifact)
                .normalize();
        Path displayPath = absoluteArtifact.startsWith(repositoryRoot)
                ? repositoryRoot.relativize(absoluteArtifact)
                : Path.of(artifact);
        return displayPath.toString().replace('\\', '/');
    }

    private static String fingerprint(
            String code, String state, String queryId, String message) {
        String identity = String.join(
                "\u0000",
                code,
                state == null ? "" : state,
                queryId == null ? "" : queryId,
                message == null ? "" : message);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest) + ":1";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Rule requireRule(String code, String observedLevel) throws IOException {
        Rule rule = SarifRuleCatalog.find(code);
        if (rule == null) {
            throw new IOException("Unknown MigrationReplay finding code: " + code);
        }
        if (!rule.level().equals(observedLevel)) {
            throw new IOException(
                    "MigrationReplay finding " + code + " must use SARIF level " + rule.level());
        }
        return rule;
    }
}
