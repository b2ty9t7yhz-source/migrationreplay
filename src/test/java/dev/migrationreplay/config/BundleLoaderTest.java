package dev.migrationreplay.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BundleLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsTheShippedExampleAndHashesEveryInput() throws Exception {
        InputBundle bundle = new BundleLoader().load(Path.of("examples/add-user-email"));

        assertEquals(5, bundle.inputHashes().size());
        assertEquals(2, bundle.config().queries().size());
        assertTrue(bundle.inputHashes().values().stream()
                .allMatch(hash -> hash.matches("[0-9a-f]{64}")));
    }

    @Test
    void rejectsMissingRequiredFiles() throws Exception {
        writeMinimalBundle(temporaryDirectory);
        Files.delete(temporaryDirectory.resolve("migration_down.sql"));

        assertCode("MISSING_INPUT_FILE", temporaryDirectory);
    }

    @Test
    void rejectsSymlinkedRequiredFiles() throws Exception {
        writeMinimalBundle(temporaryDirectory);
        Path target = temporaryDirectory.resolve("actual.sql");
        Files.writeString(target, "SELECT 1;", StandardCharsets.UTF_8);
        Files.delete(temporaryDirectory.resolve("migration_up.sql"));
        Files.createSymbolicLink(temporaryDirectory.resolve("migration_up.sql"), target);

        assertCode("MISSING_INPUT_FILE", temporaryDirectory);
    }

    @Test
    void rejectsMalformedUtf8() throws Exception {
        writeMinimalBundle(temporaryDirectory);
        Files.write(temporaryDirectory.resolve("fixtures.sql"), new byte[] {(byte) 0xC3, 0x28});

        assertCode("INVALID_UTF8", temporaryDirectory);
    }

    @Test
    void rejectsUnsafeScriptBeforeDatabaseExecution() throws Exception {
        writeMinimalBundle(temporaryDirectory);
        Files.writeString(
                temporaryDirectory.resolve("migration_up.sql"),
                "ATTACH DATABASE 'outside.db' AS outside;",
                StandardCharsets.UTF_8);

        assertCode("UNSAFE_SQL_SCRIPT", temporaryDirectory);
        assertFalse(Files.exists(temporaryDirectory.resolve("outside.db")));
    }

    private static void writeMinimalBundle(Path directory) throws Exception {
        Files.writeString(
                directory.resolve("baseline_schema.sql"),
                "CREATE TABLE sample (id INTEGER PRIMARY KEY);",
                StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("fixtures.sql"),
                "INSERT INTO sample VALUES (1);",
                StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("migration_up.sql"), "SELECT 1;", StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("migration_down.sql"), "SELECT 1;", StandardCharsets.UTF_8);
        Files.writeString(
                directory.resolve("queries.yaml"),
                "version: 1\nqueries:\n  - id: sample\n    sql: SELECT id FROM sample\n",
                StandardCharsets.UTF_8);
    }

    private static void assertCode(String expected, Path directory) {
        ConfigurationException exception = assertThrows(
                ConfigurationException.class, () -> new BundleLoader().load(directory));
        assertEquals(expected, exception.code());
    }
}
