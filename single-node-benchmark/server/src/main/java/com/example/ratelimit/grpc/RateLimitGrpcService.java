package com.example.ratelimit.grpc;

import java.util.List;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class RateLimitGrpcService extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;
    private final Counter totalRequests;
    private final MeterRegistry meterRegistry;

    public RateLimitGrpcService(ReactiveStringRedisTemplate redisTemplate,
                                RedisScript<Long> rateLimitScript,
                                MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.meterRegistry = meterRegistry;
        this.totalRequests = meterRegistry.counter("ratelimit.requests.total");
    }

    @Override
    public void limit(LimitRequest request, StreamObserver<LimitResponse> responseObserver) {
        String key = request.getKey();
        totalRequests.increment();
        meterRegistry.counter("ratelimit.requests.by_key", "key", key).increment();
        redisTemplate.execute(rateLimitScript, List.of(key))
                .single()
                .map(count -> LimitResponse.newBuilder().setCount(count).build())
                .subscribe(response -> {
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }, responseObserver::onError);
    }

    @Override
    public void reset(ResetRequest request, StreamObserver<ResetResponse> responseObserver) {
        redisTemplate.delete(request.getKeysList().toArray(new String[0]))
                .defaultIfEmpty(0L)
                .map(deleted -> ResetResponse.newBuilder().setDeleted(deleted).build())
                .subscribe(response -> {
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }, responseObserver::onError);
    }
}
