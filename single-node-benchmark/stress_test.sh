#!/bin/bash
# Stress Test Script để tìm breaking point của Redis vs Java App
# Usage: ./stress_test.sh

set -euo pipefail

ENVOY_TARGET="${1:-localhost:9091}"
KEY_PREFIX="stress"
THREADS=8
WARMUP_SECONDS=5
TEST_DURATION=30

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}╔════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║   Redis Rate Limit Benchmark - Stress Test    ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════╝${NC}"
echo ""

# Test scenarios with progressively increasing load
# Format: RPS MODE DESCRIPTION
SCENARIOS=(
    "1000 SINGLE 1K RPS - Single Key (Baseline)"
    "5000 SINGLE 5K RPS - Single Key (Moderate)"
    "10000 SINGLE 10K RPS - Single Key (Heavy)"
    "20000 SINGLE 20K RPS - Single Key (Stress)"
    "50000 SINGLE 50K RPS - Single Key (Breaking Point?)"
    "100000 SINGLE 100K RPS - Single Key (Ultimate Test)"
    "5000 FOUR_BALANCED 5K RPS - 4 Keys Balanced"
    "10000 FOUR_BALANCED 10K RPS - 4 Keys Balanced"
    "20000 FOUR_BALANCED 20K RPS - 4 Keys Balanced"
    "50000 FOUR_BALANCED 50K RPS - 4 Keys Balanced"
    "10000 FOUR_HOT 10K RPS - 4 Keys (90% Hot)"
    "20000 FOUR_HOT 20K RPS - 4 Keys (90% Hot)"
)

# Results file
RESULTS_FILE="stress_test_results_$(date +%Y%m%d_%H%M%S).txt"
echo "Results will be saved to: $RESULTS_FILE"
echo ""

# Header
{
    echo "=========================================="
    echo "Stress Test Results"
    echo "Started: $(date)"
    echo "Target: $ENVOY_TARGET"
    echo "=========================================="
    echo ""
} > "$RESULTS_FILE"

cd client

for scenario in "${SCENARIOS[@]}"; do
    read -r target_rps mode description <<< "$scenario"
    
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${GREEN}Testing: ${description}${NC}"
    echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    
    # Determine main class
    case $mode in
        SINGLE)
            main_class="com.example.ratelimit.client.SingleKeyBenchmark"
            ;;
        FOUR_BALANCED)
            main_class="com.example.ratelimit.client.FourKeyBalancedBenchmark"
            ;;
        FOUR_HOT)
            main_class="com.example.ratelimit.client.FourKeyHotBenchmark"
            ;;
        *)
            echo "Unknown mode: $mode"
            continue
            ;;
    esac
    
    # Run test
    echo "Running: ./gradlew run --args=\"$ENVOY_TARGET $KEY_PREFIX $THREADS $TEST_DURATION $WARMUP_SECONDS\" -PmainClass=$main_class"
    
    {
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "TEST: $description"
        echo "Target RPS: $target_rps | Mode: $mode"
        echo "Time: $(date)"
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    } >> "../$RESULTS_FILE"
    
    # Run and capture output
    if ./gradlew run --args="$ENVOY_TARGET $KEY_PREFIX $THREADS $TEST_DURATION $WARMUP_SECONDS" \
        -PmainClass="$main_class" 2>&1 | tee -a "../$RESULTS_FILE"; then
        echo -e "${GREEN}✓ Test completed successfully${NC}"
    else
        echo -e "${RED}✗ Test failed or errored${NC}"
    fi
    
    echo "" >> "../$RESULTS_FILE"
    
    # Cool down period
    echo -e "${BLUE}Cooling down for 10 seconds...${NC}"
    sleep 10
    echo ""
done

cd ..

# Summary
echo -e "${BLUE}╔════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║            Stress Test Completed!             ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════╝${NC}"
echo ""
echo "Full results saved to: $RESULTS_FILE"
echo ""
echo "Next steps:"
echo "1. Check Grafana dashboard at http://localhost:3000"
echo "2. Look for:"
echo "   - Rising latency (P99 > 100ms = bottleneck)"
echo "   - High error rate (> 1% = overload)"
echo "   - CPU saturation (> 90% = CPU bound)"
echo "   - High GC % (> 10% = memory pressure)"
echo "   - Large latency gap (App overhead > Redis latency)"
echo ""
echo "Analyze results with:"
echo "  grep 'benchmark mode' $RESULTS_FILE"
echo ""
