#!/bin/bash
#
# Run eXist-db XQuery benchmarks against a running instance.
# Usage: ./run-benchmarks.sh [host:port] [output-file]
#
set -euo pipefail

HOST="${1:-localhost:8080}"
OUTPUT="${2:-benchmark-results.xml}"
BASE_URL="http://${HOST}/exist/rest"

echo "=== eXist-db Benchmark Runner ==="
echo "Target: ${HOST}"
echo "Output: ${OUTPUT}"
echo ""

# Store test data
echo "Setting up test data..."
curl -s -u admin: -X PUT \
    -H "Content-Type: application/xquery" \
    --data-binary @setup-test-data.xq \
    "${BASE_URL}/db/setup-test-data.xq" > /dev/null

curl -s -u admin: "${BASE_URL}/db/setup-test-data.xq"
echo ""

# Store and run benchmark
echo "Storing benchmark runner..."
curl -s -u admin: -X PUT \
    -H "Content-Type: application/xquery" \
    --data-binary @benchmark-runner.xq \
    "${BASE_URL}/db/bench/benchmark-runner.xq" > /dev/null

echo "Running benchmarks (this will take several minutes)..."
curl -s -u admin: "${BASE_URL}/db/bench/benchmark-runner.xq" -o "${OUTPUT}"

echo ""
echo "Results saved to ${OUTPUT}"
echo ""
cat "${OUTPUT}"
