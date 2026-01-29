package com.waitless.review.domain.entity;

import com.waitless.common.domain.BaseTimeEntity;
import com.waitless.review.domain.vo.Rating;
import com.waitless.review.domain.vo.ReviewType;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(
    name = "p_review",
    indexes = {
        @Index(name = "idx_p_review_restaurant_type", columnList = "restaurant_id, review_type")
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Review extends BaseTimeEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID reservationId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private UUID restaurantId;

    @Embedded
    private Rating rating;

    @Embedded
    private ReviewType type;

    @Column(length = 255, nullable = false)
    private String content;

    public static Review of(UUID reservationId, Long userId, UUID restaurantId, String content, Rating rating) {
        return Review.builder()
                .id(UUID.randomUUID())
                .reservationId(reservationId)
                .userId(userId)
                .restaurantId(restaurantId)
                .content(content)
                .rating(rating)
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
    }

    public void update(String newContent, Rating newRating) {
        this.content = newContent;
        this.rating = newRating;
        this.type = ReviewType.of(ReviewType.Type.EDITED);
    }

    public void softDelete() {
        this.delete();
        this.type = ReviewType.of(ReviewType.Type.DELETED);
    }
}
