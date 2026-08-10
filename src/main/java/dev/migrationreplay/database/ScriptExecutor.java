package dev.migrationreplay.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

public final class ScriptExecutor {
    public record Script(String label, String sql) {}

    public void executeTransaction(Connection connection, String phase, List<Script> scripts)
            throws ExecutionException {
        try (Statement transaction = connection.createStatement()) {
            transaction.execute("BEGIN IMMEDIATE");
            try {
                for (Script script : scripts) {
                    executeScript(connection, script);
                }
                transaction.execute("COMMIT");
            } catch (SQLException exception) {
                rollback(transaction, exception);
                throw new ExecutionException(
                        "SQL_EXECUTION_FAILED",
                        phase,
                        phase + " failed: " + stableMessage(exception),
                        exception);
            }
        } catch (SQLException exception) {
            throw new ExecutionException(
                    "TRANSACTION_FAILED",
                    phase,
                    phase + " transaction failed: " + stableMessage(exception),
                    exception);
        }
    }

    private static void executeScript(Connection connection, Script script) throws SQLException {
        List<String> statements = SqlStatements.split(script.sql());
        for (int index = 0; index < statements.size(); index++) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statements.get(index));
            } catch (SQLException exception) {
                throw new SQLException(
                        script.label()
                                + " statement "
                                + (index + 1)
                                + " failed: "
                                + stableMessage(exception),
                        exception.getSQLState(),
                        exception.getErrorCode(),
                        exception);
            }
        }
    }

    private static void rollback(Statement transaction, SQLException original) {
        try {
            transaction.execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static String stableMessage(SQLException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
    }
}
