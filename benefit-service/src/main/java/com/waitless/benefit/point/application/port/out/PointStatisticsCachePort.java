package com.waitless.benefit.point.application.port.out;

import com.waitless.benefit.point.domain.vo.PointAmountCache;
import com.waitless.benefit.point.domain.vo.PointRankingCache;

public interface PointStatisticsCachePort {
    void saveAmount(Long userId, PointAmountCache cache, long ttlSeconds);
    PointAmountCache findAmount(Long userId);

    void saveMyRanking(Long userId, PointRankingCache cache, long ttlSeconds);
    PointRankingCache findMyRanking(Long userId);

    void deleteAmount(Long userId);
    void deleteMyRanking(Long userId);
}