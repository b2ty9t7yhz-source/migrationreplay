package dev.migrationreplay.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.migrationreplay.config.ConfigLoader;
import dev.migrationreplay.config.InputBundle;
import dev.migrationreplay.report.ReportModels.RunReport;
import dev.migrationreplay.report.ReportWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MigrationReplayEngineTest {
    private static final String BASELINE_SCHEMA = """
            CREATE TABLE items (
                id INTEGER PRIMARY KEY,
                value TEXT
            );
            """;
    private static final String FIXTURES = """
            INSERT INTO items (id, value) VALUES (1, 'one');
            INSERT INTO items (id, value) VALUES (2, 'two');
            """;
    private static final String PRESERVE_ITEMS = """
            version: 1
            queries:
              - id: items
                sql: SELECT id, value FROM items ORDER BY id
                compare:
                  row_order: ordered
                assertions:
                  non_null: [id, value]
                  unique_by: [id]
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void passesACompleteMigrationRoundTrip() throws Exception {
        RunReport report = run(
                "CREATE TABLE audit (id INTEGER PRIMARY KEY);"
                        + " INSERT INTO audit VALUES (1);",
                "DROP TABLE audit;",
                PRESERVE_ITEMS);

        assertEquals("PASS", report.status());
        assertEquals(3, report.summary().capturedStates());
        assertTrue(report.violations().isEmpty());
        assertTrue(report.comparisons().get("baseline_to_after_down")
                .schemaDifference().equivalent());
    }

    @Test
    void failsWhenMigrationUpChangesPreservedBehavior() throws Exception {
        RunReport report = run(
                "UPDATE items SET value = upper(value);",
                "UPDATE items SET value = lower(value);",
                PRESERVE_ITEMS);

        assertEquals("FAIL", report.status());
        assertTrue(hasViolation(report, "QUERY_BEHAVIOR_CHANGED", "after_up"));
    }

    @Test
    void failsWhenDownDoesNotRestoreSchema() throws Exception {
        RunReport report = run(
                "CREATE TABLE leftover (id INTEGER PRIMARY KEY);",
                "DELETE FROM leftover;",
                PRESERVE_ITEMS);

        assertTrue(hasViolation(report, "SCHEMA_ROUND_TRIP_MISMATCH", "after_down"));
    }

    @Test
    void failsWhenDownRestoresSchemaButNotDataBehavior() throws Exception {
        RunReport report = run(
                "UPDATE items SET value = upper(value);",
                "UPDATE items SET value = 'not-restored';",
                PRESERVE_ITEMS);

        assertTrue(hasViolation(report, "QUERY_BEHAVIOR_CHANGED", "after_down"));
        assertTrue(report.comparisons().get("baseline_to_after_down")
                .schemaDifference().equivalent());
    }

    @Test
    void failsWhenAMigrationUnexpectedlyMakesAQueryError() throws Exception {
        RunReport report = run(
                "DROP TABLE items;",
                "CREATE TABLE items (id INTEGER PRIMARY KEY, value TEXT);"
                        + " INSERT INTO items VALUES (1, 'one');"
                        + " INSERT INTO items VALUES (2, 'two');",
                PRESERVE_ITEMS);

        assertTrue(hasViolation(report, "QUERY_OUTCOME_MISMATCH", "after_up"));
    }

    @Test
    void recordsRowCountChangesInTheSchemaComparison() throws Exception {
        String config = """
                version: 1
                queries:
                  - id: items
                    sql: SELECT id, value FROM items ORDER BY id
                    compare:
                      baseline_to_up: record_only
                      row_order: ordered
                """;
        RunReport report = run(
                "INSERT INTO items VALUES (3, 'three');",
                "DELETE FROM items WHERE id = 3;",
                config);

        var change = report.comparisons().get("baseline_to_after_up")
                .schemaDifference().rowCountChanges().get("items");
        assertEquals(2L, change.before());
        assertEquals(3L, change.after());
        assertEquals("PASS", report.status());
    }

    @Test
    void reportsMigrationUpFailureAndStopsBeforeAfterUpCapture() throws Exception {
        RunReport report = run(
                "CREATE TABLE transient_table (id INTEGER);"
                        + " INSERT INTO items (id, value) VALUES (1, 'duplicate');",
                "DROP TABLE transient_table;",
                PRESERVE_ITEMS);

        assertTrue(hasViolation(report, "MIGRATION_UP_FAILED", "migration up"));
        assertEquals(1, report.summary().capturedStates());
        assertFalse(report.states().containsKey("after_up"));
    }

    @Test
    void reportsMigrationDownFailureAndKeepsTheAfterUpEvidence() throws Exception {
        RunReport report = run(
                "CREATE TABLE created_table (id INTEGER);",
                "DROP TABLE missing_table;",
                PRESERVE_ITEMS);

        assertTrue(hasViolation(report, "MIGRATION_DOWN_FAILED", "migration down"));
        assertEquals(2, report.summary().capturedStates());
        assertTrue(report.states().containsKey("after_up"));
    }

    @Test
    void reportsMissingRequiredIndexes() throws Exception {
        String config = PRESERVE_ITEMS + """
                schema_assertions:
                  - state: after_up
                    index_exists: idx_items_value
                """;

        RunReport report = run("SELECT 1;", "SELECT 1;", config);

        assertTrue(hasViolation(report, "REQUIRED_INDEX_MISSING", "after_up"));
    }

    @Test
    void reportsNonNullAndUniquenessAssertionFailures() throws Exception {
        String fixtures = """
                INSERT INTO items (id, value) VALUES (1, NULL);
                INSERT INTO items (id, value) VALUES (2, NULL);
                """;
        String config = """
                version: 1
                queries:
                  - id: item-values
                    sql: SELECT value FROM items ORDER BY id
                    compare:
                      row_order: ordered
                    assertions:
                      non_null: [value]
                      unique_by: [value]
                """;

        RunReport report = runWithFixtures(fixtures, "SELECT 1;", "SELECT 1;", config);

        assertTrue(hasViolation(report, "UNEXPECTED_NULL", "baseline"));
        assertTrue(hasViolation(report, "DUPLICATE_QUERY_KEY", "baseline"));
    }

    @Test
    void supportsExpectedErrorToSuccessTransitionsAsRecordOnly() throws Exception {
        String config = """
                version: 1
                queries:
                  - id: optional-feature
                    sql: SELECT id FROM optional_feature ORDER BY id
                    outcomes:
                      baseline: error
                      after_up: success
                      after_down: error
                    compare:
                      baseline_to_up: record_only
                      baseline_to_down: preserve
                      row_order: ordered
                """;

        RunReport report = run(
                "CREATE TABLE optional_feature (id INTEGER PRIMARY KEY);"
                        + " INSERT INTO optional_feature VALUES (1);",
                "DROP TABLE optional_feature;",
                config);

        assertEquals("PASS", report.status());
        assertTrue(report.warnings().stream()
                .anyMatch(warning -> warning.code().equals("RECORDED_QUERY_DIFFERENCE")));
    }

    @Test
    void writesByteIdenticalReportsForIdenticalInputsAndRuntime() throws Exception {
        InputBundle bundle = bundle(
                FIXTURES,
                "CREATE TABLE audit (id INTEGER PRIMARY KEY);",
                "DROP TABLE audit;",
                PRESERVE_ITEMS);
        RunReport first = new MigrationReplayEngine().run(bundle);
        RunReport second = new MigrationReplayEngine().run(bundle);
        Path firstDirectory = temporaryDirectory.resolve("first");
        Path secondDirectory = temporaryDirectory.resolve("second");

        new ReportWriter().write(firstDirectory, first);
        new ReportWriter().write(secondDirectory, second);

        assertEquals(
                Files.readString(firstDirectory.resolve("report.json")),
                Files.readString(secondDirectory.resolve("report.json")));
        assertEquals(
                Files.readString(firstDirectory.resolve("report.md")),
                Files.readString(secondDirectory.resolve("report.md")));
    }

    private RunReport run(String up, String down, String yaml) throws Exception {
        return runWithFixtures(FIXTURES, up, down, yaml);
    }

    private RunReport runWithFixtures(String fixtures, String up, String down, String yaml)
            throws Exception {
        return new MigrationReplayEngine().run(bundle(fixtures, up, down, yaml));
    }

    private static InputBundle bundle(String fixtures, String up, String down, String yaml)
            throws Exception {
        return new InputBundle(
                Path.of("synthetic-bundle"),
                BASELINE_SCHEMA,
                fixtures,
                up,
                down,
                new ConfigLoader().load(yaml),
                Map.of(
                        "baseline_schema.sql", "baseline-hash",
                        "fixtures.sql", "fixtures-hash",
                        "migration_up.sql", "up-hash",
                        "migration_down.sql", "down-hash",
                        "queries.yaml", "queries-hash"));
    }

    private static boolean hasViolation(RunReport report, String code, String state) {
        return report.violations().stream()
                .anyMatch(violation -> violation.code().equals(code)
                        && violation.state().equals(state));
    }
}
