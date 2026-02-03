package com.example.ratelimit.client;

public class ClusterScalabilityBenchmark {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Testing cluster with 2 app instances via Envoy - Higher RPS");

        // Test points:
        // 10k, 20k (Baseline)
        // 30k (Previous Limit)
        // 45k (1.5x)
        // 60k (2x - Theoretical limit for 3 nodes if perfectly linear)
        int[] targetRps = { 10_000, 15_000, 20_000, 25_000, 30_000, 35_000, 40_000, 45_000, 50_000 };
        BenchmarkClient.run(args, BenchmarkClient.KeyMode.HUNDRED_KEYS, targetRps);
    }
}
