package com.waitless.benefit.point.infrastructure.adaptor.out.persistence;

import com.waitless.benefit.point.application.port.out.PointRankingCachePort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class PointRankingRedisRepository implements PointRankingCachePort {

    private final RedisTemplate<String, Object> pointRedisTemplate;
    private static final String RANKING_KEY = "point:ranking:global";

    @Override
    public void updateRanking(Long userId, int totalPoint) {
        pointRedisTemplate.opsForZSet().add(RANKING_KEY, userId, totalPoint);
    }

    @Override
    public List<Long> getTopUserIds(int topN) {
        Set<Object> userIds = pointRedisTemplate.opsForZSet()
                .reverseRange(RANKING_KEY, 0, topN - 1);

        if (userIds == null) return List.of();
        return userIds.stream()
                .map(id -> {
                    if (id instanceof Long) return (Long) id;
                    if (id instanceof Integer) return ((Integer) id).longValue();
                    return null;
                })
                .filter(id -> id != null)
                .collect(Collectors.toList());
    }

    @Override
    public Long getMyRanking(Long userId) {
        Long rank = pointRedisTemplate.opsForZSet().reverseRank(RANKING_KEY, userId);
        return rank != null ? rank + 1 : null; // 0-based → 1-based 변환
    }

    @Override
    public void removeUser(Long userId) {
        pointRedisTemplate.opsForZSet().remove(RANKING_KEY, userId);
    }
}
