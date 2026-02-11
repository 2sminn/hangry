package com.waitless.review.application.dto.result;

import com.waitless.review.domain.entity.Review;
import com.waitless.review.domain.vo.Rating;
import com.waitless.review.domain.vo.ReviewType;

import java.time.LocalDateTime;
import java.util.UUID;

public record UpdateReviewResult(
        UUID reviewId,
        UUID reservationId,
        Long userId,
        UUID restaurantId,
        String content,
        Rating rating,
        ReviewType reviewType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static UpdateReviewResult from(Review review) {
        return new UpdateReviewResult(
                review.getId(),
                review.getReservationId(),
                review.getUserId(),
                review.getRestaurantId(),
                review.getContent(),
                review.getRating(),
                review.getType(),
                review.getCreatedAt(),
                review.getUpdatedAt());
    }
}