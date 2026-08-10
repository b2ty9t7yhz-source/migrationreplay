package dev.migrationreplay.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TransactionRollbackTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rollsBackSchemaAndDataChangesAfterStatementFailure() throws SQLException {
        Path databasePath = temporaryDirectory.resolve("transaction.db");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath)) {
            connection.setAutoCommit(false);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE records ("
                                + "id INTEGER PRIMARY KEY, "
                                + "value TEXT NOT NULL"
                                + ")");
                statement.executeUpdate(
                        "INSERT INTO records (id, value) VALUES (1, 'baseline')");
            }
            connection.commit();

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE records ADD COLUMN note TEXT");
                statement.executeUpdate(
                        "UPDATE records SET value = 'changed', note = 'temporary' WHERE id = 1");

                assertThrows(
                        SQLException.class,
                        () -> statement.executeUpdate(
                                "INSERT INTO records (id, value) VALUES (1, 'duplicate')"));
            }
            connection.rollback();

            Set<String> columns = columnNames(connection, "records");
            assertEquals(Set.of("id", "value"), columns);
            assertFalse(columns.contains("note"));

            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT id, value FROM records ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt("id"));
                assertEquals("baseline", rows.getString("value"));
                assertFalse(rows.next());
            }
        }
    }

    private static Set<String> columnNames(Connection connection, String tableName)
            throws SQLException {
        Set<String> names = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet columns = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (columns.next()) {
                names.add(columns.getString("name"));
            }
        }
        return names;
    }
}
