# Redis Rate Limit Benchmark Report - Hot Key Test

> **Test Date**: February 1, 2026 18:42-18:55 (+07:00)
> **Mode**: HUNDRED_KEYS_HOT (100 keys, 90% load on 1 key)
> **Duration per step**: 60 seconds
> **RPS Range**: 10,000 - 30,000 RPS (9 steps)

---

## Executive Summary

| Metric | Value | Comparison to Uniform Load |
|--------|-------|--------------------------|
| **Recommended Max RPS** | ~17,500 RPS | Lower (vs 20,000 RPS) |
| **Stability Limit** | 17,500 RPS (0% failure) | Reduced Stability |
| **Breaking Point** | 20,000 RPS (75% failure) | **Severe Degradation** (Uniform failed at 22.5k) |
| **Optimal P99 Latency** | <160ms (at 17.5k RPS) | Higher (Uniform was <60ms) |

**Key Finding**: 
The "Hot Key" scenario represents the **worst-case performance** for the system. While the Single Key test (theoretical limit) hit 27.5k RPS and the Uniform Hundred Key test hit 20k RPS, the **Hot Key test collapsed at 20k RPS with a much sharper failure curve**.  This proves that **resource contention on a single key** combined with the **overhead of handling multiple keys** significantly reduces the system's effective capacity.

---

## Detailed Benchmark Results

| Target RPS | Actual RPS | Success | Fail | Fail % | Avg Latency | P95 | P99 | Status |
|------------|------------|---------|------|--------|-------------|-----|-----|--------|
| 10,000 | 9,997 | 600,000 | 0 | 0% | 3.08ms | 19.7ms | **36.4ms** | ✅ Excellent |
| 12,500 | 12,493 | 749,989 | 0 | 0% | 12.7ms | 45.4ms | **66.4ms** | ✅ Good |
| 15,000 | 15,000 | 900,000 | 0 | 0% | 18.1ms | 59.0ms | **83.6ms** | ✅ Good |
| 17,500 | 17,494 | 1,049,995 | 0 | 0% | 36.3ms | 109ms | **160ms** | ⚡ Near Limit |
| 20,000 | 13,873 | 306,231 | 893,757 | **74.5%** | 12.4s | 38.0s | **49.1s** | 💀 Collapsed |
| 22,500 | 11,835 | 117,865 | 1,231,898 | **91.3%** | 30.9s | 76.3s | **83.4s** | 💀 Dead |
| 25,000 | 6,031 | 207,837 | 1,248,614 | **85.7%** | 110s | 215s | **228s** | 💀 Dead |
| 27,500 | 27,499 | 115,901 | 1,534,066 | **93.0%** | 439ms | 3.18s | **7.08s** | 💀 Dead |

---

## Analysis: Hot Key Impact

The "Hot Key" pattern (simulating a viral event or abusive user) creates a specific stress signature on the system:

1.  **Earlier Saturation**: The system saturated at **17,500 RPS healthy** vs 20,000 RPS in the uniform test. The "hot" key became a bottleneck for thread contention within Redis, blocking the event loop more effectively than distributed keys.
2.  **Catastrophic Failure**: At 20,000 RPS, the failure rate didn't just creep up (e.g., 5-10%); it **exploded to 75%** immediately. This suggests that once the hot key queue backs up, it blocks *all* other operations, causing a "stop-the-world" effect for the client application.
3.  **Latency penalty**: Even at healthy levels (17.5k), P99 latency (160ms) was **2.5x higher** than the uniform test (60ms). This "wait time" is the cost of serialized access to the hot counter in Redis.

---

## Grafana Monitoring Evidence

### Overview - The Cliff at 20k
![Top Section](./report/grafana_hotkey_top_1769948240483.png)
*Figure 1: Notice the sharp drop in throughput and massive spike in latency at 19:08 (corresponding to the 20k RPS step).*

### Resource Utilization
![Middle Section](./report/grafana_hotkey_middle_1769948331576.png)
*Figure 2: JVM Memory and CPU usage plateaus as the application becomes I/O bound waiting for Redis.*

### Redis Performance
![Bottom Section](./report/grafana_hotkey_bottom_1769948343755.png)
*Figure 3: Redis latency spikes correlate perfectly with the application failure.*

---

## Recommendations

### 1. Hot Key Mitigation is Critical
- A single hot key can bring down the entire rate limiting node at **~17.5k RPS**.
- **Solution**: If a single user exceeds this rate, they must be throttled *before* reaching Redis (e.g., local caching in the application sidecar/client).

### 2. Conservative Capacity Planning
- Do not plan for 27.5k (Single Key result) or 20k (Uniform result).
- **The true safe capacity is ~15,000 RPS** per Redis node to account for non-uniform traffic distributions.

### 3. Architecture Changes for Scale
- **Write-Behind Caching**: Accumulate counter increments locally and flush to Redis in batches (sacrifices strict precision for massive throughput).
- **Sharding**: While helpful for general traffic, standard key-based sharding **does not solve** the hot key problem (all traffic for key 'A' still goes to Shard 1). You would need **Local bucket + Global bucket** synchronization.

---

## Artifacts

- Console log: [console.log](./console.log)
- Result directory: `benchmark_results/hundred_keys_hot_20260201_184238`
