# Redis Rate Limiter - Phân tích năng lực hệ thống & kiến trúc
**Tóm tắt điều hành cho triển khai Redis đơn node**

> **Cơ sở**: Benchmark toàn diện cho các kịch bản Single Key, Distributed Keys (100), và Hot Key.
> **Dải tải đã test**: 10.000 - 30.000 RPS.

---

## 0. Cấu hình bài test & kiến trúc hệ thống (Testbed)

Mục này mô tả **đúng topology** mình dùng trong workspace để chạy benchmark, nhằm giúp tái lập (reproduce) kết quả và hiểu chính xác “RPS/latency” đang đo ở đâu.

### 0.0 Cấu hình máy chạy benchmark

- **Máy/OS**:
  - Hệ điều hành: macOS **26.2** (Build **25C56**)
  - CPU: **Apple M4**
  - Số core logic: **10**
  - RAM: **24 GB**
- **Docker Engine**: **28.0.4**

> Ghi chú: Tất cả thành phần (Redis, Envoy, app, Prometheus, Grafana, exporter, cAdvisor) chạy bằng Docker Compose trên máy này.

### 0.1 Kiến trúc tổng quan

**Luồng request (data plane):**

`Java Benchmark Client` → `Envoy (gRPC LB)` → `Spring Boot gRPC (2 instances)` → `Redis`

**Luồng metrics (control plane):**

- `Prometheus` scrape:
  - App metrics: `app-1:8080/actuator/prometheus`, `app-2:8080/actuator/prometheus`
  - Envoy stats: `grpc-lb:9901/stats/prometheus`
  - Redis exporter: `redis-exporter:9121`
  - Container metrics: `cadvisor:8080/metrics`
- `Grafana` đọc từ Prometheus để vẽ dashboard.

### 0.2 Topology Single Node (mục tiêu của báo cáo này)

Nguồn cấu hình: `single-node-benchmark/docker-compose.yml`, `single-node-benchmark/envoy.yaml`, `single-node-benchmark/prometheus.yml`.

Thành phần chính:
- **Redis**: 1 node (`redis-benchmark`) – exposed `localhost:6379`.
- **App**: 2 instance (`ratelimit-app-1`, `ratelimit-app-2`) – gRPC nội bộ `:9090`, metrics HTTP `:8080`.
- **Envoy LB**: `grpc-lb` – listener `localhost:9091`, admin/stats `:9901`.
- **Observability**: Prometheus `:9090`, Grafana `:3000`, Redis Exporter `:9121`, cAdvisor `:8080`.

ASCII flow:

```
Client
  -> Envoy grpc-lb :9091
      -> app-1 :9090
      -> app-2 :9090
          -> redis :6379

Prometheus :9090 (scrape)
  <- app-1/app-2 :8080/actuator/prometheus
  <- grpc-lb :9901/stats/prometheus
  <- redis-exporter :9121
  <- cadvisor :8080/metrics

Grafana :3000 (query Prometheus)
```

### 0.3 Topology Cluster (tham chiếu mở rộng)

Nếu chạy biến thể cluster trong workspace này, topology nằm ở `cluster-benchmark/*`:

- **Redis Cluster**: 3 node (`redis-node-1..3`), publish ra host:
  - `redis-node-1`: `localhost:7010` (service port), `localhost:17010` (cluster bus)
  - `redis-node-2`: `localhost:7011`, `localhost:17011`
  - `redis-node-3`: `localhost:7012`, `localhost:17012`
- **redis-cluster-init**: container tiện ích tạo cluster (replicas = 0).
- **Redis Proxy**: HAProxy (`redis-proxy`) expose `localhost:6379` để app chỉ cần 1 endpoint.
- **App/Envoy/Prometheus/Grafana**: tương tự single-node.
- **cAdvisor**: expose `localhost:8085` (bên trong container vẫn là 8080).

Lưu ý: báo cáo này tập trung **single-node Redis**, phần cluster dùng để đối chiếu kiến trúc và hướng nâng cấp khi cần scale.

### 0.4 Bảng ports & endpoints

| Thành phần | Mục đích | Single-node (host) | Cluster (host) |
|---|---|---:|---:|
| Envoy gRPC listener | Điểm vào benchmark (client target) | `localhost:9091` | `localhost:9091` |
| Envoy admin/stats | Prometheus scrape | (internal) `:9901` | (internal) `:9901` |
| Redis | Endpoint Redis | `localhost:6379` | `localhost:6379` (qua HAProxy) |
| Prometheus | UI + TSDB | `localhost:9090` | `localhost:9090` |
| Grafana | Dashboard | `localhost:3000` | `localhost:3000` |
| Redis Exporter | Export Redis metrics | `localhost:9121` | `localhost:9121` |
| cAdvisor | Container metrics | `localhost:8080` | `localhost:8085` |

### 0.5 Cấu hình benchmark client (workload generator)

Benchmark client là Java tool tự viết (không phải JMH), chạy qua Gradle `application` plugin.

**Cú pháp args (theo README của client):**

- `target`: endpoint gRPC (thường là `localhost:9091` qua Envoy LB)
- `keyPrefix`: tiền tố key (ví dụ `key`, `stress`)
- `threads`: số thread bắn tải
- `durationSeconds`: thời gian đo chính thức
- `warmupSeconds`: thời gian warmup

Ví dụ (single key):
- `./gradlew run --args="localhost:9091 key 8 30 5" -PmainClass=com.example.ratelimit.client.SingleKeyBenchmark`

Trong `single-node-benchmark/stress_test.sh` mặc định:
- `THREADS=8`
- `WARMUP_SECONDS=5`
- `TEST_DURATION=30`
- Cooldown giữa các bước: `sleep 10`

### 0.6 Các pattern phân phối key (mapping với các scenario trong báo cáo)

Workspace có các entrypoint benchmark (single-node):

- `SingleKeyBenchmark`: tất cả request đập vào **1 key** → mô phỏng contention cực cao nhưng “cache-friendly”.
- `FourKeyBalancedBenchmark`: 4 key chia đều.
- `FourKeyHotBenchmark`: 4 key nhưng **90% vào 1 hot key**.

Trong phần kết luận của báo cáo này có 3 nhóm kịch bản (Single Key / Distributed Keys / Hot Key). Khi tái lập, bạn có thể quy ước tương ứng:

- **Single Key** → `SingleKeyBenchmark`
- **Phân phối đều (uniform)** → benchmark nhiều key chia đều (repo có biến thể 4-key balanced; các report “100 keys uniform” là mở rộng cùng ý tưởng)
- **Hot key / phân phối lệch (skewed)** → `FourKeyHotBenchmark` (hoặc biến thể nhiều key nhưng 1 key chiếm đa số traffic)

> Ghi chú: Nếu bạn cần “100 keys (uniform/hot)” tái lập đúng như report 100 keys, mình có thể bổ sung thêm entrypoint benchmark tương ứng trong `client` để mô hình hóa 100 keys (0..99) và tỷ lệ hot-key (ví dụ 90/10) giống hệt.

---

## 1. Tổng quan năng lực hệ thống

| Kịch bản | Pha phân phối | Điểm gãy (Failures > 5%) | Ngưỡng an toàn khuyến nghị |
|----------|----------------|--------------------------|----------------------------|
| **Single Key** | Best-case (tối ưu cache cục bộ) | 30.000 RPS | **25.000 RPS** |
| **100 Keys (Uniform)** | Thực tế hơn (phân tán) | 22.500 RPS | **18.000 RPS** |
| **100 Keys (Hot Key)** | Worst-case (lệch/hot) | 20.000 RPS | **15.000 RPS** |

### Phát hiện quan trọng
1. **“Single Key”**: Một key duy nhất có thể đạt ~27,5k RPS nhờ hiệu ứng CPU cache, nhưng đây là **cận trên lạc quan**. Traffic thực tế khi phân tán theo nhiều key sẽ phát sinh overhead (context switching, access pattern bộ nhớ…) khiến năng lực giảm khoảng ~25–30%.
2. **“100 Keys (Hot Key)”**: Một key “nóng” (90% traffic) là pattern nguy hiểm nhất. Nó bão hòa sớm hơn và gây **sụp đổ mạnh, khó tự hồi phục** so với phân phối đều.
3. **Latency**: Chuyển từ single key sang nhiều key có thể làm P99 tăng từ ~6ms lên ~30–60ms ngay cả ở mức tải còn khỏe.

---

## 2. Ảnh hưởng của các kiểu phân phối traffic

### A. Phân phối đều (Uniform) – lý tưởng
- **Hành vi**: Dễ dự đoán, tăng tải gần tuyến tính đến khoảng 20k RPS.
- **Cách fail**: Suy giảm từ từ. Latency tăng trước, sau đó mới xuất hiện timeout.
- **Hàm ý vận hành**: Dễ monitor. Các ngưỡng cảnh báo phổ thông (vd: P99 > 100ms) thường phát hiện sớm trước khi outage.

### B. Hot key / phân phối lệch (Skewed) – nguy hiểm
- **Hành vi**: “Sát thủ thầm lặng”. Latency có thể giữ tương đối thấp cho đến khi bỗng nhiên tăng vọt.
- **Cách fail**: **Sụp đổ dạng thảm họa**. Khi backlog do serialize trên hot key vượt quá khả năng buffer/queue, gần như toàn Redis node bị “kẹt” và ảnh hưởng đến tất cả clients.
- **Hàm ý vận hành**: Cần biện pháp giảm thiểu chủ động (throttling phía client / multi-layer). Auto-scaling phản ứng thường quá chậm để kịp cứu.

---

## 3. Khuyến nghị triển khai production

### 3.1 Quy hoạch kiến trúc / sizing
- **Giới hạn theo node**: Nên plan **15.000 RPS cho mỗi Redis node** để chịu được worst-case hot key.
- **Cluster vs Sentinel**:
  - **< 15k RPS**: Single master + Sentinel (HA) thường đủ.
  - **> 15k RPS**: Nên dùng **Redis Cluster**. Sharding giúp phân tán “overhead quản lý key/connection”, dù *không tự động giải quyết* vấn đề hot key nếu tất cả traffic vẫn dồn vào một key.

### 3.2 Chiến lược giảm thiểu hot key
Vì một user/key viral có thể kéo sập node ở ~20k RPS, nên cần **rate limit nhiều lớp (multi-layer)**:

1. **Bộ đệm local memory (Guava/Caffeine)**:
   - Đặt một buffer nhỏ local (vd: cho phép 10 req/s locally) cho các key có tần suất cao.
   - Chỉ gọi Redis khi token bucket local tràn.
   - *Trade-off*: giảm một chút độ chính xác để đổi lấy khả năng chịu tải/resilience tốt hơn.

2. **Circuit breaker**:
   - Nếu Redis latency > 100ms cho ~10% request, hãy **fail open** (cho qua) hoặc **fail closed** (chặn) ngay để Redis có cơ hội xả queue.
   - Tránh retry dồn dập.

### 3.3 Tuning cấu hình Redis
- **Timeout**: Đặt timeout phía client nghiêm (vd: 50–100ms). Thà fail rate-limit check (fail open/close) còn hơn treo thread của ứng dụng.
- **Persistence**: Với use-case rate limit thuần, nên tắt `AOF` hoặc đặt `fsync everysec` để tránh I/O đĩa tranh chấp CPU của main thread.

---

## 4. Kết luận

Redis rate limiter dạng single-node là lựa chọn tốt cho **startup và ứng dụng cỡ vừa** với peak traffic dưới **15.000 RPS**.

Tuy nhiên, với tải enterprise hoặc workload “viral”:
1. **Đừng tin benchmark “single key” như một sự thật cho production.**
2. **Ngưỡng an toàn thực tế là 15k, không phải 30k.**
3. **Scale ngang (Cluster)** nên thực hiện sớm hơn so với những gì CPU metrics gợi ý, chủ yếu để xử lý đồng thời kết nối và overhead do đa dạng key.
