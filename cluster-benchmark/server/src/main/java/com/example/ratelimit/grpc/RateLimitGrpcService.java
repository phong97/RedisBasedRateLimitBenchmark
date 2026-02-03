package com.example.ratelimit.grpc;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class RateLimitGrpcService extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final Counter totalRequests;
    private final Counter redisErrors;
    private final Timer redisTimer;

    public RateLimitGrpcService(ReactiveStringRedisTemplate redisTemplate,
                                RedisScript<Long> rateLimitScript,
                                MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.totalRequests = meterRegistry.counter("ratelimit.requests.total");
        this.redisErrors = meterRegistry.counter("ratelimit.redis.errors");
        this.redisTimer = Timer.builder("ratelimit.redis.latency")
                .description("Time taken to execute Redis script")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @Override
    public void limit(LimitRequest request, StreamObserver<LimitResponse> responseObserver) {
        String key = request.getKey();
        totalRequests.increment();
        long start = System.nanoTime();
        redisTemplate.execute(rateLimitScript, List.of(key), List.of("1"))
                .single()
                .doOnTerminate(() -> redisTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS))
                .map(count -> LimitResponse.newBuilder().setCount(count).build())
                .subscribe(response -> {
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }, error -> {
                    redisErrors.increment();
                    responseObserver.onError(toGrpcException("redis rateLimit failed", error));
                });
    }

    @Override
    public void reset(ResetRequest request, StreamObserver<ResetResponse> responseObserver) {
        redisTemplate.delete(request.getKeysList().toArray(new String[0]))
                .defaultIfEmpty(0L)
                .map(deleted -> ResetResponse.newBuilder().setDeleted(deleted).build())
                .subscribe(response -> {
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }, error -> responseObserver.onError(toGrpcException("redis reset failed", error)));
    }

    private static Throwable toGrpcException(String message, Throwable error) {
        // Spring Data Redis typically wraps connectivity issues in runtime exceptions; classify conservatively.
        Status status = Status.INTERNAL.withDescription(message + ": " + safeMessage(error));

        // Heuristic: treat obvious network/availability problems as UNAVAILABLE.
        for (Throwable t = error; t != null; t = t.getCause()) {
            String cn = t.getClass().getName();
            if (cn.contains("RedisConnectionFailureException")
                    || cn.contains("PoolException")
                    || cn.contains("io.lettuce.core.RedisConnectionException")
                    || cn.contains("java.net.")
                    || cn.contains("Timeout")) {
                status = Status.UNAVAILABLE.withDescription(message + ": " + safeMessage(error));
                break;
            }
        }

        return status.withCause(error).asRuntimeException();
    }

    private static String safeMessage(Throwable t) {
        if (t == null) {
            return "null";
        }
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }
}
