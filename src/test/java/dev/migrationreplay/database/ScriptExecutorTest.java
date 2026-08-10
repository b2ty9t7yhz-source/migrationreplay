package dev.migrationreplay.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.migrationreplay.database.ScriptExecutor.Script;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ScriptExecutorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void executesEveryStatementAcrossScripts() throws Exception {
        try (Connection connection = connection("success.db")) {
            new ScriptExecutor().executeTransaction(
                    connection,
                    "seed",
                    List.of(
                            new Script(
                                    "schema.sql",
                                    "CREATE TABLE records (id INTEGER PRIMARY KEY, value TEXT);"),
                            new Script(
                                    "fixtures.sql",
                                    "INSERT INTO records VALUES (1, 'a');"
                                            + " INSERT INTO records VALUES (2, 'b');")));

            assertEquals(2, count(connection, "records"));
        }
    }

    @Test
    void rollsBackAllStatementsWhenALaterStatementFails() throws Exception {
        try (Connection connection = connection("failure.db")) {
            ExecutionException exception = assertThrows(
                    ExecutionException.class,
                    () -> new ScriptExecutor().executeTransaction(
                            connection,
                            "migration up",
                            List.of(new Script(
                                    "migration_up.sql",
                                    "CREATE TABLE records (id INTEGER PRIMARY KEY);"
                                            + " INSERT INTO records VALUES (1);"
                                            + " INSERT INTO records VALUES (1);"))));

            assertEquals("SQL_EXECUTION_FAILED", exception.code());
            assertFalse(tableExists(connection, "records"));
        }
    }

    private Connection connection(String fileName) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve(fileName).toAbsolutePath());
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
