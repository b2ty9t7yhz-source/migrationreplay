# Report schema

`report.json` is the source of truth. `report.md` is rendered from the same
in-memory model.

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

SQLite documents `EXPLAIN QUERY PLAN` as debugging output whose format may
change. MigrationReplay therefore records the SQLite runtime version and treats
plan changes and full scans as warnings rather than proof of a performance
regression.
