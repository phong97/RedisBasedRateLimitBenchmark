# AI Copilot Instructions

## Project Context
This is a benchmark workspace for Redis-based rate limiting systems. It consists of a Java/Spring Boot gRPC server, a Java gRPC benchmark client, and an infrastructure setup (Envoy, Redis, Prometheus, Grafana).

## Architecture & Data Flow
- **Flow**: `Client` -> `Envoy (LB)` -> `Server` -> `Redis`.
- **Server**: Spring Boot 3 (WebFlux/Reactor) gRPC service using `net.devh:grpc-server-spring-boot-starter`.
- **Rate Limit Logic**: Implemented in `RateLimitGrpcService.java` using a fixed-window counter with Redis `INCR` + `EXPIRE` via a Lua script.
- **Proto Source**: `server/src/main/proto/ratelimit.proto` is the primary definition. A copy exists in `client/src/main/proto/ratelimit.proto`. Ensure they stay in sync.

## Key Patterns

### Server (`/server`)
- **Reactive Stack**: Uses `ReactiveStringRedisTemplate` for non-blocking Redis interactions.
- **Lua Scripting**: The Rate Limit logic is defined inline in `RedisConfig.java` as a Lua script to ensure atomicity.
  - *Pattern*: `INCR key` -> if 1 then `EXPIRE key ttl`.
- **Metrics**: Micrometer + Prometheus (`RateLimitGrpcService` increments `ratelimit.requests.total`).

### Client (`/client`)
- **Load Generation**: Custom Java benchmarking tool (not JMH).
- **Entry Points**: 
  - `SingleKeyBenchmark`: High contention on one key.
  - `FourKeyBalancedBenchmark` & `FourKeyHotBenchmark`: Distributed load scenarios.
- **Execution**: Run via Gradle `application` plugin (`./gradlew run`).

## Critical Workflows

### Infrastructure
- Start full stack: `docker compose up --build` (Redis, Envoy, App instances).
- Envoy balances traffic on `localhost:9091` to app instances.

### Development
- **Server**: `./gradlew bootRun` (Port 9090).
- **Client**: 
  ```bash
  ./gradlew run --args="localhost:9091 key 8 10 5" -PmainClass=com.example.ratelimit.client.SingleKeyBenchmark
  ```
  (Args: `target` `keyPrefix` `threads` `duration` `warmup`).

## Tech Stack
- **Language**: Java 21.
- **Frameworks**: Spring Boot 3.2, Project Reactor, gRPC.
- **Build**: Gradle.
- **Infra**: Redis, Envoy, Docker.

## Specific Guidelines
- When modifying the rate limit logic, update the Lua script in `RedisConfig.java`.
- If modifying `ratelimit.proto`, update both `server` and `client` copies.
- Prefer `ReactiveStringRedisTemplate` over blocking calls in the server.
