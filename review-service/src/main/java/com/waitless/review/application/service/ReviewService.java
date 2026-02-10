package com.waitless.review.application.service;

import com.waitless.review.application.dto.command.DeleteReviewCommand;
import com.waitless.review.application.dto.command.PageCommand;
import com.waitless.review.application.dto.command.PostReviewCommand;
import com.waitless.review.application.dto.command.ReviewStatisticsCommand;
import com.waitless.review.application.dto.command.UpdateReviewCommand;
import com.waitless.review.application.dto.result.*;
import com.waitless.review.domain.vo.ReviewSearchCondition;
import org.springframework.data.domain.Pageable;

import java.util.Map;
import java.util.UUID;

public interface ReviewService {
    PostReviewResult createReview(PostReviewCommand command);

    UpdateReviewResult updateReview(UpdateReviewCommand command);

    DeleteReviewResult deleteReview(DeleteReviewCommand command);

    GetReviewResult findOne(ReviewSearchCondition condition); // 단건 조회

    PageCommand<GetReviewListResult> findList(ReviewSearchCondition condition, Pageable pageable); // 리스트 조회

    PageCommand<SearchReviewsResult> findSearch(ReviewSearchCondition condition, Pageable pageable); // 조건 조회

    Map<UUID, ReviewStatisticsResult> getStatisticsBatch(ReviewStatisticsCommand command); // 배치형 통계 조회
}
