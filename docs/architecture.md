# Architecture

## Execution flow

```text
fixed five-file bundle
        |
        v
strict validation + SHA-256 fingerprints
        |
        v
seed.db = baseline schema + deterministic fixtures
        |
        +----------------------+
        |                      |
        v                      v
baseline.db                candidate.db
read-only replay           migration up transaction
                           -> read-only replay
                           -> migration down transaction
                           -> read-only replay
        |                      |
        +----------+-----------+
                   v
       behavioral + schema comparisons
                   |
                   v
       atomic JSON and Markdown reports
```

## Components

- `config`: fixed-file loading, strict YAML validation, and input fingerprints
- `sql`: lexical SQL restrictions and named-parameter compilation
- `database`: SQLite connections, engine-owned transactions, snapshots, and
  schema inspection
- `replay`: typed query execution, canonical rows, and query-plan capture
- `engine`: state orchestration, assertions, and comparisons
- `report`: deterministic report models, JSON serialization, and Markdown
  rendering
- `Main`: dependency-free command-line interface and exit codes

## Transaction boundaries

The baseline schema and fixtures are installed in one `BEGIN IMMEDIATE`
transaction. Migration up and migration down each run in their own
engine-controlled transaction. Input scripts cannot contain transaction-control
statements. If any statement fails, MigrationReplay executes `ROLLBACK` and does
not capture a partially migrated state.

## Isolation

`seed.db` is closed before it is copied into separate baseline and candidate
files. Query replay uses read-only SQLite connections with `query_only` enabled.
The candidate connection is closed after every migration phase before a new
read-only observation connection is opened.
