# Dragonfly Rate Limit Benchmark Report - Hot Key Test

> **Test Date**: February 5, 2026 00:56 (+07:00)
> **Mode**: HUNDRED_KEYS_HOT (100 keys, 90% load on 1 key)
> **Duration per step**: 60 seconds
> **RPS Range**: 10,000 - 30,000 RPS (9 steps)

---

## Executive Summary

| Metric | Value | Comparison to Redis Hot Key |
|--------|-------|--------------------------|
| **Recommended Max RPS** | ~22,500 RPS | **Higher** (vs 17,500 RPS) |
| **Stability Limit** | 22,500 RPS (0% failure) | **+28% Capacity** |
| **Breaking Point** | 25,000 RPS (2.6% failure) | Degradation starts higher (Redis broke at 20k) |
| **Optimal P99 Latency** | <55ms (at 22.5k RPS) | **Better** (Redis was 160ms at 17.5k) |

**Key Finding**:
Dragonfly demonstrates significantly better resilience under hot-key conditions compared to standard Redis. While Redis saturated at 17.5k RPS and collapsed at 20k RPS, **Dragonfly maintained healthy performance (0% errors, P99 < 55ms) up to 22,500 RPS**. However, the physics of "Hot Key" serialization eventually kicked in at 25,000 RPS, causing latency to spike and failures to emerge, though the collapse was more gradual than Redis.

---

## Detailed Benchmark Results

| Target RPS | Actual RPS | Success | Fail | Fail % | Avg Latency | P95 | P99 | Status |
|------------|------------|---------|------|--------|-------------|-----|-----|--------|
| 10,000 | 10,000 | 600,000 | 0 | 0% | 0.96ms | 1.16ms | **4.98ms** | ✅ Excellent |
| 12,500 | 12,500 | 749,991 | 0 | 0% | 1.17ms | 1.82ms | **6.12ms** | ✅ Excellent |
| 15,000 | 15,000 | 899,987 | 0 | 0% | 1.54ms | 2.60ms | **10.18ms** | ✅ Excellent |
| 17,500 | 17,499 | 1,049,986 | 0 | 0% | 2.34ms | 4.60ms | **24.27ms** | ✅ Good |
| 20,000 | 19,999 | 1,199,982 | 0 | 0% | 4.29ms | 14.37ms | **42.05ms** | ✅ Good |
| 22,500 | 22,499 | 1,349,989 | 0 | 0% | 7.51ms | 28.98ms | **54.62ms** | ⚡ Limit Reached |
| 25,000 | 24,998 | 1,460,145 | 39,831 | **2.65%** | 94.98ms | 276ms | **358ms** | ⚠️ Degrading |
| 27,500 | 27,478 | 1,505,433 | 144,533 | **8.76%** | 106.9ms | 324ms | **420ms** | ❌ Overloaded |
| 30,000 | 28,880 | 704,778 | 1,095,190 | **60.8%** | 1.26s | 3.30s | **4.66s** | 💀 Collapsed |

---

## Analysis: Hot Key Impact

The "Hot Key" pattern (90% traffic on a single key) limits system throughput due to thread contention on the specific shard/key.

1.  **Extended Healthy Range**: Dragonfly extended the healthy operating range to **22,500 RPS** (vs 17,500 for Redis). This confirms Dragonfly's multi-threaded architecture provides efficiency gains even when specific keys are hot, likely due to better handling of the "other" 10% traffic or faster execution pipelines.
2.  **Gradual vs Sudden Death**:
    *   **Redis** at 20k -> 75% Failure instantly.
    *   **Dragonfly** at 25k -> 2.6% Failure; at 27.5k -> 8.7% Failure.
    *   Dragonfly degraded more "gracefully" initially, only collapsing fully at 30k RPS (60% failure). This suggests better backpressure handling or buffering.
3.  **Latency Superiority**: At 17.5k RPS (Redis's limit), Redis had P99 of **160ms**. At the same load, Dragonfly had P99 of **24ms**. Dragonfly is **6x faster** at comparable high loads.

---

## Grafana Monitoring Evidence

### Overview
![Dashboard Screenshot](./screenshot_00-20-41%205_2_2026.png)
*Figure 1: The throughput (yellow line) tracks the target successfully until the 25k step, where errors (green line) begin to appear.*

### Key Observations from Dashboard
- **Throughput**: Stable ramps up to 22.5k. At 25k, we see the first dip/instability.
- **Latency**: Remains extremely low (<10ms P99) until ~15k RPS. Begins climbing at 17.5k but stays safe (<100ms) until the 25k breaking point.
- **Resources**: (Referencing standard container metrics) Dragonfly typically shows higher CPU utilization efficiency per op compared to single-threaded Redis.

---

## Recommendations

### 1. Set Rate Limits Higher for Dragonfly
- If migrating from Redis to Dragonfly, you can safely **increase per-node rate limits by ~25-30%** even for skewed traffic.
- Safe conservative limit: **20,000 RPS** (providing a safety buffer below the 22.5k stable limit).

### 2. Hot Key Mitigation Still Required
- Despite better performance, the physics of a single hot counter still creates a hard ceiling (25k RPS).
- **Client-side buffering** or **Local Rate Limiting** is still required for use cases exceeding 25k RPS per key.
- "Infinite scaling" cannot be achieved on a single key, regardless of the backend store.

### 3. Architecture
- For extreme scale (>50k RPS/key), move to a **Tiered Rate Limit**:
    - **L1**: Local Caffeine Cache (Token Bucket) -> handles top 10% burst.
    - **L2**: Dragonfly (Fixed Window / Sliding Window) -> handles shared state.

---

## Artifacts

- Console log: [console.log](./console.log)
- Grafana Screenshot: `screenshot_00-20-41 5_2_2026.png`
