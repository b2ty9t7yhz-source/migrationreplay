# Test strategy

MigrationReplay uses unit, component, integration, and CLI tests. Every fixture
is synthetic. The suite does not connect to a production database and does not
use timing-sensitive assertions.

## Required failure coverage

| Scenario | Expected invariant or evidence |
|---|---|
| A later statement in a migration fails | The entire migration transaction is rolled back |
| Migration up fails | Only the baseline state is captured; a structured violation is reported |
| Migration down fails | Baseline and after-up evidence remain available |
| Preserved query results change | `QUERY_BEHAVIOR_CHANGED` is reported |
| Rollback leaves a schema object behind | `SCHEMA_ROUND_TRIP_MISMATCH` is reported |
| Rollback does not restore data behavior | Baseline-to-down query comparison fails |
| A required index is absent | `REQUIRED_INDEX_MISSING` is reported |
| A declared non-null result contains `NULL` | `UNEXPECTED_NULL` is reported |
| A declared query key is duplicated | `DUPLICATE_QUERY_KEY` is reported |
| A query changes success/error state unexpectedly | `QUERY_OUTCOME_MISMATCH` is reported |
| A script contains external or transaction-control SQL | Validation rejects it before execution |
| A query is writable, nondeterministic, or multi-statement | Validation rejects it before replay |
| An ordered comparison has no explicit `ORDER BY` | Configuration validation rejects it |
| A required input is missing or symlinked | Bundle loading rejects it |
| An input is malformed UTF-8 or YAML | Bundle loading rejects it with a structured code |
| Runtime type changes while display text stays equal | Typed canonical comparison detects it |
| Row order changes under ordered comparison | Ordered comparison detects it |
| Row multiplicity changes under unordered comparison | Multiset comparison detects it |

## Determinism check

An integration test runs the same input bundle twice in the same runtime,
writes both report formats, and requires byte-identical JSON and Markdown.
Reports intentionally exclude timestamps, durations, random identifiers, and
temporary database paths.

## Local verification

```bash
./gradlew clean test --no-daemon
./gradlew run --args="run examples/add-user-email --output-dir build/example-report" --no-daemon
```
