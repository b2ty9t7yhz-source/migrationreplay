package dev.migrationreplay.engine;

import dev.migrationreplay.config.ConfigModels.QueriesConfig;
import dev.migrationreplay.config.ConfigModels.RunState;
import dev.migrationreplay.config.InputBundle;
import dev.migrationreplay.database.DatabaseManager;
import dev.migrationreplay.database.DatabaseManager.RunDatabases;
import dev.migrationreplay.database.ExecutionException;
import dev.migrationreplay.database.SchemaInspector;
import dev.migrationreplay.replay.QueryRunner;
import dev.migrationreplay.replay.ReplayModels.StateSnapshot;
import dev.migrationreplay.report.ReportModels.ComparisonReport;
import dev.migrationreplay.report.ReportModels.RunReport;
import dev.migrationreplay.report.ReportModels.RuntimeInfo;
import dev.migrationreplay.report.ReportModels.Summary;
import dev.migrationreplay.report.ReportModels.Violation;
import dev.migrationreplay.report.ReportModels.Warning;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MigrationReplayEngine {
    private static final Comparator<Violation> VIOLATION_ORDER = Comparator
            .comparing(Violation::code)
            .thenComparing(Violation::state)
            .thenComparing(Violation::queryId)
            .thenComparing(Violation::message);
    private static final Comparator<Warning> WARNING_ORDER = Comparator
            .comparing(Warning::code)
            .thenComparing(Warning::state)
            .thenComparing(Warning::queryId)
            .thenComparing(Warning::message);

    private final DatabaseManager databases = new DatabaseManager();
    private final SchemaInspector schemaInspector = new SchemaInspector();
    private final QueryRunner queryRunner = new QueryRunner();
    private final ComparisonEngine comparisonEngine = new ComparisonEngine();

    public RunReport run(InputBundle bundle) {
        Map<String, StateSnapshot> states = new LinkedHashMap<>();
        Map<String, ComparisonReport> comparisons = new LinkedHashMap<>();
        List<Violation> violations = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        String sqliteVersion = "unavailable";
        RunDatabases runDatabases = null;

        try {
            runDatabases = databases.initialize(bundle);
            StateSnapshot baseline = capture(
                    runDatabases.baselineDatabase(), RunState.BASELINE, bundle.config());
            states.put(RunState.BASELINE.key(), baseline);
            try (Connection connection = databases.openReadOnly(runDatabases.baselineDatabase())) {
                sqliteVersion = schemaInspector.sqliteVersion(connection);
            }
            comparisonEngine.validateState(
                    RunState.BASELINE, baseline, bundle.config(), violations, warnings);

            try {
                databases.applyMigration(
                        runDatabases.candidateDatabase(), "migration up", bundle.migrationUp());
            } catch (ExecutionException exception) {
                violations.add(executionViolation("MIGRATION_UP_FAILED", exception));
                return finish(bundle, sqliteVersion, states, comparisons, violations, warnings);
            }

            StateSnapshot afterUp = capture(
                    runDatabases.candidateDatabase(), RunState.AFTER_UP, bundle.config());
            states.put(RunState.AFTER_UP.key(), afterUp);
            comparisonEngine.validateState(
                    RunState.AFTER_UP, afterUp, bundle.config(), violations, warnings);
            comparisons.put(
                    "baseline_to_after_up",
                    comparisonEngine.compare(
                            RunState.AFTER_UP,
                            baseline,
                            afterUp,
                            bundle.config(),
                            violations,
                            warnings));

            try {
                databases.applyMigration(
                        runDatabases.candidateDatabase(), "migration down", bundle.migrationDown());
            } catch (ExecutionException exception) {
                violations.add(executionViolation("MIGRATION_DOWN_FAILED", exception));
                return finish(bundle, sqliteVersion, states, comparisons, violations, warnings);
            }

            StateSnapshot afterDown = capture(
                    runDatabases.candidateDatabase(), RunState.AFTER_DOWN, bundle.config());
            states.put(RunState.AFTER_DOWN.key(), afterDown);
            comparisonEngine.validateState(
                    RunState.AFTER_DOWN, afterDown, bundle.config(), violations, warnings);
            comparisons.put(
                    "baseline_to_after_down",
                    comparisonEngine.compare(
                            RunState.AFTER_DOWN,
                            baseline,
                            afterDown,
                            bundle.config(),
                            violations,
                            warnings));
        } catch (ExecutionException exception) {
            violations.add(executionViolation("SEED_INITIALIZATION_FAILED", exception));
        } catch (SQLException exception) {
            violations.add(new Violation(
                    "STATE_CAPTURE_FAILED",
                    "",
                    "",
                    "State capture failed: " + stableMessage(exception)));
        } finally {
            if (runDatabases != null) {
                databases.cleanup(runDatabases);
            }
        }
        return finish(bundle, sqliteVersion, states, comparisons, violations, warnings);
    }

    private StateSnapshot capture(Path database, RunState state, QueriesConfig config)
            throws SQLException {
        try (Connection connection = databases.openReadOnly(database)) {
            return new StateSnapshot(
                    state.key(),
                    schemaInspector.inspect(connection),
                    queryRunner.runAll(connection, config.queries()));
        }
    }

    private static RunReport finish(
            InputBundle bundle,
            String sqliteVersion,
            Map<String, StateSnapshot> states,
            Map<String, ComparisonReport> comparisons,
            List<Violation> violations,
            List<Warning> warnings) {
        List<Violation> sortedViolations = violations.stream().sorted(VIOLATION_ORDER).toList();
        List<Warning> sortedWarnings = warnings.stream().sorted(WARNING_ORDER).toList();
        String status = sortedViolations.isEmpty() ? "PASS" : "FAIL";
        return new RunReport(
                "1",
                status,
                bundle.inputHashes(),
                new RuntimeInfo(System.getProperty("java.version"), sqliteVersion),
                states,
                comparisons,
                sortedViolations,
                sortedWarnings,
                new Summary(
                        bundle.config().queries().size(),
                        states.size(),
                        sortedViolations.size(),
                        sortedWarnings.size()));
    }

    private static Violation executionViolation(String code, ExecutionException exception) {
        return new Violation(code, exception.phase(), "", exception.getMessage());
    }

    private static String stableMessage(SQLException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").trim();
    }
}
