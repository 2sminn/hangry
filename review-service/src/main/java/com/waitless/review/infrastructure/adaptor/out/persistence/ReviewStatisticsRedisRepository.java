package com.waitless.review.infrastructure.adaptor.out.persistence;

import com.waitless.review.application.port.out.ReviewStatisticsCachePort;
import com.waitless.review.domain.vo.ReviewStatisticsCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ReviewStatisticsRedisRepository implements ReviewStatisticsCachePort {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String KEY_PREFIX = "review:stats:";
    private static final int PIPELINE_BATCH_SIZE = 500;

    @Override
    public void saveBatch(Map<String, ReviewStatisticsCache> data, long ttlSeconds) {
        if (data.isEmpty()) {
            return;
        }
        int total = data.size();
        if (log.isDebugEnabled()) {
            log.debug("Redis saveBatch 시작: {}건, TTL: {}s", total, ttlSeconds);
        }

        List<Map.Entry<String, ReviewStatisticsCache>> entries = new ArrayList<>(data.entrySet());
        for (int from = 0; from < entries.size(); from += PIPELINE_BATCH_SIZE) {
            int to = Math.min(from + PIPELINE_BATCH_SIZE, entries.size());
            List<Map.Entry<String, ReviewStatisticsCache>> batch = entries.subList(from, to);
            executePipelineBatch(batch, ttlSeconds);
        }

        if (log.isInfoEnabled() && total > 0) {
            log.info("Redis saveBatch 완료: {}건, TTL: {}s", total, ttlSeconds);
        }
    }

    private void executePipelineBatch(List<Map.Entry<String, ReviewStatisticsCache>> batch, long ttlSeconds) {
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisSerializer keySerializer = new StringRedisSerializer();
            GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

            for (Map.Entry<String, ReviewStatisticsCache> e : batch) {
                String key = getKey(e.getKey());
                byte[] keyBytes = keySerializer.serialize(key);
                byte[] valueBytes = valueSerializer.serialize(e.getValue());
                if (keyBytes != null && valueBytes != null) {
                    connection.stringCommands().setEx(keyBytes, ttlSeconds, valueBytes);
                }
            }
            return null;
        });
    }

    @Override
    public Map<String, ReviewStatisticsCache> findBatch(List<String> restaurantIds) {
        List<String> keys = restaurantIds.stream().map(this::getKey).toList();
        List<Object> values = redisTemplate.opsForValue().multiGet(keys);

        if (log.isDebugEnabled()) {
            log.debug("Redis MGET 결과: keys={}, values={}", keys, values);
        }

        Map<String, ReviewStatisticsCache> result = new HashMap<>();
        if (values != null) {
            for (int i = 0; i < keys.size() && i < values.size(); i++) {
                if (values.get(i) != null) {
                    result.put(restaurantIds.get(i), (ReviewStatisticsCache) values.get(i));
                }
            }
        }
        return result;
    }

    public void delete(String restaurantId) {
        redisTemplate.delete(getKey(restaurantId));
    }

    private String getKey(String restaurantId) {
        return KEY_PREFIX + restaurantId;
    }
}