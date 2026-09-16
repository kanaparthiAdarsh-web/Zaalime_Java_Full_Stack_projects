package com.engine.metrics;

public class LatencyTracker {
    private long totalLatencyNanos = 0;
    private long processedCount = 0;
    private long maxLatencyNanos = 0;
    private long minLatencyNanos = Long.MAX_VALUE;

    public void recordLatency(long startTimeNanos) {
        long latency = System.nanoTime() - startTimeNanos;
        totalLatencyNanos += latency;
        processedCount++;
        
        if (latency > maxLatencyNanos) {
            maxLatencyNanos = latency;
        }
        if (latency < minLatencyNanos) {
            minLatencyNanos = latency;
        }
    }

    public void printStats() {
        if (processedCount == 0) return;
        long avgLatency = totalLatencyNanos / processedCount;
        System.out.println("--- Latency Metrics ---");
        System.out.println("Total Orders Processed: " + processedCount);
        System.out.println("Average Latency: " + avgLatency + " ns (" + (avgLatency / 1000.0) + " µs)");
        System.out.println("Max Latency: " + maxLatencyNanos + " ns");
        System.out.println("Min Latency: " + minLatencyNanos + " ns");
    }
}