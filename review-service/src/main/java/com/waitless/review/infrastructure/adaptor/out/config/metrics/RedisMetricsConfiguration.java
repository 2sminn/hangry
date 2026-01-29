package com.waitless.review.infrastructure.adaptor.out.config.metrics;

import com.waitless.review.application.port.out.RedisMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisMetricsConfiguration implements RedisMetricsPort {

    private final MeterRegistry meterRegistry;

    // DistributionSummary 캐시 (cacheName별로 재사용)
    private final ConcurrentMap<String, DistributionSummary> batchSizeSummaryCache = new ConcurrentHashMap<>();

    // Counter 캐시 (cacheName별로 재사용 및 초기화)
    private final ConcurrentMap<String, Counter> batchRequestCounterCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> batchFullHitCounterCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> batchPartialHitCounterCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> batchFullMissCounterCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void initializeMetrics() {
        String cacheName = "review-statistics";
        // 모든 Per-Request 메트릭을 초기화 (시계열 생성)
        // increment(0)을 호출하여 시계열을 명시적으로 생성
        getOrCreateCounter("review.cache.batch.request", cacheName, batchRequestCounterCache).increment(0);
        getOrCreateCounter("review.cache.batch.full.hit", cacheName, batchFullHitCounterCache).increment(0);
        getOrCreateCounter("review.cache.batch.partial.hit", cacheName, batchPartialHitCounterCache).increment(0);
        getOrCreateCounter("review.cache.batch.full.miss", cacheName, batchFullMissCounterCache).increment(0);
        log.info("Review Cache 메트릭 초기화 완료: cacheName={}", cacheName);
    }

    private Counter getOrCreateCounter(
            String metricName, 
            String cacheName, 
            ConcurrentMap<String, Counter> cache) {
        String key = metricName + ":" + cacheName;
        return cache.computeIfAbsent(
                key,
                k -> meterRegistry.counter(metricName, "cache", cacheName)
        );
    }

    @Override
    public void increaseCacheHit(String cacheName, int count) {
        meterRegistry.counter("review.cache.hit", "cache", cacheName).increment(count);
    }
    @Override
    public void increaseCacheMiss(String cacheName, int count) {
        meterRegistry.counter("review.cache.miss", "cache", cacheName).increment(count);
    }
    @Override
    public void increaseCacheLookup(String cacheName, int count) {
        meterRegistry.counter("review.cache.lookup", "cache", cacheName).increment(count);
    }
    @Override
    public void increaseDbFallback(String cacheName, int count) {
        meterRegistry.counter("review.cache.db.fallback", "cache", cacheName).increment(count);
    }

    @Override
    public void increaseBatchRequest(String cacheName) {
        getOrCreateCounter("review.cache.batch.request", cacheName, batchRequestCounterCache).increment();
    }
    @Override
    public void increaseBatchFullHit(String cacheName) {
        getOrCreateCounter("review.cache.batch.full.hit", cacheName, batchFullHitCounterCache).increment();
    }
    @Override
    public void increaseBatchPartialHit(String cacheName) {
        getOrCreateCounter("review.cache.batch.partial.hit", cacheName, batchPartialHitCounterCache).increment();
    }
    @Override
    public void increaseBatchFullMiss(String cacheName) {
        getOrCreateCounter("review.cache.batch.full.miss", cacheName, batchFullMissCounterCache).increment();
    }
    @Override
    public void recordBatchSize(String cacheName, int batchSize) {
        DistributionSummary summary = batchSizeSummaryCache.computeIfAbsent(
                cacheName,
                name -> DistributionSummary.builder("review.cache.batch.size")
                        .tag("cache", name)
                        .publishPercentileHistogram()
                        .register(meterRegistry));
        summary.record(batchSize);
    }
}
