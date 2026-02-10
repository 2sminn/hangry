package com.waitless.review.presentation.dto.response;

import com.waitless.review.application.dto.result.GetReviewListResult;
import com.waitless.review.application.dto.command.PageCommand;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record GetReviewListResponseDto(
        List<Item> items,
        int pageNumber,
        int pageSize,
        long totalElements,
        int totalPages
) {
    public static GetReviewListResponseDto from(PageCommand<GetReviewListResult> command) {
        List<Item> items = command.content().stream()
                .map(Item::from)
                .toList();
        return new GetReviewListResponseDto(
                items,
                command.pageNumber(),
                command.pageSize(),
                command.totalElements(),
                command.totalPages()
        );
    }
    @Builder
    public record Item(
            UUID reviewId,
            Long userId,
            UUID restaurantId,
            String content,
            int rating,
            String reviewType,
            LocalDateTime createdAt
    ) {
        public static Item from(GetReviewListResult result) {
            return Item.builder()
                    .reviewId(result.reviewId())
                    .userId(result.userId())
                    .restaurantId(result.restaurantId())
                    .content(result.content())
                    .rating(result.rating().getRatingValue())
                    .reviewType(result.type().getReviewType().name())
                    .createdAt(result.createdAt())
                    .build();
        }
    }
}
