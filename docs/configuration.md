# Input bundle reference

Every run reads five UTF-8 regular files from one directory. Symbolic links are
rejected, and each file is limited to 10 MiB.

## SQL files

- `baseline_schema.sql`: creates the baseline SQLite schema.
- `fixtures.sql`: inserts deterministic synthetic data.
- `migration_up.sql`: applies one forward migration.
- `migration_down.sql`: reverses that migration.

The schema and fixtures share one transaction. Up and down each have a separate
engine-owned transaction. Scripts may contain multiple statements but may not
contain their own transaction control.

## `queries.yaml`

The configuration is strict: unknown fields, duplicate keys, invalid types,
and unsupported enum values are errors.

```yaml
version: 1

queries:
  - id: users-by-status
    sql: |
      SELECT id, email
      FROM users
      WHERE status = :status
      ORDER BY id
    parameters:
      status:
        type: text
        value: active
    outcomes:
      baseline: success
      after_up: success
      after_down: success
    compare:
      baseline_to_up: preserve
      baseline_to_down: preserve
      row_order: ordered
    assertions:
      non_null: [id, email]
      unique_by: [id]
    plan:
      warn_on_full_scan: [users]

schema_assertions:
  - state: after_up
    index_exists: idx_users_status
```

### Query fields

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Unique lowercase identifier matching `[a-z][a-z0-9_-]*` |
| `sql` | yes | One read-only `SELECT` or `WITH` statement |
| `parameters` | no | Complete map of named parameters referenced by the SQL |
| `outcomes` | no | Expected `success` or `error` in each state; defaults to success |
| `compare` | no | Preserve or record behavioral differences and choose row ordering |
| `assertions` | no | Result columns that must be non-null or unique as a composite key |
| `plan` | no | Tables whose full scans should produce warnings |

Parameter types are `null`, `integer`, `real`, `text`, and `blob`. Blob values
use Base64 text. Named parameter definitions must exactly match the names used
in SQL; repeated uses of one name are allowed.

`row_order` defaults to `unordered`. Selecting `ordered` requires an explicit
`ORDER BY`. Comparison policies default to `preserve`.

### Schema assertions

V1 supports `index_exists` for `baseline`, `after_up`, or `after_down`. A missing
required index is a violation. Schema objects and table row counts are always
captured even without an explicit assertion.
