package com.waitless.review.infrastructure.adaptor.out.persistence;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.waitless.review.domain.repository.ReviewStatisticsProjection;
import com.waitless.review.domain.vo.ReviewSearchCondition;
import com.waitless.review.domain.entity.QReview;
import com.waitless.review.domain.entity.Review;
import com.waitless.review.domain.repository.ReviewRepositoryCustom;
import com.waitless.review.domain.vo.ReviewType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
@Primary
@RequiredArgsConstructor
public class ReviewRepositoryCustomImpl implements ReviewRepositoryCustom {

    private final JPAQueryFactory queryFactory;
    private static final QReview review = QReview.review;
    public static final int STATS_QUERY_CHUNK_THRESHOLD = 100;
    public static final int STATS_QUERY_CHUNK_SIZE = 50;

    @Override
    public Page<Review> searchByCondition(ReviewSearchCondition condition, Pageable pageable) {
        List<Review> results = queryFactory
                .selectFrom(review)
                .where(
                        eqUserId(condition.userId()),
                        eqRestaurantId(condition.restaurantId()),
                        eqRating(condition.rating()),
                        notDeleted()
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        long total = Optional.ofNullable(queryFactory
                .select(review.count())
                .from(review)
                .where(
                        eqUserId(condition.userId()),
                        eqRestaurantId(condition.restaurantId()),
                        eqRating(condition.rating()),
                        notDeleted()
                )
                .fetchOne()).orElse(0L);

        return new PageImpl<>(results, pageable, total);
    }

    @Override
    public Optional<Review> findOneByCondition(ReviewSearchCondition condition) {
        return Optional.ofNullable(
                queryFactory
                        .selectFrom(review)
                        .where(
                                eqReviewId(condition.reviewId()),
                                notDeleted()
                        )
                        .fetchOne()
        );
    }

    @Override
    public Map<UUID, ReviewStatisticsProjection> findStatisticsByRestaurantIds(List<UUID> restaurantIds) {
        if (restaurantIds == null || restaurantIds.isEmpty())
            return Map.of();

        List<Tuple> results = queryFactory
                .select(
                        review.restaurantId,
                        review.id.countDistinct(),
                        review.rating.ratingValue.avg()
                )
                .from(review)
                .where(
                        review.restaurantId.in(restaurantIds),
                        notDeleted()
                )
                .groupBy(review.restaurantId)
                .fetch();

        Map<UUID, ReviewStatisticsProjection> resultMap = new HashMap<>();
        for (Tuple tuple : results) {
            UUID restaurantId = tuple.get(review.restaurantId);
            long count = tuple.get(review.id.countDistinct());
            double avg = tuple.get(review.rating.ratingValue.avg());
            resultMap.put(restaurantId, new ReviewStatisticsProjection() {
                @Override
                public long getReviewCount() {
                    return count;
                }
                @Override
                public double getAverageRating() {
                    return avg;
                }
            });
        }
        return resultMap;
    }

    private BooleanExpression eqUserId(Long userId) {
        return userId != null ? review.userId.eq(userId) : null;
    }

    private BooleanExpression eqRestaurantId(UUID restaurantId) {
        return restaurantId != null ? review.restaurantId.eq(restaurantId) : null;
    }

    private BooleanExpression eqRating(Integer rating) {
        return rating != null ? review.rating.ratingValue.eq(rating) : null;
    }

    private BooleanExpression eqReviewId(UUID reviewId) {
        return reviewId != null ? review.id.eq(reviewId) : null;
    }

    private BooleanExpression notDeleted() {
        return review.type.reviewType.in(ReviewType.Type.NORMAL, ReviewType.Type.EDITED);
    }
}
