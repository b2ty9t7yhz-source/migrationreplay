package dev.migrationreplay.database;

import dev.migrationreplay.config.InputBundle;
import dev.migrationreplay.database.ScriptExecutor.Script;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

public final class DatabaseManager {
    private final ScriptExecutor scriptExecutor = new ScriptExecutor();

    public record RunDatabases(
            Path runDirectory, Path seedDatabase, Path baselineDatabase, Path candidateDatabase) {}

    public RunDatabases initialize(InputBundle bundle) throws ExecutionException {
        final Path runDirectory;
        try {
            runDirectory = Files.createTempDirectory("migrationreplay-");
        } catch (IOException exception) {
            throw new ExecutionException(
                    "TEMP_DIRECTORY_FAILED", "initialization", "Could not create the run directory.", exception);
        }

        RunDatabases databases = new RunDatabases(
                runDirectory,
                runDirectory.resolve("seed.db"),
                runDirectory.resolve("baseline.db"),
                runDirectory.resolve("candidate.db"));
        try (Connection connection = openWritable(databases.seedDatabase())) {
            scriptExecutor.executeTransaction(
                    connection,
                    "seed initialization",
                    List.of(
                            new Script("baseline_schema.sql", bundle.baselineSchema()),
                            new Script("fixtures.sql", bundle.fixtures())));
        } catch (SQLException exception) {
            cleanup(databases);
            throw new ExecutionException(
                    "DATABASE_OPEN_FAILED",
                    "seed initialization",
                    "Could not initialize seed.db: " + stableMessage(exception),
                    exception);
        } catch (ExecutionException exception) {
            cleanup(databases);
            throw exception;
        }

        try {
            Files.copy(databases.seedDatabase(), databases.baselineDatabase(), StandardCopyOption.COPY_ATTRIBUTES);
            Files.copy(databases.seedDatabase(), databases.candidateDatabase(), StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException exception) {
            cleanup(databases);
            throw new ExecutionException(
                    "DATABASE_SNAPSHOT_FAILED",
                    "initialization",
                    "Could not create isolated database snapshots.",
                    exception);
        }
        return databases;
    }

    public void applyMigration(Path database, String phase, String sql) throws ExecutionException {
        try (Connection connection = openWritable(database)) {
            scriptExecutor.executeTransaction(connection, phase, List.of(new Script(phase, sql)));
        } catch (SQLException exception) {
            throw new ExecutionException(
                    "DATABASE_OPEN_FAILED",
                    phase,
                    "Could not open candidate.db: " + stableMessage(exception),
                    exception);
        }
    }

    public Connection openReadOnly(Path database) throws SQLException {
        SQLiteConfig config = baseConfig();
        config.setReadOnly(true);
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath(), config.toProperties());
        configureConnection(connection, true);
        return connection;
    }

    public void cleanup(RunDatabases databases) {
        Path runDirectory = databases.runDirectory();
        if (runDirectory == null
                || !runDirectory.getFileName().toString().startsWith("migrationreplay-")) {
            return;
        }
        try (var paths = Files.walk(runDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary files are best-effort cleanup only.
                }
            });
        } catch (IOException ignored) {
            // Temporary files are best-effort cleanup only.
        }
    }

    private Connection openWritable(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + database.toAbsolutePath(), baseConfig().toProperties());
        configureConnection(connection, false);
        return connection;
    }

    private static SQLiteConfig baseConfig() {
        SQLiteConfig config = new SQLiteConfig(new Properties());
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        return config;
    }

    private static void configureConnection(Connection connection, boolean readOnly)
            throws SQLException {
        if (connection instanceof SQLiteConnection sqlite) {
            sqlite.getDatabase().enable_load_extension(false);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA trusted_schema = OFF");
            statement.execute("PRAGMA ignore_check_constraints = OFF");
            statement.execute("PRAGMA busy_timeout = 5000");
            if (readOnly) {
                statement.execute("PRAGMA query_only = ON");
            } else {
                statement.execute("PRAGMA journal_mode = DELETE");
            }
        }
    }

    private static String stableMessage(SQLException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
    }
}
