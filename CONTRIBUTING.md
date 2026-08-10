# Contributing

1. Use JDK 21 and the checked-in Gradle Wrapper.
2. Keep all fixtures synthetic, minimal, and deterministic.
3. Add a focused test for every behavior change or failure case.
4. Preserve typed comparison, row multiplicity, and explicit ordering semantics.
5. Do not add production database connectivity or weaken the trusted-input
   warning.
6. Run the complete gate before opening a pull request:

   ```bash
   ./gradlew clean check installDist distZip --dependency-verification=strict \
     --warning-mode all --no-daemon
   ```

7. When changing dependencies, update both `gradle.lockfile` and
   `gradle/verification-metadata.xml`, then rerun the complete gate without
   metadata-writing flags to prove strict verification succeeds.
8. Use a concise Conventional Commit message and explain user-visible behavior
   in the pull request.

Code, tests, documentation, issue titles, and commit messages should be written
in English.
