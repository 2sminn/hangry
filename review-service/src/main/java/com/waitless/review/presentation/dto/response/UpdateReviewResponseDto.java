package com.waitless.review.presentation.dto.response;

import com.waitless.review.application.dto.result.UpdateReviewResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record UpdateReviewResponseDto(
        UUID reviewId,
        UUID reservationId,
        Long userId,
        UUID restaurantId,
        String content,
        int rating,
        String reviewType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
    public static UpdateReviewResponseDto from(UpdateReviewResult result) {
        return new UpdateReviewResponseDto(
                result.reviewId(),
                result.reservationId(),
                result.userId(),
                result.restaurantId(),
                result.content(),
                result.rating().getRatingValue(),
                result.reviewType().getReviewType().name(),
                result.createdAt(),
                result.updatedAt());
    }
}