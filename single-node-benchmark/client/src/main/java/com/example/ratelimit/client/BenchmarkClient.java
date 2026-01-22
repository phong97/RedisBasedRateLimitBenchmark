package com.example.ratelimit.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

import com.example.ratelimit.grpc.LimitRequest;
import com.example.ratelimit.grpc.RateLimitServiceGrpc;
import com.example.ratelimit.grpc.ResetRequest;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.HdrHistogram.Recorder;

public class BenchmarkClient {

    private static final int DEFAULT_THREADS = 8;
    private static final int DEFAULT_DURATION_SECONDS = 10;
    private static final int DEFAULT_WARMUP_SECONDS = 5;
    private static final double HOT_KEY_RATIO = 0.90;

    public enum KeyMode {
        SINGLE,
        FOUR_BALANCED,
        FOUR_HOT
    }

    public static void run(String[] args, KeyMode mode, int[] targetRpsList) throws InterruptedException {
        String target = getArg(args, 0, "localhost:9091");
        String keyPrefix = getArg(args, 1, "key");
        int threads = Integer.parseInt(getArg(args, 2, String.valueOf(DEFAULT_THREADS)));
        int durationSeconds = Integer.parseInt(getArg(args, 3, String.valueOf(DEFAULT_DURATION_SECONDS)));
        int warmupSeconds = Integer.parseInt(getArg(args, 4, String.valueOf(DEFAULT_WARMUP_SECONDS)));

        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();

        RateLimitServiceGrpc.RateLimitServiceStub stub = RateLimitServiceGrpc.newStub(channel);
        RateLimitServiceGrpc.RateLimitServiceBlockingStub blockingStub = RateLimitServiceGrpc.newBlockingStub(channel);

        resetKeys(blockingStub, keyPrefix, mode);
        runWarmup(stub, keyPrefix, mode, threads, warmupSeconds, targetRpsList);
        for (int rps : targetRpsList) {
            runBenchmark(stub, keyPrefix, mode, threads, durationSeconds, rps);
        }

        channel.shutdown();
        channel.awaitTermination(10, TimeUnit.SECONDS);
    }

    private static void runWarmup(RateLimitServiceGrpc.RateLimitServiceStub stub,
                                  String keyPrefix,
                                  KeyMode mode,
                                  int threads,
                                  int warmupSeconds,
                                  int[] targetRpsList) throws InterruptedException {
        int warmupRps = targetRpsList[0];
        runBenchmarkInternal("warmup", stub, keyPrefix, mode, threads, warmupSeconds, warmupRps);
    }

    private static void runBenchmark(RateLimitServiceGrpc.RateLimitServiceStub stub,
                                     String keyPrefix,
                                     KeyMode mode,
                                     int threads,
                                     int durationSeconds,
                                     int targetRps) throws InterruptedException {
        runBenchmarkInternal("benchmark", stub, keyPrefix, mode, threads, durationSeconds, targetRps);
    }

    private static void runBenchmarkInternal(String phase,
                                             RateLimitServiceGrpc.RateLimitServiceStub stub,
                                             String keyPrefix,
                                             KeyMode mode,
                                             int threads,
                                             int durationSeconds,
                                             int targetRps) throws InterruptedException {
        long durationNanos = Duration.ofSeconds(durationSeconds).toNanos();
        long endTime = System.nanoTime() + durationNanos;
        int baseRps = targetRps / threads;
        int remainder = targetRps % threads;

        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong errors = new AtomicLong();
        AtomicLong sent = new AtomicLong();
        AtomicLong inflight = new AtomicLong();
        LongAdder successCount = new LongAdder();
        LongAdder failCount = new LongAdder();
        LongAdder totalLatencyNanos = new LongAdder();
        Recorder recorder = new Recorder(TimeUnit.SECONDS.toNanos(10), 3);

        IntSupplier keyIndexSupplier = keyIndexSupplier(mode);

        long start = System.nanoTime();

        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                int threadIndex = t;
                executor.submit(() -> {
                    int threadRps = threadIndex < remainder ? baseRps + 1 : baseRps;
                    if (threadRps == 0) {
                        while (System.nanoTime() < endTime) {
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                        }
                        done.countDown();
                        return;
                    }
                    long intervalNanos = 1_000_000_000L / threadRps;
                    long next = System.nanoTime();
                    while (System.nanoTime() < endTime) {
                        int keyIndex = keyIndexSupplier.getAsInt();
                        String key = keyPrefix + keyIndex;
                        LimitRequest request = LimitRequest.newBuilder().setKey(key).build();
                        long requestStart = System.nanoTime();
                        inflight.incrementAndGet();
                        stub.limit(request, new StreamObserver<>() {
                            @Override
                            public void onNext(com.example.ratelimit.grpc.LimitResponse value) {
                                System.out.println(value.getCount());
                            }

                            @Override
                            public void onError(Throwable t) {
                                errors.incrementAndGet();
                                failCount.increment();
                                long latency = System.nanoTime() - requestStart;
                                totalLatencyNanos.add(latency);
                                recordLatency(recorder, latency);
                                inflight.decrementAndGet();
                            }

                            @Override
                            public void onCompleted() {
                                successCount.increment();
                                long latency = System.nanoTime() - requestStart;
                                totalLatencyNanos.add(latency);
                                recordLatency(recorder, latency);
                                inflight.decrementAndGet();
                            }
                        });
                        sent.incrementAndGet();
                        next += intervalNanos;
                        long sleepNanos = next - System.nanoTime();
                        if (sleepNanos > 0) {
                            LockSupport.parkNanos(sleepNanos);
                        }
                    }
                    done.countDown();
                });
            }

            done.await();
            while (inflight.get() > 0) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            long elapsedNanos = System.nanoTime() - start;
            double seconds = Duration.ofNanos(elapsedNanos).toMillis() / 1000.0;
            double rps = sent.get() / seconds;
            long completed = successCount.sum() + failCount.sum();
            double avgMillis = completed > 0 ? (totalLatencyNanos.sum() / 1_000_000.0) / completed : 0.0;
            var histogram = recorder.getIntervalHistogram();
            double p95Millis = histogram.getValueAtPercentile(95.0) / 1000.0;
            double p99Millis = histogram.getValueAtPercentile(99.0) / 1000.0;

            System.out.printf(Locale.US,
                    "%s mode=%s targetRps=%d total=%d success=%d fail=%d avg=%.3fms p95=%.3fms p99=%.3fms time=%.3fs rps=%.0f%n",
                    phase, mode.name(), targetRps, sent.get(), successCount.sum(), failCount.sum(),
                    avgMillis, p95Millis, p99Millis, seconds, rps);
        }
    }

    private static void recordLatency(Recorder recorder, long latencyNanos) {
        long micros = Math.max(1, TimeUnit.NANOSECONDS.toMicros(latencyNanos));
        recorder.recordValue(micros);
    }

    private static void resetKeys(RateLimitServiceGrpc.RateLimitServiceBlockingStub blockingStub,
                                  String keyPrefix,
                                  KeyMode mode) {
        List<String> keys = new ArrayList<>();
        switch (mode) {
            case SINGLE -> keys.add(keyPrefix + 0);
            case FOUR_BALANCED, FOUR_HOT -> {
                keys.add(keyPrefix + 0);
                keys.add(keyPrefix + 1);
                keys.add(keyPrefix + 2);
                keys.add(keyPrefix + 3);
            }
        }
        ResetRequest request = ResetRequest.newBuilder().addAllKeys(keys).build();
        blockingStub.reset(request);
    }

    private static IntSupplier keyIndexSupplier(KeyMode mode) {
        return switch (mode) {
            case SINGLE -> () -> 0;
            case FOUR_BALANCED -> () -> ThreadLocalRandom.current().nextInt(4);
            case FOUR_HOT -> () -> ThreadLocalRandom.current().nextDouble() < HOT_KEY_RATIO ? 0
                    : 1 + ThreadLocalRandom.current().nextInt(3);
        };
    }

    private static String getArg(String[] args, int index, String defaultValue) {
        if (args.length > index && args[index] != null && !args[index].isBlank()) {
            return args[index];
        }
        return defaultValue;
    }
}
