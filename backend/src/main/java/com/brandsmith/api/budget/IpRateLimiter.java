package com.brandsmith.api.budget;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IpRateLimiter {

    private final double capacity;
    private final double refillPerMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public IpRateLimiter(@Value("${brandsmith.rate-limit.create-capacity:10}") int capacity,
                         @Value("${brandsmith.rate-limit.create-refill-per-minute:10}") int refillPerMinute) {
        this.capacity = capacity;
        this.refillPerMinute = refillPerMinute;
    }

    public boolean tryAcquire(String ip) {
        Bucket bucket = buckets.computeIfAbsent(ip, k -> new Bucket(capacity));
        synchronized (bucket) {
            refill(bucket);
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return true;
            }
            return false;
        }
    }

    private void refill(Bucket bucket) {
        long now = System.nanoTime();
        double minutes = (now - bucket.lastNanos) / 60e9;
        bucket.tokens = Math.min(capacity, bucket.tokens + minutes * refillPerMinute);
        bucket.lastNanos = now;
    }

    private static final class Bucket {
        double tokens;
        long lastNanos = System.nanoTime();

        Bucket(double tokens) {
            this.tokens = tokens;
        }
    }
}
