# Report schema

`report.json` is the source of truth. `report.md` and `report.sarif` are rendered
from the same in-memory model.

## Top-level fields

- `reportVersion`: currently `1`
- `status`: `PASS` or `FAIL`
- `inputs`: SHA-256 fingerprint for each required file
- `runtime`: Java and SQLite versions
- `states`: available `baseline`, `after_up`, and `after_down` snapshots
- `comparisons`: baseline-to-up and baseline-to-down comparisons
- `violations`: deterministic, sorted failure records
- `warnings`: deterministic, sorted advisory records
- `summary`: configured query, captured state, violation, and warning counts

Each state contains normalized schema objects, row counts, integrity results,
foreign-key violations, index names, typed query rows, normalized errors, and
raw `EXPLAIN QUERY PLAN` nodes.

Reports intentionally omit timestamps, durations, random IDs, and temporary
paths. Given identical inputs and runtime versions, repeated successful runs
produce byte-identical reports.

## SARIF 2.1.0

`report.sarif` converts every violation to an `error` result and every warning
to a `warning` result. Every run includes the complete fixed catalog of 18
MigrationReplay rules, including a passing run with no results. Rule IDs,
indices, descriptions, default severities, and location policies therefore do
not depend on which findings happen to occur. Unknown finding codes fail report
generation rather than silently changing the SARIF contract.

Result properties retain the replay state and query ID, and SHA-256 partial
fingerprints keep findings stable across CI runs. Rule IDs include stable codes
such as `QUERY_BEHAVIOR_CHANGED` and `SCHEMA_ROUND_TRIP_MISMATCH`.

Findings use repository-relative bundle locations when the CLI runs from the
repository root:

- `after_up` and migration-up failures map to `migration_up.sql`
- `after_down` and migration-down failures map to `migration_down.sql`
- baseline query findings map to `queries.yaml`
- findings without a more specific location map to `baseline_schema.sql`

For bundles outside the working directory, SARIF uses only the input filename;
it never embeds an absolute local path. JSON and Markdown determinism depends
only on inputs and runtime versions. SARIF determinism additionally assumes the
same repository-relative bundle path.

SQLite documents `EXPLAIN QUERY PLAN` as debugging output whose format may
change. MigrationReplay therefore records the SQLite runtime version and treats
plan changes and full scans as warnings rather than proof of a performance
regression.
