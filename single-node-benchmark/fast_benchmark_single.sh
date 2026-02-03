#!/bin/bash
set -euo pipefail

# Define variables
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BASE_DIR=$(pwd)
RESULTS_DIR="$BASE_DIR/../benchmark_results/single_key_$TIMESTAMP"
mkdir -p "$RESULTS_DIR"
LOG_FILE="$RESULTS_DIR/console.log"

echo "╔════════════════════════════════════════════════╗"
echo "║   Single Key Benchmark Execution               ║"
echo "╚════════════════════════════════════════════════╝"
echo "Results Directory: $RESULTS_DIR"
echo "Log File: $LOG_FILE"
echo "Starting test at $(date)"

# Navigate to client directory
cd client

# Run SingleKeyBenchmark
# Arguments: target, keyPrefix, threads, durationSeconds, warmupSeconds
# Using 30s duration per step to ensure steady state for Grafana
echo "Running ./gradlew run -PmainClass=com.example.ratelimit.client.SingleKeyBenchmark..."

./gradlew run \
    --args="localhost:9091 key 8 30 5" \
    -PmainClass=com.example.ratelimit.client.SingleKeyBenchmark \
    2>&1 | tee "$LOG_FILE"

STATUS=$?

if [ $STATUS -eq 0 ]; then
    echo "✅ Benchmark finished successfully."
    echo "BENCHMARK_SUCCESS=true"
else
    echo "❌ Benchmark failed."
    echo "BENCHMARK_SUCCESS=false"
fi

echo "Finished at $(date)"
echo "RESULT_DIR_PATH=$RESULTS_DIR"
