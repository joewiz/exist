#!/bin/bash
# litmus-check.sh — Compare litmus results against a known-failure baseline.
#
# Usage: litmus-check.sh <litmus-output-file> <baseline-file>
#
# Exit codes:
#   0  — all results match baseline (no regressions)
#   1  — regression detected (a previously-passing test now fails)

set -euo pipefail

LITMUS_OUTPUT="${1:?Usage: litmus-check.sh <litmus-output> <baseline>}"
BASELINE="${2:?Usage: litmus-check.sh <litmus-output> <baseline>}"

# Parse actual failures from litmus output.
# litmus lines look like: "17. cond_put_corrupt_token FAIL (...)"
ACTUAL_FAILS=$(grep 'FAIL' "$LITMUS_OUTPUT" \
    | sed -E 's/^.*[0-9]+\. ([a-z_]+)\.* *FAIL.*/\1/' \
    | grep -v '^<' \
    | sort -u)

# Parse expected failures from baseline (skip comments and blank lines)
EXPECTED_FAILS=$(grep -v '^\s*#' "$BASELINE" | grep -v '^\s*$' | sort -u)

# Find regressions: tests that FAIL now but are NOT in the baseline
REGRESSIONS=""
for test in $ACTUAL_FAILS; do
    if ! echo "$EXPECTED_FAILS" | grep -qx "$test"; then
        REGRESSIONS="${REGRESSIONS}  - ${test}"$'\n'
    fi
done

# Find improvements: tests in the baseline that no longer FAIL
IMPROVEMENTS=""
for test in $EXPECTED_FAILS; do
    if ! echo "$ACTUAL_FAILS" | grep -qx "$test"; then
        IMPROVEMENTS="${IMPROVEMENTS}  - ${test}"$'\n'
    fi
done

# Count from summary lines
TOTAL_PASS=$(grep 'passed' "$LITMUS_OUTPUT" | sed -E 's/.*of [0-9]+ tests run: ([0-9]+) passed.*/\1/' | paste -sd+ - | bc 2>/dev/null || echo "?")
TOTAL_FAIL=$(grep 'failed' "$LITMUS_OUTPUT" | sed -E 's/.*([0-9]+) failed.*/\1/' | paste -sd+ - | bc 2>/dev/null || echo "?")

echo "=== Litmus WebDAV Compliance ==="
echo "Passed: ${TOTAL_PASS}"
echo "Failed: ${TOTAL_FAIL}"
echo "Expected failures: $(echo "$EXPECTED_FAILS" | grep -c . || echo 0)"
echo ""

if [ -n "$REGRESSIONS" ]; then
    echo "::error::REGRESSION DETECTED — previously-passing tests now fail:"
    echo "$REGRESSIONS"
    echo "Fix these regressions or update the baseline if the failure is expected."
    exit 1
fi

if [ -n "$IMPROVEMENTS" ]; then
    echo "::warning::Tests improved — previously-failing tests now pass:"
    echo "$IMPROVEMENTS"
    echo "Update extensions/webdav/src/test/resources/litmus-baseline.txt to remove these entries."
fi

echo "No regressions detected. All results match baseline."
exit 0
