# CI integration

MigrationReplay exits `0` for a passing replay, `1` when it detects a regression,
and `2` for configuration, safety-validation, usage, or report-writing failures.
Every completed replay also writes a SARIF 2.1.0 report that GitHub Code Scanning
can ingest.

## GitHub Actions

Install a verified MigrationReplay distribution before these steps, then run the
CLI from the repository root so SARIF artifact URIs remain repository-relative:

```yaml
permissions:
  actions: read
  contents: read
  security-events: write

steps:
  - uses: actions/checkout@v7

  - name: Replay database migration
    run: |
      migrationreplay run db/migration-check \
        --output-dir build/migrationreplay-report

  - name: Upload MigrationReplay SARIF
    if: ${{ always() && hashFiles('build/migrationreplay-report/report.sarif') != '' }}
    uses: github/codeql-action/upload-sarif@v4
    with:
      sarif_file: build/migrationreplay-report/report.sarif
      category: migrationreplay

  - name: Upload complete replay evidence
    if: ${{ always() && hashFiles('build/migrationreplay-report/report.json') != '' }}
    uses: actions/upload-artifact@v7
    with:
      name: migrationreplay-report
      path: build/migrationreplay-report/
      if-no-files-found: error
```

The `always()` conditions ensure evidence is uploaded even when the replay step
returns `1`. The job still fails because the replay step failed; the upload steps
do not hide or replace MigrationReplay's exit status.

GitHub interprets relative SARIF artifact URIs from the repository root. When a
bundle is outside the current working directory, MigrationReplay emits only the
bundle filename rather than leaking an absolute local path. For useful code
scanning navigation, keep the bundle in the repository and invoke the CLI from
the repository root.

## Other CI systems

Archive all three report files after every completed replay:

- `report.json`: complete, versioned machine-readable evidence
- `report.md`: human-readable review artifact
- `report.sarif`: standard findings for SARIF-compatible analysis systems

Treat exit code `1` as a detected migration regression and exit code `2` as an
invalid or incomplete analysis. Do not convert either code to success merely to
upload artifacts.

MigrationReplay's own CI also executes the packaged CLI against the checked-in
intentional data-loss example. It requires exit code `1`, five SARIF results,
and the expected behavior-change and round-trip rule IDs. This distinguishes an
expected product finding from an unexpected CLI or workflow failure.
