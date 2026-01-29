package com.waitless.review.application.service.cache;

import com.waitless.review.application.dto.result.ReviewStatisticsResult;
import com.waitless.review.application.port.out.RedisMetricsPort;
import com.waitless.review.application.port.out.ReviewStatisticsCachePort;
import com.waitless.review.domain.repository.ReviewRepositoryCustom;
import com.waitless.review.domain.repository.ReviewStatisticsProjection;
import com.waitless.review.domain.vo.ReviewStatisticsCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.waitless.common.constants.ReviewCacheConstants.LOG_PREFIX;
import static com.waitless.common.constants.ReviewCacheConstants.TTL_SECONDS;

@Slf4j
@Service
public class ReviewBatchCacheImpl implements ReviewBatchCache {
    private static final int DB_QUERY_CHUNK_THRESHOLD = 100;
    private static final int DB_QUERY_CHUNK_SIZE = 50;

    private final ReviewRepositoryCustom reviewRepositoryCustom;
    private final ReviewStatisticsCachePort reviewStatisticsCachePort;
    private final RedisMetricsPort redisMetricsPort;
    private final Executor cacheSaveExecutor;

    public ReviewBatchCacheImpl(
            ReviewRepositoryCustom reviewRepositoryCustom,
            ReviewStatisticsCachePort reviewStatisticsCachePort,
            RedisMetricsPort redisMetricsPort,
            @Qualifier("cacheSaveExecutor") Executor cacheSaveExecutor) {
        this.reviewRepositoryCustom = reviewRepositoryCustom;
        this.reviewStatisticsCachePort = reviewStatisticsCachePort;
        this.redisMetricsPort = redisMetricsPort;
        this.cacheSaveExecutor = cacheSaveExecutor;
    }

    @Override
    public Map<UUID, ReviewStatisticsResult> getBatch(List<UUID> restaurantIds) {
        if (restaurantIds == null || restaurantIds.isEmpty())
            return Map.of();

        Map<UUID, ReviewStatisticsResult> resultMap = new HashMap<>();

        // 배치 요청 수 메트릭 기록 (per-request)
        redisMetricsPort.increaseBatchRequest("review-statistics");
        redisMetricsPort.recordBatchSize("review-statistics", restaurantIds.size());

        // 총 조회 시도 횟수 메트릭 기록 (per-item)
        int totalLookupCount = restaurantIds.size();
        redisMetricsPort.increaseCacheLookup("review-statistics", totalLookupCount);

        // Redis 캐시 조회
        Map<String, ReviewStatisticsCache> cachedMap = getCachedStatistics(restaurantIds);
        log.debug("{} Batch HIT: {}", LOG_PREFIX, cachedMap.keySet());

        // 캐시 Hit 처리 및 메트릭 기록 (per-item)
        int hitCount = addCacheHitsToResult(restaurantIds, cachedMap, resultMap);
        if (hitCount > 0) {
            redisMetricsPort.increaseCacheHit("review-statistics", hitCount);
        }

        // Miss ID 계산
        List<UUID> missedIds = findMissedIds(restaurantIds, resultMap);
        log.debug("{} Batch MISS: {}", LOG_PREFIX, missedIds);

        // MISS 수에 대해 메트릭 증가 (per-item)
        int missCount = missedIds.size();
        if (missCount > 0) {
            redisMetricsPort.increaseCacheMiss("review-statistics", missCount);
        }

        // DB Fallback 발생 시 메트릭 기록 (per-item)
        if (missCount > 0) {
            redisMetricsPort.increaseDbFallback("review-statistics", missCount);
        }

        // Per-Request 기반 메트릭 기록 (운영 안정성 관점)
        if (missCount == 0) {
            // 전체 Hit (DB 조회 없음)
            redisMetricsPort.increaseBatchFullHit("review-statistics");
        } else if (hitCount > 0 && missCount > 0) {
            // 부분 Hit (일부는 Hit, 일부는 Miss)
            log.debug("{} Partial Hit 발생: hitCount={}, missCount={}, batchSize={}",
                    LOG_PREFIX, hitCount, missCount, restaurantIds.size());
            redisMetricsPort.increaseBatchPartialHit("review-statistics");
        } else {
            // 전체 Miss (Hit 없음)
            redisMetricsPort.increaseBatchFullMiss("review-statistics");
        }

        // Full Hit면 DB/캐시 저장 없이 즉시 반환 (불필요한 DB 호출 회피)
        if (missedIds.isEmpty()) {
            return resultMap;
        }

        // DB에서 조회 → 결과만 반영 후 즉시 반환, 캐시 저장은 비동기
        Map<UUID, ReviewStatisticsProjection> dbResults = getStatisticsFromDatabase(missedIds);
        updateResults(dbResults, resultMap, missedIds);

        int missedCount = missedIds.size();
        Map<String, ReviewStatisticsCache> cacheMap = convertToCacheMap(dbResults, missedIds);
        if (!cacheMap.isEmpty()) {
            CompletableFuture.runAsync(() -> {
                try {
                    saveCacheBatch(cacheMap);
                } catch (Exception e) {
                    log.warn("{} 캐시 비동기 저장 실패: missedCount={}, cacheMapSize={}, cause={}",
                            LOG_PREFIX, missedCount, cacheMap.size(), e.getMessage());
                }
            }, cacheSaveExecutor);
        }

        return resultMap;
    }

    // 캐시에서 조회
    private Map<String, ReviewStatisticsCache> getCachedStatistics(List<UUID> restaurantIds) {
        List<String> ids = restaurantIds.stream().map(UUID::toString).toList();
        return reviewStatisticsCachePort.findBatch(ids);
    }

    // 캐시 Hit 처리
    private int addCacheHitsToResult(
            List<UUID> restaurantIds,
            Map<String, ReviewStatisticsCache> cachedMap,
            Map<UUID, ReviewStatisticsResult> resultMap) {
        int hitCount = 0;
        for (UUID id : restaurantIds) {
            ReviewStatisticsCache cached = cachedMap.get(id.toString());
            if (cached != null) {
                hitCount++;
                resultMap.put(id, ReviewStatisticsResult.from(cached.getReviewCount(), cached.getAverageRating()));
            }
        }
        return hitCount;
    }

    // 캐시 MissId만 추출
    private List<UUID> findMissedIds(List<UUID> allIds, Map<UUID, ReviewStatisticsResult> resultMap) {
        return allIds.stream()
                .filter(id -> !resultMap.containsKey(id))
                .toList();
    }

    // DB 집계 조회. 목록이 클 경우 청크 단위로 나눠 조회 후 병합
    private Map<UUID, ReviewStatisticsProjection> getStatisticsFromDatabase(List<UUID> missedIds) {
        if (missedIds == null || missedIds.isEmpty()) {
            return Map.of();
        }
        if (missedIds.size() <= DB_QUERY_CHUNK_THRESHOLD) {
            return reviewRepositoryCustom.findStatisticsByRestaurantIds(missedIds);
        }
        return getStatisticsInChunks(missedIds, DB_QUERY_CHUNK_SIZE);
    }

    // 청크별 조회 결과는 restaurant_id 기준이므로 키 중복 없음. putAll 병합만 하면 됨
    private Map<UUID, ReviewStatisticsProjection> getStatisticsInChunks(List<UUID> missedIds, int chunkSize) {
        Map<UUID, ReviewStatisticsProjection> resultMap = new HashMap<>();
        for (int i = 0; i < missedIds.size(); i += chunkSize) {
            List<UUID> chunk = missedIds.subList(i, Math.min(i + chunkSize, missedIds.size()));
            Map<UUID, ReviewStatisticsProjection> chunkResults =
                    reviewRepositoryCustom.findStatisticsByRestaurantIds(chunk);
            resultMap.putAll(chunkResults);
        }
        return resultMap;
    }

    // 결과맵만 채움 (캐시 저장 없음). 응답 반환을 위해 동기 처리.
    private void updateResults(
            Map<UUID, ReviewStatisticsProjection> dbResults,
            Map<UUID, ReviewStatisticsResult> resultMap,
            List<UUID> missedIds) {
        dbResults.forEach((id, projection) -> resultMap.put(id,
                ReviewStatisticsResult.from(projection.getReviewCount(), projection.getAverageRating())));
        missedIds.forEach(id -> {
            if (!resultMap.containsKey(id)) {
                log.debug("{} DB 결과 없음 → 기본값 처리: restaurantId={}", LOG_PREFIX, id);
                resultMap.put(id, ReviewStatisticsResult.from(0L, 0.0));
            }
        });
    }

    // 비동기 캐시 저장용 맵만 생성 (resultMap 갱신 없음).
    private Map<String, ReviewStatisticsCache> convertToCacheMap(
            Map<UUID, ReviewStatisticsProjection> dbResults,
            List<UUID> missedIds) {
        Map<String, ReviewStatisticsCache> cacheToSave = new HashMap<>();
        dbResults.forEach((id, projection) -> cacheToSave.put(id.toString(),
                ReviewStatisticsCache.builder()
                        .reviewCount(projection.getReviewCount())
                        .averageRating(projection.getAverageRating())
                        .build()));
        missedIds.stream()
                .filter(id -> !dbResults.containsKey(id))
                .forEach(id -> cacheToSave.put(id.toString(),
                        ReviewStatisticsCache.builder().reviewCount(0L).averageRating(0.0).build()));
        return cacheToSave;
    }

    // Redis 캐시에 저장. 키별 로그 제거 — 배치 시 로그 스팸·성능 영향 방지.
    private void saveCacheBatch(Map<String, ReviewStatisticsCache> cacheToSave) {
        if (cacheToSave == null || cacheToSave.isEmpty()) {
            return;
        }
        log.info("{} 캐시 저장 요청 → 총 {}건", LOG_PREFIX, cacheToSave.size());
        reviewStatisticsCachePort.saveBatch(cacheToSave, TTL_SECONDS);
    }
}