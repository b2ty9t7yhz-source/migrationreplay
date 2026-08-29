# MigrationReplay

[![CI](https://github.com/b2ty9t7yhz-source/migrationreplay/actions/workflows/ci.yml/badge.svg)](https://github.com/b2ty9t7yhz-source/migrationreplay/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-1.1.0-blue.svg)](CHANGELOG.md)
[![Java](https://img.shields.io/badge/Java-21-ED8B00.svg)](https://openjdk.org/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

Catch SQLite migrations that execute successfully but silently change
application behavior.

MigrationReplay executes the same deterministic, read-only query corpus against
baseline, migrated, and rolled-back database states. It compares behavior and
integrity—not just whether migration SQL returned successfully.

```mermaid
flowchart LR
    A["Baseline schema + fixtures"] --> B["Baseline replay"]
    A --> C["Isolated candidate copy"]
    C --> D["Migration up transaction"]
    D --> E["After-up replay + diff"]
    E --> F["Migration down transaction"]
    F --> G["After-down replay + round-trip diff"]
    B --> H["Deterministic JSON + Markdown + SARIF reports"]
    E --> H
    G --> H
```

## Why it exists

A migration can execute successfully while still losing rows, changing query
results, introducing unexpected `NULL` values, removing an index, or failing to
restore behavior during rollback. MigrationReplay complements migration
frameworks by testing these observable effects. It does not generate or apply
production migrations.

## What it checks

| Signal | Evidence captured |
|---|---|
| Query behavior | Success/error outcome, columns, typed rows, row multiplicity |
| Data integrity | Row counts, non-null assertions, unique query keys |
| Database integrity | SQLite integrity check and foreign-key violations |
| Schema behavior | Tables, indexes, schema SQL, rollback round trip |
| Query plans | `EXPLAIN QUERY PLAN`, plan changes, configured full-scan warnings |
| Reproducibility | SHA-256 input fingerprints and deterministic reports |

Result comparison preserves SQLite runtime types: integer `1`, real `1.0`, and
text `"1"` are distinct values. Unordered comparisons retain duplicate rows as
a multiset. Ordered comparisons require an explicit top-level `ORDER BY`.

## 60–90 second demo

Requirements: JDK 21 and a POSIX shell. The script builds the packaged CLI,
runs a behavior-preserving migration, then proves that an intentionally
data-losing migration exits `1` while still producing complete evidence.

```bash
./scripts/demo.sh
```

The checked-in scenario deletes an inactive user during migration up. Its down
migration restores the schema but cannot restore the deleted row. The demo
verifies, from generated files rather than hard-coded output, that the passing
run exposes all 18 stable SARIF rule descriptors and that the regression emits
5 SARIF results. Inspect the evidence under `build/recruiter-demo/`.

## Quick start

Requirements: JDK 21. The checked-in Gradle Wrapper supplies the build tool.

```bash
./gradlew clean check installDist
./build/install/migrationreplay/bin/migrationreplay --version
```

Run the passing migration example:

```bash
./build/install/migrationreplay/bin/migrationreplay \
  run examples/add-user-email \
  --output-dir build/example-report
```

Expected CLI summary:

```text
status=PASS
json=.../build/example-report/report.json
markdown=.../build/example-report/report.md
sarif=.../build/example-report/report.sarif
```

## See a regression get caught

`examples/data-loss-regression` deletes an inactive account during migration up.
Migration down restores the schema but cannot restore the lost row.

```bash
./build/install/migrationreplay/bin/migrationreplay \
  run examples/data-loss-regression \
  --output-dir build/data-loss-report
```

The command exits with code `1` and reports both behavioral and round-trip
violations:

```text
status=FAIL
QUERY_BEHAVIOR_CHANGED
SCHEMA_ROUND_TRIP_MISMATCH
```

The failure is intentional and demonstrates that a syntactically successful
rollback is not necessarily a behavioral rollback.

## Input bundle

Each run reads five UTF-8 files:

```text
baseline_schema.sql
fixtures.sql
migration_up.sql
migration_down.sql
queries.yaml
```

`queries.yaml` defines named parameters, expected outcomes, comparison policy,
row-order semantics, result assertions, plan warnings, and index assertions.
See the [configuration reference](docs/configuration.md) and the
[passing example](examples/add-user-email).

## Reports and exit codes

Every completed replay writes `report.json`, `report.md`, and `report.sarif`
using atomic replacement for each file. JSON is the complete evidence record,
Markdown is optimized for review, and SARIF 2.1.0 maps violations and warnings
to bundle files for CI code scanning. A fixed catalog keeps rule indices,
descriptions, default severities, and location policies stable even when a run
has no findings. Reports contain no timestamps, durations, temporary paths, or
random identifiers. Repeated runs with identical inputs, runtime versions, and
bundle paths are byte-identical.

See the [CI integration guide](docs/ci-integration.md) for uploading SARIF to
GitHub Code Scanning while preserving MigrationReplay's exit status.

- `0`: validation succeeded or replay passed
- `1`: replay completed and found one or more violations
- `2`: configuration, safety validation, CLI usage, or report-writing failure

## Safety boundary

MigrationReplay accepts no JDBC URL or database path. It creates databases only
inside a tool-owned temporary directory and opens replay connections as
read-only with SQLite `query_only` enabled. It rejects external-database,
extension-loading, transaction-control, virtual-table, and other unsupported SQL
before execution.

Input SQL must still come from a trusted repository. The lexical policy is
deliberately conservative; it is not a sandbox for hostile SQL. See the full
[safety model](docs/safety-model.md).

## Architecture and technical challenges

The execution engine treats a migration as a three-state experiment:

1. Build a baseline database from deterministic schema and fixtures.
2. Apply migration up in an engine-owned transaction and replay typed queries.
3. Apply migration down, replay again, and compare behavior and schema with the
   original baseline.

The main engineering challenges are preserving SQLite runtime types instead of
comparing display strings, retaining duplicate rows during unordered comparison,
isolating every state from partial transactions, and producing byte-stable
evidence suitable for both human review and CI. See the component and transaction
boundaries in [Architecture](docs/architecture.md).

## Quality gates

- Java 21 compilation with all compiler warnings treated as errors
- 75 automated unit, component, integration, report-contract, and CLI tests
- Enforced JaCoCo minimums: 84% line and 65% branch coverage
- Latest clean local gate: 88.03% line and 69.92% branch coverage
- Transaction rollback and deterministic-report regression tests
- CI-built installation ZIP, passing and intentional-failure packaged-CLI smoke
  tests, and JSON/Markdown/SARIF artifacts
- Gradle Wrapper and dependency-artifact SHA-256 verification
- Dependency locking and automated dependency update proposals

Run the same gate used by CI:

```bash
./gradlew clean check installDist distZip --dependency-verification=strict --no-daemon
```

## Design tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Fixed five-file bundle | Small, reviewable, reproducible inputs | No discovery of framework-specific migration layouts |
| Synthetic fixtures | Deterministic and safe to publish | Does not reproduce every production data distribution |
| Typed canonical rows | Detects changes hidden by string rendering | Adds explicit serialization and comparison rules |
| Conservative SQL policy | Prevents external database access and engine-owned transaction conflicts | Rejects some valid but unsupported SQLite features |
| SQLite-only V1 | Keeps transaction and type semantics precise | Results do not generalize automatically to other databases |
| Fixed SARIF catalog | Stable CI rule metadata on PASS and FAIL runs | New finding codes must be registered deliberately |

## Limitations and non-goals

V1 supports SQLite, deterministic synthetic fixtures, local temporary database
copies, one migration pair, and a read-only query corpus. It intentionally does
not support PostgreSQL/MySQL, production connections, migration generation,
online schema changes, query capture, a web UI, hostile-SQL sandboxing, or
performance benchmarking. Query replay is capped at 100,000 rows per query and
uses a five-second JDBC query timeout. It does not claim to prove that a
migration is absolutely safe or predict production performance.

## Documentation

- [Architecture](docs/architecture.md)
- [Configuration reference](docs/configuration.md)
- [Canonicalization rules](docs/canonicalization.md)
- [Report schema](docs/report-schema.md)
- [CI integration](docs/ci-integration.md)
- [Safety model](docs/safety-model.md)
- [Test strategy](docs/test-strategy.md)
- [Release checklist](docs/release-checklist.md)
- [Changelog](CHANGELOG.md)
- [Security policy](SECURITY.md)

## License

MIT. See [LICENSE](LICENSE).
