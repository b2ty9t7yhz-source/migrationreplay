# MigrationReplay

Behavioral Regression Testing for Database Migrations.

MigrationReplay executes the same deterministic, read-only query corpus against
three isolated SQLite states:

```text
baseline -> migration up -> migration down
```

It compares query outcomes, typed values, row multiplicity, schema objects,
row counts, integrity checks, indexes, and `EXPLAIN QUERY PLAN` evidence. It
produces deterministic JSON and Markdown reports suitable for local review and
CI artifacts.

## Why this project exists

A migration that executes successfully can still change application behavior,
lose or duplicate rows, introduce unexpected `NULL` values, remove an index, or
fail to restore the baseline state during rollback. MigrationReplay complements
migration frameworks; it does not generate migrations or manage production
schema history.

## V1 features

- One local SQLite migration pair per run
- Deterministic synthetic fixtures
- Isolated baseline and candidate database snapshots
- Engine-owned transactions for migration up and down
- Read-only parameterized query corpus
- Runtime-type-aware result canonicalization
- Ordered or unordered multiset comparison
- Expected outcome, non-null, and uniqueness assertions
- SQLite integrity and foreign-key checks
- Schema, index, and row-count comparison
- Full-table-scan warnings from `EXPLAIN QUERY PLAN`
- Rollback round-trip validation
- Deterministic `report.json` and `report.md`
- Unit and integration tests with GitHub Actions CI

## Requirements

- JDK 21
- No global Gradle installation; use the included Gradle Wrapper

## Quick start

Validate the example bundle without executing SQL:

```bash
./gradlew run --args="validate examples/add-user-email"
```

Run the complete replay:

```bash
./gradlew run --args="run examples/add-user-email --output-dir build/example-report"
```

Inspect:

```text
build/example-report/report.json
build/example-report/report.md
```

Run all tests:

```bash
./gradlew clean test
```

## Input bundle

Every bundle contains exactly these required files:

```text
baseline_schema.sql
fixtures.sql
migration_up.sql
migration_down.sql
queries.yaml
```

See [`examples/add-user-email`](examples/add-user-email) for a complete bundle
and [`docs/canonicalization.md`](docs/canonicalization.md) for comparison rules.

## Exit codes

- `0`: validation succeeded or replay passed
- `1`: replay completed and found one or more violations
- `2`: invalid input, unsupported SQL, or report-writing failure

## Safety boundary

MigrationReplay never accepts a production JDBC URL. It creates databases only
inside a tool-owned temporary directory and opens query states as read-only.
Input SQL must still be trusted: V1 restrictions reduce accidental external
effects but are not a security sandbox for malicious SQL. See
[`docs/safety-model.md`](docs/safety-model.md).

## Non-goals

- PostgreSQL, MySQL, or production database connections
- Migration generation or history management
- Multiple migration pairs per run
- Production query capture
- Arbitrary SQL sandboxing
- Performance benchmarking
- Proof that a migration is absolutely safe
- Web UI, Docker, cloud deployment, or distributed execution

## Documentation

- [`docs/architecture.md`](docs/architecture.md)
- [`docs/configuration.md`](docs/configuration.md)
- [`docs/canonicalization.md`](docs/canonicalization.md)
- [`docs/report-schema.md`](docs/report-schema.md)
- [`docs/safety-model.md`](docs/safety-model.md)
- [`docs/test-strategy.md`](docs/test-strategy.md)

## License

MIT. See [`LICENSE`](LICENSE).
