package com.waitless.review.application.dto.command;

import com.waitless.common.domain.UserInfoDto;

import java.util.UUID;

public record UpdateReviewCommand(
                UUID reviewId,
                Long userId,
                String content,
                Integer rating,
                UserInfoDto userInfoDto) {
}