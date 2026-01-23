# 🔥 Bottleneck Detection Guide

## New Features

This benchmark now includes comprehensive bottleneck detection to answer: **"Is Redis overloaded or is the Java App the bottleneck?"**

### ✅ What's New

1. **cAdvisor Integration** - Container-level CPU, memory, network monitoring
2. **Enhanced JVM Metrics** - GC pressure, thread pool, heap usage
3. **Latency Breakdown Analysis** - Identify where time is spent (Redis vs App overhead)
4. **Stress Test Script** - Automated load testing to find breaking points
5. **Enhanced Grafana Dashboard** - New bottleneck detection panels

---

## 📊 Dashboard Panels

### 🔥 Container Resources & Bottleneck Detection (NEW)

| Panel | What to Monitor | Bottleneck Indicator |
|-------|----------------|----------------------|
| **Container CPU (%)** | CPU usage per container | **App > 90%** → App CPU bound |
| **Container Memory** | Memory per container | Continuously rising → Memory leak |
| **Latency Breakdown** | Total vs Redis vs App overhead | **Large gap** → App is slow |
| **GC Pressure (%)** | Time spent in GC | **> 10%** → Memory pressure |
| **Thread Pool Status** | Active threads | Continuously rising → Thread leak |
| **Network I/O** | Container network throughput | Max out → Network bound |

---

## 🔍 How to Detect Bottlenecks

### Scenario 1: Redis is the Bottleneck

**Symptoms:**
- ✅ Redis CPU → **90-100%**
- ✅ App CPU → **30-50%** (still has capacity)
- ✅ Redis latency → **High**
- ✅ App overhead → **Low** (< 5ms)

**Solution:** Scale Redis (cluster mode, replica sets, or faster hardware)

---

### Scenario 2: Java App is the Bottleneck

**Symptoms:**
- ❌ App CPU → **90-100%**
- ❌ Redis CPU → **30-50%** (still has capacity)
- ❌ GC Pressure → **> 10%**
- ❌ App overhead → **> 20ms**
- ❌ Latency gap large (Total - Redis > 50ms)

**Solution:** 
- Increase app instances (horizontal scaling)
- Tune JVM heap size
- Optimize connection pool
- Use async/reactive patterns

---

### Scenario 3: Connection Pool Exhaustion

**Symptoms:**
- ❌ Redis connection errors spiking
- ❌ Latency spikes
- ❌ Error rate > 1%

**Solution:** Increase `spring.data.redis.lettuce.pool.max-active` in `application.yml`

---

### Scenario 4: GC Thrashing

**Symptoms:**
- ❌ GC Pressure > 10%
- ❌ Heap usage > 85%
- ❌ P99 latency spikes

**Solution:**
- Increase heap size: `-Xmx4g -Xms4g`
- Tune GC: Use G1GC or ZGC
- Reduce object allocation

---

## 🚀 Running the Stress Test

### Quick Start

```bash
# Start the full stack
docker compose up --build

# Run stress test
./stress_test.sh localhost:9091
```

### What it Does

The stress test runs **12 scenarios** with increasing load:

1. **1K RPS** - Single Key (Baseline)
2. **5K RPS** - Single Key (Moderate)
3. **10K RPS** - Single Key (Heavy)
4. **20K RPS** - Single Key (Stress)
5. **50K RPS** - Single Key (Breaking Point?)
6. **100K RPS** - Single Key (Ultimate Test)
7. **5K-50K RPS** - 4 Keys Balanced
8. **10K-20K RPS** - 4 Keys (90% Hot)

Each test runs for **30 seconds** with **5 seconds warm-up**.

### Analyzing Results

```bash
# View results
cat stress_test_results_*.txt

# Extract summary
grep "benchmark mode" stress_test_results_*.txt
```

**Look for:**
- **Latency degradation** - When does P99 exceed 100ms?
- **Error rate spike** - When does error % exceed 1%?
- **Throughput plateau** - What's the max sustainable RPS?

---

## 📈 Grafana Dashboard

Access Grafana at **http://localhost:3000** (admin/admin)

### Key Metrics to Watch

1. **Total Throughput (RPS)** - Are you hitting target RPS?
2. **P99 Latency** - Is it under 100ms?
3. **Error Rate %** - Is it under 1%?
4. **Container CPU** - Which component is saturated?
5. **Latency Breakdown** - Where is time spent?
6. **GC Pressure %** - Is GC causing pauses?

---

## 🔧 Tuning Recommendations

### If App is Bottleneck

**1. Increase App Instances**
```yaml
# docker-compose.yml
services:
  app-3:
    build:
      context: ./server
    container_name: ratelimit-app-3
    environment:
      - SPRING_DATA_REDIS_HOST=redis
```

**2. Tune JVM Heap**
```dockerfile
# server/Dockerfile
ENV JAVA_TOOL_OPTIONS="-Xmx4g -Xms4g -XX:+UseG1GC"
```

**3. Increase Connection Pool**
```yaml
# application.yml
spring:
  data:
    redis:
      lettuce:
        pool:
          max-active: 1024  # Increase from 512
          max-idle: 512
```

---

### If Redis is Bottleneck

**1. Use Redis Cluster**
- Shard data across multiple nodes
- Horizontal scaling

**2. Enable Redis Multi-threading**
```conf
# redis.conf
io-threads 4
io-threads-do-reads yes
```

**3. Optimize Lua Script**
- Reduce Redis operations
- Use pipelining

---

## 📊 Expected Performance

| Component | Metric | Good | Warning | Critical |
|-----------|--------|------|---------|----------|
| App CPU | % | < 70% | 70-90% | > 90% |
| Redis CPU | % | < 70% | 70-90% | > 90% |
| P99 Latency | ms | < 20ms | 20-100ms | > 100ms |
| Error Rate | % | < 0.1% | 0.1-1% | > 1% |
| GC Pressure | % | < 5% | 5-10% | > 10% |

---

## 🎯 Next Steps

1. **Baseline Test** - Run at low load (1K RPS) to establish baseline
2. **Stress Test** - Use `./stress_test.sh` to find breaking point
3. **Analyze Grafana** - Identify bottleneck component
4. **Tune & Repeat** - Apply optimizations and re-test
5. **Document Findings** - Record max sustainable RPS

---

## 📝 Troubleshooting

### Issue: No Data in Grafana

**Solution:**
```bash
# Check if Prometheus is scraping
curl http://localhost:9090/api/v1/targets

# Check if cAdvisor is running
curl http://localhost:8080/metrics
```

### Issue: High Error Rate

**Causes:**
- Redis connection pool exhausted
- Redis max connections exceeded
- Network issues

**Debug:**
```bash
# Check Redis connections
docker exec redis-benchmark redis-cli CLIENT LIST | wc -l

# Check max connections
docker exec redis-benchmark redis-cli CONFIG GET maxclients
```

---

## 🎓 Learning Resources

- **PromQL Queries** - [Prometheus Query Documentation](https://prometheus.io/docs/prometheus/latest/querying/basics/)
- **cAdvisor Metrics** - [cAdvisor Metric Documentation](https://github.com/google/cadvisor/blob/master/docs/storage/prometheus.md)
- **JVM Tuning** - [Java GC Tuning Guide](https://docs.oracle.com/en/java/javase/21/gctuning/)

---

Happy benchmarking! 🚀
