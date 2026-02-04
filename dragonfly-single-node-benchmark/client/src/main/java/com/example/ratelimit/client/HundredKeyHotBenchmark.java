package com.example.ratelimit.client;

public class HundredKeyHotBenchmark {
    public static void main(String[] args) throws InterruptedException {
        int[] targetRps = { 10_000, 12_500, 15_000, 17_500, 20_000, 22_500, 25_000, 27_500, 30_000 };
        BenchmarkClient.run(args, BenchmarkClient.KeyMode.HUNDRED_KEYS_HOT, targetRps);
    }
}
