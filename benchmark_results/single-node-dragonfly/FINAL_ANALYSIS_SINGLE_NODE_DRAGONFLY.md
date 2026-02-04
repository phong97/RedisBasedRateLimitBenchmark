# Dragonfly Rate Limiter - Phân tích năng lực hệ thống & kiến trúc
**Tóm tắt điều hành cho triển khai Dragonfly đơn node**

> **Cơ sở**: Benchmark toàn diện cho các kịch bản Hundred Keys (Uniform) và Hot Key.
> **Dải tải đã test**: 10.000 - 30.000 RPS.

---

## 0. Cấu hình bài test & kiến trúc hệ thống (Testbed)

Cấu hình testbed tương tự như bài test Redis để đảm bảo tính công bằng (apple-to-apple comparison).

### 0.0 Cấu hình máy chạy benchmark
- **Máy/OS**: macOS (Apple M4, 10 cores, 24GB RAM).
- **Backend**: DragonflyDB (thay thế cho Redis 7.x).
- **Client/Proxy**: Spring Boot gRPC + Envoy (giữ nguyên).

### 0.1 Kiến trúc tổng quan
Dragonfly được deploy dưới dạng drop-in integration thay thế Redis container, expose cùng port `6379`.

ASCII flow:
```
Client
  -> Envoy grpc-lb :9091
      -> app-1 :9090
      -> app-2 :9090
          -> dragonfly :6379
```

---

## 1. Tổng quan năng lực hệ thống (Dragonfly)

| Kịch bản | Pha phân phối | Điểm gãy (Failures > 5%) | Ngưỡng an toàn khuyến nghị | So với Redis |
|----------|----------------|--------------------------|----------------------------|--------------|
| **100 Keys (Uniform)** | Phân tán đều | 30.000 RPS | **27.500 RPS** | **+35%** (Redis ~20k) |
| **100 Keys (Hot Key)** | Worst-case (lệch/hot) | 25.000 - 30.000 RPS | **22.500 RPS** | **+28%** (Redis ~17.5k) |

### Phát hiện quan trọng
1.  **Vượt trội về Throughput**: Dragonfly cho thấy khả năng xử lý vượt trội so với Redis đơn node, đặc biệt ở các mức tải cao (>20k RPS). Trong khi Redis bắt đầu ngộp thở ở 20k, Dragonfly vẫn chạy mượt mà với latency thấp.
2.  **Khả năng chịu đựng Hot Key**: Dragonfly xử lý kịch bản Hot Key (90% traffic vào 1 key) tốt hơn đáng kể. Kiến trúc đa luồng (multi-threaded shared-nothing) dường như giúp giảm bớt "noisy neighbor effect" của hot key lên các luồng xử lý khác, kéo dài ngưỡng chịu đựng lên tới 22.5k RPS an toàn.
3.  **Latency ổn định**: Một ưu điểm lớn là P99 latency. Tại mức 22.5k RPS (nơi Redis đã sập), Dragonfly vẫn duy trì P99 < 60ms cho Uniform và < 55ms cho Hot Key.

---

## 2. Ảnh hưởng của các kiểu phân phối traffic

### A. Phân phối đều (Uniform)
- **Hành vi**: Rất ổn định. Tải tăng tuyến tính mà không gây tăng vọt latency.
- **So sánh**: Redis bắt đầu nghẽn CPU (single core bão hòa) ở mức ~22k RPS. Dragonfly nhờ tận dụng đa core nên điểm nghẽn dời về phía Network I/O hoặc Client connection nhiều hơn là CPU của chính nó.

### B. Hot key / phân phối lệch (Skewed)
- **Hành vi**: Vẫn chịu ảnh hưởng của quy luật vật lý (serialization trên 1 key), nhưng đường cong suy giảm "mềm" hơn.
- **Fail Pattern**: Thay vì "sập sàn" (collapse) ngay lập tức như Redis ở 20k RPS (lỗi vọt lên 75%), Dragonfly chuyển sang trạng thái "degraded" (lỗi 2-8%) trong một khoảng rộng (25k-27.5k) trước khi thực sự quá tải ở 30k. Điều này giúp hệ thống có cơ hội tự phục hồi hoặc kích hoạt circuit breaker hiệu quả hơn.

---

## 3. Khuyến nghị triển khai production (với Dragonfly)

### 3.1 Quy hoạch kiến trúc / sizing
- **Giới hạn theo node**: Có thể tự tin nâng ngưỡng plan lên **20.000 - 22.000 RPS cho mỗi Dragonfly node**.
- **Cluster**: Với năng lực đơn node mạnh mẽ (gần 30k RPS), nhu cầu sharding/cluster có thể đến chậm hơn so với Redis. Một cụm Dragonfly đơn giản (Master-Replica) có thể gánh được workload mà trước đây cần Redis Cluster 3-shard.

### 3.2 Chiến lược giảm thiểu hot key
Dù Dragonfly khỏe hơn, **Hot Key vẫn là điểm yếu chí tử** của mọi in-memory store.
- Vẫn CẦN **Local Caching** (như Caffeine) cho các key cực nóng. Ngưỡng 22.5k tuy cao nhưng một viral event thread vẫn có thể dễ dàng vượt qua.
- Đừng ỷ lại vào sức mạnh của Dragonfly để bỏ qua thiết kế rate limiter nhiều lớp (multi-layer).

### 3.3 Tuning
- **Kết nối (Connections)**: Vì Dragonfly xử lý nhanh hơn, client có thể trở thành bottleneck nếu không đủ thread/connection pool. Cần chú ý cấu hình `Netty` worker threads và pool size phía ứng dụng Spring Boot.

---

## 4. Kết luận: Redis vs Dragonfly

| Tiêu chí | Redis Single Node | Dragonfly Single Node | Nhận xét |
|----------|-------------------|-----------------------|----------|
| **Max RPS (An toàn)** | ~15.000 - 18.000 | **22.500 - 27.500** | Dragonfly thắng áp đảo về raw performance. |
| **Latency tại tải cao** | Cao (hàng trăm ms) | **Thấp (< 50ms)** | Dragonfly giữ ổn định tốt hơn. |
| **Resilience (Hot Key)** | Kém (Sập nhanh) | **Khá (Degrade từ từ)** | Dragonfly cho thêm "thời gian vàng" để xử lý trước khi sập hoàn toàn. |
| **Độ phức tạp ops** | Thấp (Standard) | Thấp (Drop-in replacement) | Dễ dàng chuyển đổi. |

**Lời khuyên cuối cùng**:
Nếu hệ thống của bạn đang chạm ngưỡng 15k-20k RPS trên Redis và bạn đang cân nhắc chuyển sang Redis Cluster (phức tạp hơn), hãy thử **Dragonfly** trước. Nó có thể giải quyết bài toán performance ngay lập tức (Vertical Scaling) mà không cần thay đổi kiến trúc topology sang Cluster (Horizontal Scaling).
