package com.nexora.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * RateLimiter
 * 
 * In-memory sliding window rate limiter designed to protect the server and public AI APIs
 * from spam and excessive quota exhaustion.
 * 
 * Part of NEXORA - AI-Powered Personal Chatbot
 */
public class RateLimiter {

    private final int maxRequests;
    private final long windowMillis;
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>> clientRequests;
    private volatile long lastCleanupTime;

    public RateLimiter(int maxRequestsPerMinute) {
        this.maxRequests = maxRequestsPerMinute;
        this.windowMillis = 60_000L; // 1 minute window
        this.clientRequests = new ConcurrentHashMap<>();
        this.lastCleanupTime = System.currentTimeMillis();
    }

    /**
     * Checks if a request from the given client IP is allowed under the rate limit.
     * Records the timestamp if allowed.
     */
    public boolean isAllowed(String clientIp) {
        if (clientIp == null || clientIp.trim().isEmpty()) {
            clientIp = "unknown";
        }

        long now = System.currentTimeMillis();
        long threshold = now - windowMillis;

        // Periodic cleanup of stale IPs every 5 minutes to prevent memory leaks
        if (now - lastCleanupTime > 300_000L) {
            cleanupStaleEntries(threshold);
            lastCleanupTime = now;
        }

        ConcurrentLinkedQueue<Long> timestamps = clientRequests.computeIfAbsent(clientIp, k -> new ConcurrentLinkedQueue<>());

        // Remove timestamps older than the sliding window
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peek() < threshold) {
                timestamps.poll();
            }

            if (timestamps.size() >= maxRequests) {
                return false; // Rate limit exceeded
            }

            timestamps.add(now);
            return true;
        }
    }

    /**
     * Gets the number of requests made by this IP within the current sliding window.
     */
    public int getCurrentRequestCount(String clientIp) {
        if (clientIp == null) return 0;
        ConcurrentLinkedQueue<Long> timestamps = clientRequests.get(clientIp);
        if (timestamps == null) return 0;

        long threshold = System.currentTimeMillis() - windowMillis;
        int count = 0;
        for (Long t : timestamps) {
            if (t >= threshold) {
                count++;
            }
        }
        return count;
    }

    private void cleanupStaleEntries(long threshold) {
        clientRequests.entrySet().removeIf(entry -> {
            ConcurrentLinkedQueue<Long> q = entry.getValue();
            synchronized (q) {
                while (!q.isEmpty() && q.peek() < threshold) {
                    q.poll();
                }
                return q.isEmpty();
            }
        });
    }
}
