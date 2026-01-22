# Redis Rate Limit Benchmark

Minimal Spring Boot 3 + gRPC app for benchmarking Redis-based rate limiting.

## Architecture

- gRPC server (Spring Boot) in [server](server)
- Java benchmark client (Benchmark) in [client](client)
- Redis + Prometheus + Grafana + Envoy in [docker-compose.yml](docker-compose.yml)

## gRPC API

- Service: RateLimitService
- Method: Limit
- Request: { key: string }
- Response: { count: int64 }

Proto: [server/src/main/proto/ratelimit.proto](server/src/main/proto/ratelimit.proto)

## Run infrastructure (Redis + Prometheus + Grafana + Envoy)

Use Docker Compose in [docker-compose.yml](docker-compose.yml). Envoy listens on 9091 and balances traffic to two app instances.

## Run the server

The server lives in [server](server). It exposes gRPC on 9090 and Prometheus metrics on 8080.

From [server](server):

```bash
./gradlew bootRun
```

### Run the server with Docker

Build and run the two app instances defined in [docker-compose.yml](docker-compose.yml):

```bash
docker compose up --build app-1 app-2
```

To run the full stack (Redis, Prometheus, Grafana, Envoy, and both app instances):

```bash
docker compose up --build
```

## Run Benchmark

Benchmark is the Java gRPC benchmark client in [client](client). Use one of these entrypoints for each case:

- Single key: [client/src/main/java/com/example/ratelimit/client/SingleKeyBenchmark.java](client/src/main/java/com/example/ratelimit/client/SingleKeyBenchmark.java)
- 4 keys balanced: [client/src/main/java/com/example/ratelimit/client/FourKeyBalancedBenchmark.java](client/src/main/java/com/example/ratelimit/client/FourKeyBalancedBenchmark.java)
- 4 keys (1 hot): [client/src/main/java/com/example/ratelimit/client/FourKeyHotBenchmark.java](client/src/main/java/com/example/ratelimit/client/FourKeyHotBenchmark.java)

Each entrypoint targets the Envoy load balancer at localhost:9091 by default.

From [client](client):

```bash
./gradlew run --args="localhost:9091 key 8 10 5"
```

Replace the main class to run a specific case:

```bash
./gradlew run --args="localhost:9091 key 8 10 5" -PmainClass=com.example.ratelimit.client.SingleKeyBenchmark
./gradlew run --args="localhost:9091 key 8 10 5" -PmainClass=com.example.ratelimit.client.FourKeyBalancedBenchmark
./gradlew run --args="localhost:9091 key 8 10 5" -PmainClass=com.example.ratelimit.client.FourKeyHotBenchmark
```

Arguments: target, keyPrefix, threads, durationSeconds, warmupSeconds.

## Monitoring

Prometheus is available at http://localhost:9090 and Grafana at http://localhost:3000 (admin/admin).

Prometheus scrapes:
- App metrics: http://localhost:8080/actuator/prometheus
- Redis exporter: http://localhost:9121
