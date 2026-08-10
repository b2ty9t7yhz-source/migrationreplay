# Safety model

MigrationReplay is a local testing tool for trusted project files. It is not an
SQL sandbox.

## Enforced boundaries

- No user-supplied JDBC URL or production database path
- Databases created only in a tool-owned temporary directory
- Fixed input filenames and regular, non-symlink input files
- 10 MiB limit per input file
- Strict UTF-8 and strict YAML fields
- Query corpus restricted to one `SELECT` or `WITH` statement
- Query connections opened read-only with `PRAGMA query_only = ON`
- Extension loading disabled on every SQLite connection
- User scripts reject `ATTACH`, `DETACH`, `VACUUM`, `PRAGMA`, transaction
  control, triggers, virtual tables, and extension loading
- Common nondeterministic query functions and current-time keywords rejected
- Five-second query timeout and 100,000-row result limit

## Important limitations

Lexical validation is intentionally conservative and may reject safe SQL. It is
not a complete SQL parser and cannot make hostile input safe. Only execute
bundles from a trusted repository. Resource exhaustion and unknown SQLite
extensions are not treated as solved security problems.

V1 also rejects triggers and virtual tables so that multi-statement execution
and external module behavior remain explainable.
