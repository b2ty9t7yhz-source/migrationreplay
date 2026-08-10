# Changelog

All notable changes to MigrationReplay are documented here. The project follows
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-08-10

### Added

- Isolated baseline, after-up, and after-down SQLite replay states
- Engine-owned transactional execution for schema, fixtures, migration up, and migration down
- Strict five-file input loading with SHA-256 fingerprints
- Typed query-result canonicalization and ordered or unordered multiset comparison
- Expected-outcome, non-null, uniqueness, and index assertions
- Schema, row-count, integrity, foreign-key, and query-plan evidence
- Deterministic JSON and Markdown reports with stable exit codes
- Conservative SQL safety policy and read-only query connections
- Passing and intentional data-loss regression examples
- Java 21 Gradle distribution, coverage verification, and GitHub Actions CI

[1.0.0]: https://github.com/b2ty9t7yhz-source/migrationreplay/releases/tag/v1.0.0
