# Query-result canonicalization

MigrationReplay compares SQLite runtime values, not formatted console output.

| SQLite storage class | Canonical representation |
|---|---|
| `NULL` | `{type: "null", value: null}` |
| `INTEGER` | signed decimal text |
| `REAL` | exact Java hexadecimal floating-point text |
| `TEXT` | exact Unicode text without trimming or case conversion |
| `BLOB` | Base64 text |

`INTEGER 1` and `TEXT "1"` are different values. Duplicate rows are retained.

## Row ordering

- `row_order: unordered` sorts canonical row keys and compares a multiset. It
  does not assume SQLite returns stable order without `ORDER BY`.
- `row_order: ordered` compares rows position by position and is rejected unless
  the query contains an explicit `ORDER BY`.

MigrationReplay does not attempt to prove that an `ORDER BY` is a total order.
The query author remains responsible for adding a deterministic tie breaker.

## Errors

Error comparison uses the SQLite category, numeric error code, and SQL state.
The human-readable message is included for diagnosis but excluded from
behavioral equality because driver messages can change between versions.

## Comparison policies

- `preserve`: a behavior difference is a violation.
- `record_only`: the difference is retained as a warning.

V1 intentionally omits approximate floating-point tolerances and state-specific
query text.
