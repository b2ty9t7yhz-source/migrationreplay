# Release checklist

1. Confirm `build.gradle.kts`, `--version`, and `CHANGELOG.md` use the same
   semantic version.
2. Run the complete quality gate:

   ```bash
   ./gradlew clean check installDist distZip --dependency-verification=strict \
     --warning-mode all --no-daemon
   ```

3. Run the packaged passing example and verify exit code `0`.
4. Run the packaged data-loss example and verify exit code `1` with
   `QUERY_BEHAVIOR_CHANGED` and `SCHEMA_ROUND_TRIP_MISMATCH`.
5. Inspect the generated JSON and Markdown reports for temporary paths or
   nondeterministic fields.
6. Confirm `git diff --check` is clean and the intended commit is pushed.
7. Create an annotated `vMAJOR.MINOR.PATCH` tag and push it. The release workflow
   verifies the tag/version match, reruns all gates, and publishes the ZIP plus
   its SHA-256 checksum.
8. Confirm the GitHub release and Actions run completed successfully.
