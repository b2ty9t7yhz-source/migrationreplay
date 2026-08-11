package dev.migrationreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainTest {
    private static final String[] INPUT_FILES = {
        "baseline_schema.sql",
        "fixtures.sql",
        "migration_up.sql",
        "migration_down.sql",
        "queries.yaml"
    };

    @TempDir
    Path temporaryDirectory;

    @Test
    void returnsZeroForHelp() {
        Invocation invocation = invoke("--help");

        assertEquals(0, invocation.exitCode());
        assertTrue(invocation.standardOutput().contains("MigrationReplay 1.0.1"));
    }

    @Test
    void reportsTheReleaseVersion() {
        Invocation invocation = invoke("--version");

        assertEquals(0, invocation.exitCode());
        assertEquals("MigrationReplay 1.0.1\n", invocation.standardOutput());
        assertEquals("", invocation.standardError());
    }

    @Test
    void returnsStructuredErrorForUnknownCommands() {
        Invocation invocation = invoke("unknown");

        assertEquals(2, invocation.exitCode());
        assertTrue(invocation.standardError().contains("\"code\":\"INVALID_COMMAND\""));
    }

    @Test
    void validatesTheExampleBundle() {
        Invocation invocation = invoke("validate", "examples/add-user-email");

        assertEquals(0, invocation.exitCode());
        assertTrue(invocation.standardOutput().startsWith("VALID\nqueries=2\n"));
    }

    @Test
    void runsTheExampleAndWritesBothReports() {
        Path output = temporaryDirectory.resolve("passing-report");

        Invocation invocation = invoke(
                "run", "examples/add-user-email", "--output-dir", output.toString());

        assertEquals(0, invocation.exitCode());
        assertTrue(invocation.standardOutput().contains("status=PASS"));
        assertTrue(Files.isRegularFile(output.resolve("report.json")));
        assertTrue(Files.isRegularFile(output.resolve("report.md")));
    }

    @Test
    void returnsOneAndStillWritesEvidenceForDetectedRegressions() throws Exception {
        Path bundle = temporaryDirectory.resolve("failing-bundle");
        copyExampleBundle(bundle);
        Files.writeString(
                bundle.resolve("migration_up.sql"),
                "UPDATE users SET display_name = upper(display_name);",
                StandardCharsets.UTF_8);
        Files.writeString(
                bundle.resolve("migration_down.sql"),
                "UPDATE users SET display_name = lower(display_name);",
                StandardCharsets.UTF_8);
        Path output = temporaryDirectory.resolve("failing-report");

        Invocation invocation = invoke(
                "run", bundle.toString(), "--output-dir", output.toString());

        assertEquals(1, invocation.exitCode());
        assertTrue(invocation.standardOutput().contains("status=FAIL"));
        assertTrue(Files.readString(output.resolve("report.json"))
                .contains("QUERY_BEHAVIOR_CHANGED"));
    }

    @Test
    void shippedRegressionExampleDetectsDataLossAfterRollback() throws Exception {
        Path output = temporaryDirectory.resolve("data-loss-report");

        Invocation invocation = invoke(
                "run",
                "examples/data-loss-regression",
                "--output-dir",
                output.toString());

        assertEquals(1, invocation.exitCode());
        String report = Files.readString(output.resolve("report.json"));
        assertTrue(report.contains("QUERY_BEHAVIOR_CHANGED"));
        assertTrue(report.contains("SCHEMA_ROUND_TRIP_MISMATCH"));
    }

    private static Invocation invoke(String... arguments) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            exitCode = Main.run(arguments, out, err);
        }
        return new Invocation(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private static void copyExampleBundle(Path destination) throws Exception {
        Files.createDirectories(destination);
        Path source = Path.of("examples/add-user-email");
        for (String file : INPUT_FILES) {
            Files.copy(source.resolve(file), destination.resolve(file));
        }
    }

    private record Invocation(int exitCode, String standardOutput, String standardError) {}
}
