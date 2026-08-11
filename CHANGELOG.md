# Changelog

All notable changes to MigrationReplay are documented here. The project follows
[Semantic Versioning](https://semver.org/).

## [1.0.1] - 2026-08-11

### Changed

- Updated Gradle, SQLite JDBC, Jackson, and JUnit maintenance versions
- Removed hard-coded distribution versions from the CI workflow

### Security

- Added SHA-256 verification metadata for resolved Gradle dependencies

### Fixed

- Removed a duplicated heading from the contribution guide

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

[1.0.1]: https://github.com/b2ty9t7yhz-source/migrationreplay/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/b2ty9t7yhz-source/migrationreplay/releases/tag/v1.0.0
