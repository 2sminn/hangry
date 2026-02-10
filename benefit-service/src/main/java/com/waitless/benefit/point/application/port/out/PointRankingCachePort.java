package com.waitless.benefit.point.application.port.out;

import java.util.List;

public interface PointRankingCachePort {
    void updateRanking(Long userId, int totalPoint);

    List<Long> getTopUserIds(int topN);

    Long getMyRanking(Long userId);

    void removeUser(Long userId);
}