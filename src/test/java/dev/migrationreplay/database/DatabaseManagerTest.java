package dev.migrationreplay.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DatabaseManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void queryConnectionsAreReadOnlyAtTheSQLiteEngineLayer() throws Exception {
        Path database = temporaryDirectory.resolve("readonly.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE records (id INTEGER PRIMARY KEY)");
        }

        try (var connection = new DatabaseManager().openReadOnly(database);
                Statement statement = connection.createStatement()) {
            SQLException exception = assertThrows(
                    SQLException.class,
                    () -> statement.execute("INSERT INTO records VALUES (1)"));
            assertEquals(8, exception.getErrorCode());
        }
    }

    @Test
    void schemaInspectionReportsForeignKeyViolations() throws Exception {
        Path database = temporaryDirectory.resolve("foreign-key.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            statement.execute(
                    "CREATE TABLE child (parent_id INTEGER REFERENCES parent(id))");
            statement.execute("INSERT INTO child VALUES (999)");
        }

        try (var connection = new DatabaseManager().openReadOnly(database)) {
            var snapshot = new SchemaInspector().inspect(connection);

            assertEquals(1, snapshot.foreignKeyViolations().size());
            assertEquals("child", snapshot.foreignKeyViolations().getFirst().table());
        }
    }
}
