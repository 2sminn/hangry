package com.waitless.benefit.point.application.service;

import com.waitless.benefit.point.application.dto.command.GetMyRankingCommand;
import com.waitless.benefit.point.application.dto.command.GetPointAmountCommand;
import com.waitless.benefit.point.application.dto.command.PageCommand;
import com.waitless.benefit.point.application.dto.command.SearchRankingCommand;
import com.waitless.benefit.point.application.dto.result.*;
import com.waitless.benefit.point.application.port.out.PointRankingCachePort;
import com.waitless.benefit.point.application.service.cache.PointQueryCache;
import com.waitless.benefit.point.domain.entity.Point;
import com.waitless.benefit.point.domain.repository.PointRepositoryCustom;
import com.waitless.benefit.point.domain.vo.PointSearchCondition;
import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointQueryServiceImpl implements PointQueryService {

    private final PointRepositoryCustom pointRepositoryCustom;
    private final PointRankingCachePort pointRankingCachePort;
    private final PointQueryCache pointQueryCache;

    @Override
    @Transactional(readOnly = true)
    public GetPointResult findOne(PointSearchCondition condition) {
        return pointRepositoryCustom.findByReviewId(condition.reviewId())
                .map(GetPointResult::from)
                .orElseThrow(() -> BusinessException.from(CommonErrorCode.NOT_FOUND));
    }

    @Override
    @Transactional(readOnly = true)
    public PageCommand<GetPointListResult> findList(PointSearchCondition condition, Pageable pageable) {
        Page<Point> pointPage = pointRepositoryCustom.findAllByUserId(condition.userId(), pageable);
        return GetPointListResult.toPageCommand(pointPage);
    }

    @Override
    @Transactional(readOnly = true)
    public GetPointAmountResult getTotalAmount(GetPointAmountCommand command) {
        return pointQueryCache.getTotalPoint(command.userId());
    }

    @Override
    @Transactional(readOnly = true)
    public PageCommand<SearchRankingResult> getTopRanking(SearchRankingCommand command) {
        // 1. Redis ZSET에서 TopN userIds 조회
        List<Long> topUserIds = pointRankingCachePort.getTopUserIds(command.topN());
        if (topUserIds.isEmpty()) {
            throw BusinessException.from(CommonErrorCode.NOT_FOUND);
        }
        // 2. 각각 유저별 포인트 조회하고, 순위 부여
        List<SearchRankingResult> rankingResults = topUserIds.stream()
                .map(userId -> {
                    int totalPoint = pointRepositoryCustom.getTotalPointByUserId(userId);
                    int rank = pointRankingCachePort.getMyRanking(userId).intValue(); // 0-based → 1-based
                    return SearchRankingResult.of(userId, totalPoint, rank);
                })
                .toList();
        Page<SearchRankingResult> page = new PageImpl<>(rankingResults);
        return SearchRankingResult.toPageCommand(page);
    }

    @Override
    @Transactional(readOnly = true)
    public GetMyRankingResult getMyRanking(GetMyRankingCommand command) {
        return pointQueryCache.getMyRanking(command.userId());
    }
}