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
import io.grpc.Metadata;
import io.grpc.stub.StreamObserver;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.HdrHistogram.Recorder;

public class BenchmarkClient {

    private static final int DEFAULT_THREADS = 8;
    private static final int DEFAULT_DURATION_SECONDS = 60;
    private static final int DEFAULT_WARMUP_SECONDS = 5;
    private static final double HOT_KEY_RATIO = 0.90;

    private static final Duration RESET_DEADLINE = Duration.ofSeconds(5);
    private static final int RESET_MAX_ATTEMPTS = 8;

    /**
     * If true, the benchmark will skip the Reset RPC and proceed directly to warmup/benchmark.
     * Useful when routing through proxies (e.g., Envoy) where Reset might be blocked/misrouted.
     */
    private static final boolean SKIP_RESET = Boolean.parseBoolean(System.getProperty("ratelimit.skipReset", "false"))
            || Boolean.parseBoolean(System.getenv().getOrDefault("RATELIMIT_SKIP_RESET", "false"));

    /**
     * If true, a Reset failure will not abort the benchmark. This keeps runs usable even when
     * reset is unsupported/misrouted through a proxy.
     */
    private static final boolean CONTINUE_ON_RESET_FAILURE = Boolean
            .parseBoolean(System.getProperty("ratelimit.continueOnResetFailure", "true"));

    private static final int LIMIT_ERROR_LOG_CAP = Integer
            .parseInt(System.getProperty("ratelimit.limitErrorLogCap", "10"));

    public enum KeyMode {
        SINGLE,
        HUNDRED_KEYS,
        HUNDRED_KEYS_HOT
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

        try {
            resetKeysWithRetry(blockingStub, target, keyPrefix, mode);
        } catch (StatusRuntimeException e) {
            if (!CONTINUE_ON_RESET_FAILURE) {
                throw e;
            }
            System.err.println(
                    "Reset failed and will be ignored (ratelimit.continueOnResetFailure=true). Continuing benchmark.");
        }

        runWarmup(stub, keyPrefix, mode, threads, warmupSeconds, targetRpsList);
        for (int rps : targetRpsList) {
            runBenchmark(stub, keyPrefix, mode, threads, durationSeconds, rps);
            System.out.println("Cooling down for 5 seconds...");
            Thread.sleep(5000);
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

    private static void resetKeysWithRetry(RateLimitServiceGrpc.RateLimitServiceBlockingStub blockingStub,
                                          String target,
                                          String keyPrefix,
                                          KeyMode mode) throws InterruptedException {
        if (SKIP_RESET) {
            System.out.println("Skipping reset (ratelimit.skipReset=true)");
            return;
        }

        List<String> keys = keysForMode(keyPrefix, mode);
        ResetRequest request = ResetRequest.newBuilder().addAllKeys(keys).build();

        long backoffMillis = 250;
        StatusRuntimeException last = null;

        for (int attempt = 1; attempt <= RESET_MAX_ATTEMPTS; attempt++) {
            try {
                var resp = blockingStub.withDeadlineAfter(RESET_DEADLINE.toMillis(), TimeUnit.MILLISECONDS).reset(request);
                if (attempt > 1) {
                    System.out.printf("Reset succeeded after %d attempts (target=%s, keys=%d, deleted=%d)%n",
                            attempt, target, keys.size(), resp.getDeleted());
                }
                return;
            } catch (StatusRuntimeException e) {
                last = e;
                Status.Code code = e.getStatus().getCode();

                // Only retry on transient-ish failures.
                boolean retryable = code == Status.Code.UNAVAILABLE
                        || code == Status.Code.DEADLINE_EXCEEDED
                        || code == Status.Code.RESOURCE_EXHAUSTED
                        || code == Status.Code.UNKNOWN;

                logGrpcFailure("Reset failed", attempt, RESET_MAX_ATTEMPTS, target, e);

                if (!retryable || attempt == RESET_MAX_ATTEMPTS) {
                    throw e;
                }

                Thread.sleep(backoffMillis);
                backoffMillis = Math.min(2000, backoffMillis * 2);
            }
        }

        // Should be unreachable, but keep compiler happy.
        if (last != null) {
            throw last;
        }
    }

    private static void logGrpcFailure(String prefix,
                                       int attempt,
                                       int maxAttempts,
                                       String target,
                                       StatusRuntimeException e) {
        Status status = e.getStatus();
        Metadata trailers = e.getTrailers();

        System.err.printf(
                Locale.ROOT,
                "%s (attempt %d/%d, target=%s, code=%s, desc=%s, cause=%s, trailersKeys=%s)%n",
                prefix,
                attempt,
                maxAttempts,
                target,
                status.getCode(),
                status.getDescription(),
                formatThrowable(e.getCause()),
                trailers == null ? "null" : trailers.keys());

        Throwable root = rootCause(e);
        if (root != null && root != e && root != e.getCause()) {
            System.err.println("Root cause: " + formatThrowable(root));
        }
    }

    private static String formatThrowable(Throwable t) {
        if (t == null) {
            return "null";
        }
        String msg = t.getMessage();
        return t.getClass().getName() + (msg == null ? "" : (": " + msg));
    }

    private static Throwable rootCause(Throwable t) {
        if (t == null) {
            return null;
        }
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static List<String> keysForMode(String keyPrefix, KeyMode mode) {
        List<String> keys = new ArrayList<>();
        switch (mode) {
            case SINGLE -> keys.add(keyPrefix + 0);
            case HUNDRED_KEYS, HUNDRED_KEYS_HOT -> {
                for (int i = 0; i < 100; i++) {
                    keys.add(keyPrefix + i);
                }
            }
        }
        return keys;
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
                                // Intentionally no per-request stdout printing; it destroys throughput/latency at high RPS.
                            }

                            @Override
                            public void onError(Throwable t) {
                                long errCount = errors.incrementAndGet();
                                if (errCount <= LIMIT_ERROR_LOG_CAP) {
                                    if (t instanceof StatusRuntimeException sre) {
                                        logGrpcFailure("Limit failed", (int) errCount, LIMIT_ERROR_LOG_CAP, "async", sre);
                                    } else {
                                        System.err.println("Limit failed: " + formatThrowable(t));
                                    }
                                }
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

            if (p99Millis > 100.0) {
                System.out.printf(
                        "WARNING: P99 Latency (%.3fms) Exceeded 100ms threshold! Recommended max effective RPS is likely lower than %d.%n",
                        p99Millis, targetRps);
            }
        }
    }

    private static void recordLatency(Recorder recorder, long latencyNanos) {
        long micros = Math.max(1, TimeUnit.NANOSECONDS.toMicros(latencyNanos));
        recorder.recordValue(micros);
    }

    private static void resetKeys(RateLimitServiceGrpc.RateLimitServiceBlockingStub blockingStub,
            String keyPrefix,
            KeyMode mode) {
        // NOTE: kept for backwards compatibility; prefer resetKeysWithRetry().
        ResetRequest request = ResetRequest.newBuilder().addAllKeys(keysForMode(keyPrefix, mode)).build();
        var resp = blockingStub.reset(request);
        // Optional: you can uncomment if you want visibility during manual runs.
        // System.out.printf("Reset deleted=%d%n", resp.getDeleted());
    }

    private static IntSupplier keyIndexSupplier(KeyMode mode) {
        return switch (mode) {
            case SINGLE -> () -> 0;
            case HUNDRED_KEYS -> () -> ThreadLocalRandom.current().nextInt(100);
            // 90% traffic to key 0, 10% distributed among keys 1-99
            case HUNDRED_KEYS_HOT -> () -> ThreadLocalRandom.current().nextDouble() < HOT_KEY_RATIO ? 0
                    : 1 + ThreadLocalRandom.current().nextInt(99);
        };
    }

    private static String getArg(String[] args, int index, String defaultValue) {
        if (args.length > index && args[index] != null && !args[index].isBlank()) {
            return args[index];
        }
        return defaultValue;
    }
}
