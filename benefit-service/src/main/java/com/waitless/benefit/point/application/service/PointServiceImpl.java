package com.waitless.benefit.point.application.service;

import com.waitless.benefit.point.application.dto.command.PostPointCommand;
import com.waitless.benefit.point.application.dto.result.PostPointResult;
import com.waitless.benefit.point.application.mapper.PointServiceMapper;
import com.waitless.benefit.point.application.port.in.PointCommandUseCase;
import com.waitless.benefit.point.application.port.out.PointOutboxPort;
import com.waitless.benefit.point.application.port.out.PointRankingCachePort;
import com.waitless.benefit.point.application.port.out.PointStatisticsCachePort;
import com.waitless.benefit.point.domain.entity.Point;
import com.waitless.benefit.point.domain.repository.PointRepository;
import com.waitless.benefit.point.domain.repository.PointRepositoryCustom;
import com.waitless.common.event.PointIssuedEvent;
import com.waitless.common.event.PointIssuedFailedEvent;
import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PointServiceImpl implements PointCommandUseCase {

    private final PointRepository pointRepository;
    private final PointRepositoryCustom pointRepositoryCustom;
    private final PointServiceMapper pointServiceMapper;
    private final PointOutboxPort pointOutboxPort;
    private final PointRankingCachePort pointRankingCachePort;
    private final PointStatisticsCachePort pointStatisticsCachePort;

    @Override
    @Transactional
    public PostPointResult createPoint(PostPointCommand command) {
        if (pointRepository.existsByUserIdAndReservationId(command.userId(), command.reservationId())) {
            log.warn("중복 포인트 적립 시도: userId={}, reservationId={}", command.userId(), command.reservationId());
            PointIssuedFailedEvent event = PointIssuedFailedEvent.builder()
                    .reviewId(command.reviewId())
                    .userId(command.userId())
                    .reservationId(command.reservationId())
                    .build();
            pointOutboxPort.savePointIssuedFailedEvent(event);
            return null;
        }
        Point point = pointServiceMapper.toEntity(command);
        Point saved = pointRepository.save(point);

        pointStatisticsCachePort.deleteAmount(saved.getUserId()); // 총합 캐시 삭제
        pointStatisticsCachePort.deleteMyRanking(saved.getUserId()); // 개인 랭킹 캐시 삭제
        // ZSET에는 누적 총합으로 반영 (마지막 값이 아닌 DB 기준 총합)
        int cumulativeTotal = pointRepositoryCustom.getTotalPointByUserId(saved.getUserId());
        pointRankingCachePort.updateRanking(saved.getUserId(), cumulativeTotal);

        PointIssuedEvent event = PointIssuedEvent.builder()
                .pointId(saved.getId())
                .userId(saved.getUserId())
                .reviewId(saved.getReviewId())
                .reservationId(saved.getReservationId())
                .amount(saved.getAmount().getPointValue())
                .description(saved.getDescription())
                .issuedAt(saved.getCreatedAt())
                .build();
        pointOutboxPort.savePointIssuedEvent(event);
        return PostPointResult.from(saved);
    }

    @Override
    @Transactional
    public void deletePointByReview(UUID reviewId, Long userId) {
        Point point = pointRepository.findByReviewIdAndUserId(reviewId, userId)
                .orElseThrow(() -> BusinessException.from(CommonErrorCode.NOT_FOUND));
        point.softDelete();

        pointStatisticsCachePort.deleteAmount(userId); // 총합 캐시 삭제
        pointStatisticsCachePort.deleteMyRanking(userId); // 개인 랭킹 캐시 삭제
        // 삭제 후 남은 누적 총합 반영: 남은 포인트가 있으면 ZSET 점수 갱신, 없으면 ZSET에서 제거
        int remainingTotal = pointRepositoryCustom.getTotalPointByUserId(userId);
        if (remainingTotal > 0) {
            pointRankingCachePort.updateRanking(userId, remainingTotal);
        } else {
            pointRankingCachePort.removeUser(userId);
        }
    }
}
