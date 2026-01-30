package com.waitless.review.application.port.out;

public interface RedisMetricsPort {
    void increaseCacheHit(String cacheName, int count);
    void increaseCacheMiss(String cacheName, int count);
    void increaseCacheLookup(String cacheName, int count);
    void increaseDbFallback(String cacheName, int count);

    void increaseBatchRequest(String cacheName);
    void increaseBatchFullHit(String cacheName);
    void increaseBatchPartialHit(String cacheName);
    void increaseBatchFullMiss(String cacheName);
    void recordBatchSize(String cacheName, int batchSize);
}