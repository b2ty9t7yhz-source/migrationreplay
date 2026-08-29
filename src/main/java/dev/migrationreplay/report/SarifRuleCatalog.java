package dev.migrationreplay.report;

import java.util.List;

final class SarifRuleCatalog {
    enum LocationPolicy {
        BASELINE_SCHEMA,
        MIGRATION_DOWN,
        MIGRATION_UP,
        STATE_RELATED
    }

    record Rule(
            String id,
            String name,
            String description,
            String level,
            LocationPolicy locationPolicy) {}

    private static final List<Rule> RULES = List.of(
            error(
                    "AMBIGUOUS_QUERY_COLUMN",
                    "Ambiguous query column",
                    "A query returned duplicate column labels required by an assertion.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "ASSERTION_COLUMN_MISSING",
                    "Assertion column missing",
                    "A configured assertion references a column that is missing or ambiguous.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "DUPLICATE_QUERY_KEY",
                    "Duplicate query key",
                    "A query result violates its configured unique-by assertion.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "FOREIGN_KEY_VIOLATION",
                    "Foreign key violation",
                    "SQLite reported a foreign-key violation in a replay state.",
                    LocationPolicy.STATE_RELATED),
            warning(
                    "FULL_TABLE_SCAN",
                    "Full table scan",
                    "A configured query-plan assertion observed a full table scan.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "MIGRATION_DOWN_FAILED",
                    "Migration down failed",
                    "The down migration could not complete in its transaction.",
                    LocationPolicy.MIGRATION_DOWN),
            error(
                    "MIGRATION_UP_FAILED",
                    "Migration up failed",
                    "The up migration could not complete in its transaction.",
                    LocationPolicy.MIGRATION_UP),
            error(
                    "QUERY_BEHAVIOR_CHANGED",
                    "Query behavior changed",
                    "A preserve-mode query changed its observable behavior from baseline.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "QUERY_NOT_CAPTURED",
                    "Query not captured",
                    "A configured query has no observation for a replay state.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "QUERY_OUTCOME_MISMATCH",
                    "Query outcome mismatch",
                    "A query succeeded or failed differently from its configured expectation.",
                    LocationPolicy.STATE_RELATED),
            warning(
                    "QUERY_PLAN_CHANGED",
                    "Query plan changed",
                    "SQLite EXPLAIN QUERY PLAN details differ from baseline.",
                    LocationPolicy.STATE_RELATED),
            warning(
                    "RECORDED_QUERY_DIFFERENCE",
                    "Recorded query difference",
                    "A record-mode query changed from baseline without failing the replay.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "REQUIRED_INDEX_MISSING",
                    "Required index missing",
                    "A replay state does not contain a configured required index.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "SCHEMA_ROUND_TRIP_MISMATCH",
                    "Schema round-trip mismatch",
                    "The down migration did not restore baseline schema objects or row counts.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "SEED_INITIALIZATION_FAILED",
                    "Seed initialization failed",
                    "The baseline schema or fixtures could not initialize the replay database.",
                    LocationPolicy.BASELINE_SCHEMA),
            error(
                    "SQLITE_INTEGRITY_CHECK_FAILED",
                    "SQLite integrity check failed",
                    "SQLite reported an integrity-check failure in a replay state.",
                    LocationPolicy.STATE_RELATED),
            error(
                    "STATE_CAPTURE_FAILED",
                    "State capture failed",
                    "MigrationReplay could not capture a database state for comparison.",
                    LocationPolicy.BASELINE_SCHEMA),
            error(
                    "UNEXPECTED_NULL",
                    "Unexpected null",
                    "A query result violates its configured non-null assertion.",
                    LocationPolicy.STATE_RELATED));

    private SarifRuleCatalog() {}

    static List<Rule> rules() {
        return RULES;
    }

    static Rule find(String id) {
        return RULES.stream().filter(rule -> rule.id().equals(id)).findFirst().orElse(null);
    }

    private static Rule error(
            String id, String name, String description, LocationPolicy locationPolicy) {
        return new Rule(id, name, description, "error", locationPolicy);
    }

    private static Rule warning(
            String id, String name, String description, LocationPolicy locationPolicy) {
        return new Rule(id, name, description, "warning", locationPolicy);
    }
}
