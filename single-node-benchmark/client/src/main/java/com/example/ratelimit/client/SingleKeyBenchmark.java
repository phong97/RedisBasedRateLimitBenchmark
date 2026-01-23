package com.example.ratelimit.client;

public class SingleKeyBenchmark {
    public static void main(String[] args) throws InterruptedException {
//        int[] targetRps = {10_000, 100_000, 300_000, 500_000, 1_000_000};
        int[] targetRps = {10_000};
        BenchmarkClient.run(args, BenchmarkClient.KeyMode.SINGLE, targetRps);
    }
}
