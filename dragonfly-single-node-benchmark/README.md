# Dragonfly Rate Limit Benchmark

Minimal Spring Boot 3 + gRPC app for benchmarking Dragonfly-based rate limiting **with comprehensive bottleneck detection**.

## Architecture

- gRPC server (Spring Boot) in [server](server)
- Java benchmark client (Benchmark) in [client](client)
- Dragonfly + Prometheus + Grafana + Envoy + **cAdvisor** in [docker-compose.yml](docker-compose.yml)

## 🔥 New: Bottleneck Detection

This benchmark helps you identify **whether Dragonfly or the Java App is the bottleneck**:

- **Container-level metrics** (CPU, memory, network) via cAdvisor
- **JVM metrics** (GC pressure, heap, thread pool)
- **Latency breakdown** (Total vs Dragonfly vs App overhead)
- **Automated stress testing** to find breaking points

📖 **[Read the full Bottleneck Detection Guide →](BOTTLENECK_DETECTION.md)**

## gRPC API

- Service: RateLimitService
- Method: Limit
- Request: { key: string }
- Response: { count: int64 }

Proto: [server/src/main/proto/ratelimit.proto](server/src/main/proto/ratelimit.proto)

## Quick Start

### 1. Run the full stack

```bash
docker compose up --build
```

This starts:
- Dragonfly (port 6379)
- Redis Exporter (port 9121)
- Prometheus (port 9090)
- Grafana (port 3000)
- cAdvisor (port 8080) ⬅️ NEW
- 2x App instances (ratelimit-app-1, ratelimit-app-2)
- Envoy Load Balancer (port 9091)

### 2. Run stress test

```bash
./stress_test.sh localhost:9091
```

This runs 12 scenarios from 1K to 100K RPS to find your breaking point.

### 3. View dashboard

Open Grafana at **http://localhost:3000** (admin/admin)

Dashboard includes:
- ✅ Overview (RPS, Latency, Errors)
- 🔥 **Container Resources** (CPU, Memory, Network)
- ☕ **JVM & GC Pressure**
- 🔴 Dragonfly Performance & Resources
- 🌐 Envoy Load Balancer
- 📈 Quick Stats

## Run Manual Benchmark

From [client](client):

```bash
# Single key test
./gradlew run --args="localhost:9091 key 8 30 5" -PmainClass=com.example.ratelimit.client.SingleKeyBenchmark

# 4 keys balanced
./gradlew run --args="localhost:9091 key 8 30 5" -PmainClass=com.example.ratelimit.client.FourKeyBalancedBenchmark

# 4 keys with 90% hot key
./gradlew run --args="localhost:9091 key 8 30 5" -PmainClass=com.example.ratelimit.client.FourKeyHotBenchmark
```

**Arguments:** target, keyPrefix, threads, durationSeconds, warmupSeconds

## Monitoring

| Service | URL | Purpose |
|---------|-----|---------|
| Grafana | http://localhost:3000 | Dashboards (admin/admin) |
| Prometheus | http://localhost:9090 | Metrics database |
| cAdvisor | http://localhost:8080 | Container metrics |

**Prometheus scrapes:**
- App metrics: http://app-1:8080/actuator/prometheus, http://app-2:8080/actuator/prometheus
- Redis exporter: http://redis-exporter:9121
- Envoy: http://grpc-lb:9901/stats/prometheus
- cAdvisor: http://cadvisor:8080/metrics

## 🎯 Finding Bottlenecks

### Is Dragonfly the bottleneck?
- Dragonfly CPU → 90-100%
- App CPU → 30-50%
- Dragonfly latency → High

**Solution:** Scale Dragonfly (cluster mode, faster hardware)

### Is Java App the bottleneck?
- App CPU → 90-100%
- GC Pressure → > 10%
- Large latency gap (Total - Dragonfly > 50ms)

**Solution:** Scale app instances, tune JVM, optimize code

📖 **[See full troubleshooting guide →](BOTTLENECK_DETECTION.md)**

## Development

### Build server
```bash
cd server
./gradlew build
```

### Build client
```bash
cd client
./gradlew build
```

### Run locally (without Docker)
```bash
# Start Dragonfly
docker run -p 6379:6379 docker.dragonflydb.io/dragonflydb/dragonfly

# Run server
cd server
./gradlew bootRun

# Run client (in another terminal)
cd client
./gradlew run --args="localhost:9090 key 8 30 5"
```

## Architecture Diagram

```
┌─────────────┐
│   Client    │
│ (JMH Load)  │
└──────┬──────┘
       │
       v
┌──────────────────┐
│  Envoy (LB)      │  ← Load balances gRPC
│  :9091           │
└────────┬─────────┘
         │
    ┌────┴────┐
    v         v
┌─────┐   ┌─────┐
│App-1│   │App-2│  ← Spring Boot gRPC servers
│:9090│   │:9090│
└──┬──┘   └──┬──┘
   │         │
   └────┬────┘
        v
   ┌─────────┐
   │Dragonfly│  ← Rate limit state
   │  :6379  │
   └─────────┘
        │
        v (metrics)
   ┌───────────────┐
   │  Prometheus   │  ← Scrapes all metrics
   │  :9090        │
   └───────┬───────┘
           v
      ┌─────────┐
      │ Grafana │  ← Visualization
      │ :3000   │
      └─────────┘
```

## Files Overview

```
.
├── docker-compose.yml          # Full stack orchestration
├── prometheus.yml              # Prometheus config (with cAdvisor)
├── envoy.yaml                  # Envoy LB config
├── stress_test.sh              # Automated stress testing ⬅️ NEW
├── generate_dashboard.py       # Dashboard generator ⬅️ NEW
├── BOTTLENECK_DETECTION.md     # Bottleneck guide ⬅️ NEW
├── server/
│   └── src/main/java/com/example/ratelimit/
│       ├── config/
│       │   ├── RedisConfig.java
│       │   └── MetricsConfig.java  ⬅️ NEW (custom metrics)
│       └── grpc/
│           └── RateLimitGrpcService.java
└── client/
    └── src/main/java/com/example/ratelimit/client/
        ├── BenchmarkClient.java
        ├── SingleKeyBenchmark.java
        ├── FourKeyBalancedBenchmark.java
        └── FourKeyHotBenchmark.java
```

---

**Happy Benchmarking! 🚀**

For detailed bottleneck analysis, see **[BOTTLENECK_DETECTION.md](BOTTLENECK_DETECTION.md)**
