package com.waitless.review.presentation.dto.request;

import jakarta.validation.constraints.*;

import java.util.UUID;

public record UpdateReviewRequestDto(
                @NotNull(message = "reviewId는 필수입니다.") UUID reviewId,
                @NotBlank(message = "리뷰 내용은 필수입니다.") @Size(max = 255, message = "리뷰 내용은 최대 255자까지 입력 가능합니다.") String content,
                @NotNull(message = "평점은 필수입니다.") @Min(value = 1, message = "평점은 최소 1점 이상이어야 합니다.") @Max(value = 5, message = "평점은 최대 5점 이하여야 합니다.") Integer rating) {
}