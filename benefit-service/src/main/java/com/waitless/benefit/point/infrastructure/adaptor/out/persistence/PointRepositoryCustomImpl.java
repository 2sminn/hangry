package com.waitless.benefit.point.infrastructure.adaptor.out.persistence;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.waitless.benefit.point.domain.entity.Point;
import com.waitless.benefit.point.domain.entity.QPoint;
import com.waitless.benefit.point.domain.repository.PointRepositoryCustom;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PointRepositoryCustomImpl implements PointRepositoryCustom {

    private final JPAQueryFactory queryFactory;
    private static final QPoint point = QPoint.point;

    @Override
    public Page<Point> findAllByUserId(Long userId, Pageable pageable) {
        List<Point> results = queryFactory
                .selectFrom(point)
                .where(
                        eqUserId(userId),
                        notDeleted()
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        long total = Optional.ofNullable(queryFactory
                .select(point.count())
                .from(point)
                .where(
                        eqUserId(userId),
                        notDeleted()
                )
                .fetchOne()).orElse(0L);

        return new PageImpl<>(results, pageable, total);
    }
    @Override
    public Optional<Point> findByReviewId(UUID reviewId) {
        return Optional.ofNullable(
                queryFactory
                        .selectFrom(point)
                        .where(
                                eqReviewId(reviewId),
                                notDeleted()
                        )
                        .fetchOne()
        );
    }
    @Override
    public int getTotalPointByUserId(Long userId) {
        PointStatisticsProjectionDto result = queryFactory
                .select(Projections.fields(
                        PointStatisticsProjectionDto.class,
                        point.userId.as("userId"),
                        point.amount.pointValue.sum().as("totalPoint")
                ))
                .from(point)
                .where(
                        eqUserId(userId),
                        notDeleted()
                )
                .groupBy(point.userId)
                .fetchOne();

        return result != null && result.getTotalPoint() != null ? result.getTotalPoint() : 0;
    }
     /*미사용 메소드 - Redis ZSET으로 대체됨
    @Override
    public int getUserRanking(Long userId) {
        List<PointStatisticsProjection> userTotals = queryFactory
                .select(Projections.fields(
                        PointStatisticsProjection.class,
                        point.userId.as("userId"),
                        point.amount.pointValue.sum().as("totalPoint")
                ))
                .from(point)
                .where(notDeleted())
                .groupBy(point.userId)
                .fetch();

        userTotals.sort(Comparator.comparing(PointStatisticsProjection::getTotalPoint).reversed());
        int rank = -1;
        for (int i = 0; i < userTotals.size(); i++) {
            if (userTotals.get(i).getUserId().equals(userId)) {
                rank = i + 1;
                break;
            }
        }
        return rank > 0 ? rank : 0;
    } */

    private BooleanExpression eqUserId(Long userId) {
        return userId != null ? point.userId.eq(userId) : null;
    }

    private BooleanExpression eqReviewId(UUID reviewId) {
        return reviewId != null ? point.reviewId.eq(reviewId) : null;
    }

    private BooleanExpression notDeleted() {
        return point.isDeleted.isFalse();
    }
}
