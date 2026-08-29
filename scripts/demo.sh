#!/usr/bin/env bash
set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(cd -- "${script_directory}/.." && pwd)"
cd "${repository_root}"

cli="build/install/migrationreplay/bin/migrationreplay"
pass_output="build/recruiter-demo/pass"
regression_output="build/recruiter-demo/data-loss"

printf 'Building the packaged MigrationReplay CLI...\n'
./gradlew -q installDist --dependency-verification=strict --no-daemon

printf '\n1/2 Replaying a migration that preserves behavior\n'
"${cli}" run examples/add-user-email --output-dir "${pass_output}"

pass_rule_count="$(grep -c '"id" : ' "${pass_output}/report.sarif")"
if [[ "${pass_rule_count}" -ne 18 ]]; then
    printf 'Expected 18 stable SARIF rule descriptors, found %s.\n' "${pass_rule_count}" >&2
    exit 2
fi

printf '\n2/2 Replaying a migration that silently deletes data\n'
set +e
"${cli}" run examples/data-loss-regression --output-dir "${regression_output}"
regression_status=$?
set -e

if [[ "${regression_status}" -ne 1 ]]; then
    printf 'Expected the data-loss replay to exit 1, received %s.\n' "${regression_status}" >&2
    exit 2
fi

regression_result_count="$(grep -c '"ruleId" : ' "${regression_output}/report.sarif")"
if [[ "${regression_result_count}" -ne 5 ]]; then
    printf 'Expected 5 SARIF results, found %s.\n' "${regression_result_count}" >&2
    exit 2
fi

printf '\nDetected violation codes:\n'
grep '"code" : ' "${regression_output}/report.json" \
    | sed -E 's/.*"code" : "([^"]+)".*/  - \1/' \
    | sort -u

printf '\nDemo verified:\n'
printf '  - passing replay exit code: 0\n'
printf '  - intentional regression exit code: %s\n' "${regression_status}"
printf '  - stable SARIF rule descriptors: %s\n' "${pass_rule_count}"
printf '  - intentional regression SARIF results: %s\n' "${regression_result_count}"
printf '  - evidence directory: %s/build/recruiter-demo\n' "${repository_root}"
