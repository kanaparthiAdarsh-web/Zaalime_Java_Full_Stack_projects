package com.zaalime.rag.web;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-client token bucket. Every /ask can cost an LLM call, so it is limited per IP. */
public class RateLimiter {

    private record Bucket(double tokens, long updatedNanos) {
    }

    private final double capacity;
    private final double perNano;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this.capacity = perMinute;
        this.perNano = perMinute / 60e9;
    }

    /** @return 0 when allowed, otherwise the seconds to wait before retrying */
    public long tryAcquire(String client) {
        if (capacity <= 0) {
            return 0;
        }
        long now = System.nanoTime();
        if (buckets.size() > 10_000) {
            prune(now);
        }
        long[] wait = {0};
        buckets.compute(client, (k, b) -> {
            double tokens = b == null ? capacity : Math.min(capacity, b.tokens() + (now - b.updatedNanos()) * perNano);
            if (tokens >= 1) {
                return new Bucket(tokens - 1, now);
            }
            wait[0] = Math.max(1, (long) Math.ceil((1 - tokens) / perNano / 1e9));
            return new Bucket(tokens, now);
        });
        return wait[0];
    }

    // a bucket idle long enough to be full again carries no state worth keeping
    private void prune(long now) {
        double refillNanos = capacity / perNano;
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext(); ) {
            if (now - it.next().updatedNanos() > refillNanos) {
                it.remove();
            }
        }
    }
}
