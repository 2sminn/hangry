package com.waitless.review.presentation.mapper;

import com.waitless.common.domain.UserInfoDto;
import com.waitless.review.application.dto.command.DeleteReviewCommand;
import com.waitless.review.application.dto.command.PostReviewCommand;
import com.waitless.review.application.dto.command.ReviewStatisticsCommand;
import com.waitless.review.application.dto.command.UpdateReviewCommand;
import com.waitless.review.domain.vo.ReviewSearchCondition;
import com.waitless.review.presentation.dto.request.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReviewControllerMapper {

    @Mapping(source = "requestDto.reservationId", target = "reservationId")
    @Mapping(source = "requestDto.restaurantId", target = "restaurantId")
    @Mapping(source = "requestDto.content", target = "content")
    @Mapping(source = "requestDto.rating", target = "rating")
    @Mapping(source = "userInfoDto.userId", target = "userId")
    @Mapping(source = "userInfoDto", target = "userInfoDto")
    PostReviewCommand toCommand(PostReviewRequestDto requestDto, UserInfoDto userInfoDto);

    @Mapping(source = "requestDto.reviewId", target = "reviewId")
    @Mapping(source = "userInfoDto.userId", target = "userId")
    @Mapping(source = "userInfoDto", target = "userInfoDto")
    DeleteReviewCommand toCommand(DeleteReviewRequestDto requestDto, UserInfoDto userInfoDto);

    @Mapping(source = "requestDto.reviewId", target = "reviewId")
    @Mapping(source = "requestDto.content", target = "content")
    @Mapping(source = "requestDto.rating", target = "rating")
    @Mapping(source = "userInfoDto.userId", target = "userId")
    @Mapping(source = "userInfoDto", target = "userInfoDto")
    UpdateReviewCommand toCommand(UpdateReviewRequestDto requestDto, UserInfoDto userInfoDto);

    default ReviewSearchCondition toCondition(GetReviewRequestDto requestDto) {
        return new ReviewSearchCondition(requestDto.reviewId(), null, null, null);
    }

    default ReviewSearchCondition toCondition(GetReviewListRequestDto requestDto) {
        return new ReviewSearchCondition(null, requestDto.userId(), requestDto.restaurantId(), null);
    }

    default ReviewSearchCondition toCondition(SearchReviewsRequestDto requestDto) {
        return new ReviewSearchCondition(null, requestDto.userId(), requestDto.restaurantId(), requestDto.rating());
    }

    ReviewStatisticsCommand toCommand(ReviewStatisticsBatchRequestDto requestDto, UserInfoDto userInfoDto);
}
