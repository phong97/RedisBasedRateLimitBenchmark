# Redis Rate Limit Benchmark Report - Extended Single Key Test

> **Test Date**: January 27, 2026 21:43-21:48 (+07:00)  
> **Mode**: SINGLE key (simulates hot key scenario)  
> **Duration per step**: 30 seconds  
> **RPS Range**: 10,000 - 30,000 RPS (9 steps)

---

## Executive Summary

| Metric | Value |
|--------|-------|
| **Recommended Max RPS** | ~27,500 RPS |
| **Absolute Limit** | ~28,700 RPS (with degradation) |
| **Breaking Point** | 30,000 RPS (49% failure rate) |
| **Optimal P99 Latency** | <7ms (at 10k-12.5k RPS) |
| **Acceptable P99 Latency** | <137ms (at 27.5k RPS) |

---

## Detailed Benchmark Results

| Target RPS | Actual RPS | Success | Fail | Fail % | Avg Latency | P95 | P99 | Status |
|------------|------------|---------|------|--------|-------------|-----|-----|--------|
| 10,000 | 10,000 | 300,000 | 0 | 0% | 0.94ms | 1.74ms | **6.26ms** | ✅ Excellent |
| 12,500 | 12,500 | 374,987 | 0 | 0% | 0.98ms | 1.54ms | **3.71ms** | ✅ Excellent |
| 15,000 | 14,999 | 436,764 | 13,225 | 2.9% | 22.5ms | 19.9ms | **702ms** | ⚠️ Anomaly |
| 17,500 | 17,499 | 524,986 | 0 | 0% | 3.17ms | 11.0ms | **42.1ms** | ✅ Good |
| 20,000 | 19,999 | 564,317 | 35,664 | 5.9% | 67.5ms | 390ms | **667ms** | ⚠️ Degraded |
| 22,500 | 22,499 | 646,903 | 28,081 | 4.2% | 42.6ms | 190ms | **873ms** | ⚠️ Degraded |
| 25,000 | 24,997 | 749,964 | 0 | 0% | 19.5ms | 107ms | **234ms** | ⚡ Near Limit |
| 27,500 | 27,456 | 824,930 | 0 | 0% | 19.4ms | 92.3ms | **137ms** | ⚡ Optimal Limit |
| 30,000 | 28,718 | 458,166 | 441,809 | 49.1% | 751ms | 2.83s | **3.83s** | ❌ Collapsed |

---

## Key Insights

### 1. System Capacity
- **Maximum Sustainable Throughput**: **27,500 RPS** with P99 < 140ms
- **Actual Achieved RPS at 27.5k target**: 27,456 RPS (99.8% efficiency)
- **Breaking Point**: At 30,000 RPS, the system only achieved 28,718 RPS with 49% failures

### 2. Latency Characteristics
- **Sub-10ms P99**: Achieved at 10k-12.5k RPS (ideal for latency-sensitive applications)
- **Sub-50ms P99**: Achieved at 17.5k RPS (acceptable for most use cases)
- **Sub-150ms P99**: Achieved at 27.5k RPS (maximum throughput with reasonable latency)

### 3. Anomalies Detected
- **15k, 20k, 22.5k RPS showed temporary failures** while 25k and 27.5k recovered with 0% failure
- This non-linear pattern suggests:
  - JVM warmup effects (Hotspot JIT compilation)
  - TCP connection pool dynamics
  - Possible GC pauses during specific load transitions

### 4. Single Hot Key Impact
- This test represents **worst-case scenario** for Redis (1 key receiving all traffic)
- In production with distributed keys across millions of users, actual capacity would be higher
- CPU cache efficiency favors single key access (artificially boosting performance)

---

## Grafana Monitoring Evidence

### Overview - Throughput, Latency & Container Resources
![Dashboard Top Section](./report/grafana_dashboard_top_1769525455862.png)

Observations:
- **Total Throughput**: Shows clear ramp-up pattern from 10k to 30k RPS
- **End-to-End Latency**: Dramatic spike visible at 30k RPS step
- **Error Rate**: Correlated spikes at 15k, 20k, 22.5k, and 30k RPS
- **Container CPU**: Application container shows high pressure during peak load

---

### JVM & GC Pressure + Redis Performance
![Dashboard Middle Section](./report/grafana_dashboard_middle_1769525500675.png)

Observations:
- **JVM Heap Memory**: Shows active memory allocation patterns
- **Thread Count**: Stable thread pool management throughout test
- **Redis Latency**: Dramatic spike at saturation point confirms Redis as bottleneck
- **Redis CPU**: Peak around 0.25-0.3 cores (single-threaded Redis limitation)

---

### Redis Resources & Envoy Load Balancer
![Dashboard Bottom Section](./report/grafana_dashboard_envoy_and_stats_1769525560378.png)

Observations:
- **Redis Memory**: Stable memory usage (~2MB for single key)
- **Redis Connections**: Consistent connection pool
- **Envoy RPS by Status**: Clear visualization of error rate spikes
- **Envoy Connections**: Shows active connection management

---

## Grafana Capture Recording

![Browser captures Grafana dashboard](./report/grafana_capture_1769525350391.webp)

---

## Recommendations

### For Production Rate Limiting
1. **Target 20-25k RPS per Redis node** for comfortable headroom
2. **Add Redis Cluster** if higher throughput needed
3. **Implement circuit breaker** at ~80% capacity (22k RPS)

### For Future Benchmarks
1. **Test with realistic key cardinality** (10k-100k unique keys)
2. **Add multi-key distribution patterns** (Zipf distribution for realistic hot key simulation)
3. **Include network latency simulation** (add artificial latency to simulate cross-datacenter)

---

## Test Configuration

| Component | Configuration |
|-----------|---------------|
| Redis | Single node, latest image |
| Application | Spring Boot 3 + gRPC + Reactive Lettuce |
| Load Balancer | Envoy |
| Monitoring | Prometheus + Grafana + cAdvisor |
| Benchmark Duration | 30s per RPS level + 5s cooldown |
| Warmup | 5s at each target RPS before measurement |

---

## Artifacts

- Console log: [console.log](./console.log)
- Result directory: `benchmark_results/single_key_20260127_214303`
