package com.waitless.review.application.service;

import com.waitless.common.command.CancelReviewCommand;
import com.waitless.common.domain.Role;
import com.waitless.common.domain.UserInfoDto;
import com.waitless.common.event.ReviewCreatedEvent;
import com.waitless.common.event.ReviewDeletedEvent;
import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
import com.waitless.review.application.dto.command.DeleteReviewCommand;
import com.waitless.review.application.dto.command.PostReviewCommand;
import com.waitless.review.application.dto.command.UpdateReviewCommand;
import com.waitless.review.application.dto.result.DeleteReviewResult;
import com.waitless.review.application.dto.result.PostReviewResult;
import com.waitless.review.application.dto.result.UpdateReviewResult;
import com.waitless.review.application.mapper.ReviewServiceMapper;
import com.waitless.review.application.port.out.ReviewOutboxPort;
import com.waitless.review.application.port.out.ReviewStatisticsCachePort;
import com.waitless.review.application.service.cache.ReviewBatchCache;
import com.waitless.review.application.validator.VisitedReservationValidator;
import com.waitless.review.domain.entity.Review;
import com.waitless.review.domain.repository.ReviewRepository;
import com.waitless.review.domain.repository.ReviewRepositoryCustom;
import com.waitless.review.domain.vo.Rating;
import com.waitless.review.domain.vo.ReviewType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewServiceImpl 단위 테스트")
class ReviewServiceImplTest {
    @Mock
    private ReviewRepository reviewRepository;

    @Mock
    private ReviewServiceMapper reviewServiceMapper;

    @Mock
    private ReviewOutboxPort reviewOutboxPort;

    @Mock
    private ReviewRepositoryCustom reviewRepositoryCustom;

    @Mock
    private VisitedReservationValidator visitedReservationValidator;

    @Mock
    private ReviewStatisticsCachePort reviewStatisticsCachePort;

    @Mock
    private ReviewBatchCache reviewBatchCache;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID RESTAURANT_ID = UUID.randomUUID();
    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final UserInfoDto USER_INFO_DTO = new UserInfoDto(USER_ID, Role.USER);

    @Test
    @DisplayName("리뷰 생성 성공 테스트")
    void createReview_성공() {
        // given
        PostReviewCommand command = new PostReviewCommand(RESERVATION_ID, USER_ID, RESTAURANT_ID, "맛있어요!", 5, USER_INFO_DTO);

        Review review = Review.builder().id(REVIEW_ID).reservationId(RESERVATION_ID).userId(USER_ID).restaurantId(RESTAURANT_ID).content("맛있어요!").rating(Rating.of(5)).type(ReviewType.of(ReviewType.Type.NORMAL)).build();

        when(reviewRepository.existsByReservationId(RESERVATION_ID)).thenReturn(false);
        when(reviewServiceMapper.toEntity(command)).thenReturn(review);
        when(reviewRepository.save(any(Review.class))).thenReturn(review);

        // when
        PostReviewResult result = reviewService.createReview(command);

        // then
        assertThat(result).isNotNull();
        assertThat(result.reviewId()).isEqualTo(REVIEW_ID);
        assertThat(result.content()).isEqualTo("맛있어요!");
        assertThat(result.rating().getRatingValue()).isEqualTo(5);
        assertThat(result.reviewType().getReviewType()).isEqualTo(ReviewType.Type.NORMAL);

        verify(visitedReservationValidator).validate(command);
        verify(reviewRepository).existsByReservationId(RESERVATION_ID);
        verify(reviewServiceMapper).toEntity(command);
        verify(reviewRepository).save(any(Review.class));
        ArgumentCaptor<String> cacheKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(reviewStatisticsCachePort).delete(cacheKeyCaptor.capture());
        assertThat(cacheKeyCaptor.getValue()).isEqualTo(RESTAURANT_ID.toString());
        verify(reviewOutboxPort).saveReviewCreatedEvent(any(ReviewCreatedEvent.class));
    }

    @Test
    @DisplayName("리뷰 생성 실패 - 중복 예약 리뷰 (같은 예약에 대한 리뷰 이미 존재)")
    void createReview_중복예약() {
        // given
        PostReviewCommand command = new PostReviewCommand(RESERVATION_ID, USER_ID, RESTAURANT_ID, "맛있어요!", 5, USER_INFO_DTO);

        doNothing().when(visitedReservationValidator).validate(command);
        when(reviewRepository.existsByReservationId(RESERVATION_ID)).thenReturn(true);

        // when & then
        assertThatThrownBy(() -> reviewService.createReview(command)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.CONSTRAINT_VIOLATION);
        });

        verify(visitedReservationValidator).validate(command);
        verify(reviewRepository).existsByReservationId(RESERVATION_ID);
        verify(reviewServiceMapper, never()).toEntity(any());
        verify(reviewRepository, never()).save(any());
        verify(reviewStatisticsCachePort, never()).delete(anyString());
        verify(reviewOutboxPort, never()).saveReviewCreatedEvent(any());
    }

    @Test
    @DisplayName("리뷰 생성 실패 - 방문 미완료 예약 (검증 실패)")
    void createReview_방문미완료() {
        // given
        PostReviewCommand command = new PostReviewCommand(RESERVATION_ID, USER_ID, RESTAURANT_ID, "맛있어요!", 5, USER_INFO_DTO);

        doThrow(BusinessException.from(CommonErrorCode.FORBIDDEN)).when(visitedReservationValidator).validate(command);

        // when & then
        assertThatThrownBy(() -> reviewService.createReview(command)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
        });

        verify(visitedReservationValidator).validate(command);
        verify(reviewRepository, never()).existsByReservationId(any());
        verify(reviewServiceMapper, never()).toEntity(any());
        verify(reviewRepository, never()).save(any());
        verify(reviewStatisticsCachePort, never()).delete(anyString());
        verify(reviewOutboxPort, never()).saveReviewCreatedEvent(any());
    }

    @Test
    @DisplayName("리뷰 수정 성공 테스트")
    void updateReview_성공() {
        // given
        UpdateReviewCommand command = new UpdateReviewCommand(REVIEW_ID, USER_ID, "수정된 내용입니다.", 4, USER_INFO_DTO);

        Review existingReview = Review.builder().id(REVIEW_ID).reservationId(RESERVATION_ID).userId(USER_ID).restaurantId(RESTAURANT_ID).content("기존 내용").rating(Rating.of(3)).type(ReviewType.of(ReviewType.Type.NORMAL)).build();

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.of(existingReview));
        when(reviewRepository.update(any(Review.class))).thenReturn(existingReview);

        // when
        UpdateReviewResult result = reviewService.updateReview(command);

        // then
        assertThat(result).isNotNull();
        assertThat(result.content()).isEqualTo("수정된 내용입니다.");
        assertThat(result.rating().getRatingValue()).isEqualTo(4);
        assertThat(existingReview.getType().getReviewType()).isEqualTo(ReviewType.Type.EDITED);

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, USER_ID);
        verify(reviewRepository).update(any(Review.class));
        verify(reviewStatisticsCachePort).delete(RESTAURANT_ID.toString());
    }

    @Test
    @DisplayName("리뷰 수정 실패 - 리뷰가 존재하지 않음")
    void updateReview_리뷰없음() {
        // given
        UpdateReviewCommand command = new UpdateReviewCommand(REVIEW_ID, USER_ID, "수정된 내용", 4, USER_INFO_DTO);

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.empty());
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reviewService.updateReview(command)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        });

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, USER_ID);
        verify(reviewRepository).findById(REVIEW_ID);
        verify(reviewRepository, never()).update(any());
        verify(reviewStatisticsCachePort, never()).delete(anyString());
    }

    @Test
    @DisplayName("리뷰 수정 실패 - 권한 없음 (다른 사용자의 리뷰)")
    void updateReview_권한없음() {
        // given
        UpdateReviewCommand command = new UpdateReviewCommand(REVIEW_ID, OTHER_USER_ID, // 다른 사용자
                "수정된 내용", 4, new UserInfoDto(OTHER_USER_ID, Role.USER));

        Review existingReview = Review.builder().id(REVIEW_ID).reservationId(RESERVATION_ID).userId(USER_ID) // 리뷰 작성자는 USER_ID
                .restaurantId(RESTAURANT_ID).content("기존 내용").rating(Rating.of(3)).type(ReviewType.of(ReviewType.Type.NORMAL)).build();

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, OTHER_USER_ID)).thenReturn(Optional.empty());
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(existingReview));

        // when & then
        assertThatThrownBy(() -> reviewService.updateReview(command)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
        });

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, OTHER_USER_ID);
        verify(reviewRepository).findById(REVIEW_ID);
        verify(reviewRepository, never()).update(any());
        verify(reviewStatisticsCachePort, never()).delete(anyString());
    }

    @Test
    @DisplayName("리뷰 수정 실패 - 평점 범위 초과 (1~5 외)")
    void updateReview_평점범위초과() {
        // given: Rating.of()는 1~5 외 값 시 IllegalArgumentException
        UpdateReviewCommand command = new UpdateReviewCommand(REVIEW_ID, USER_ID, "수정된 내용", 0, // 유효하지 않은 평점
                USER_INFO_DTO);

        Review existingReview = Review.builder().id(REVIEW_ID).reservationId(RESERVATION_ID).userId(USER_ID).restaurantId(RESTAURANT_ID).content("기존 내용").rating(Rating.of(3)).type(ReviewType.of(ReviewType.Type.NORMAL)).build();

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.of(existingReview));

        // when & then: 도메인 규칙(Rating.of)에서 IllegalArgumentException 발생
        assertThatThrownBy(() -> reviewService.updateReview(command)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 and 5");

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, USER_ID);
        verify(reviewRepository, never()).update(any());
        verify(reviewStatisticsCachePort, never()).delete(anyString());
    }

    @Test
    @DisplayName("리뷰 삭제 성공 테스트")
    void deleteReview_성공() {
        // given
        DeleteReviewCommand command = new DeleteReviewCommand(REVIEW_ID, USER_ID, USER_INFO_DTO);

        Review review = Review.builder().id(REVIEW_ID).reservationId(RESERVATION_ID).userId(USER_ID).restaurantId(RESTAURANT_ID).content("삭제할 리뷰").rating(Rating.of(5)).type(ReviewType.of(ReviewType.Type.NORMAL)).build();

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.of(review));

        // when
        DeleteReviewResult result = reviewService.deleteReview(command);

        // then
        assertThat(result).isNotNull();
        assertThat(result.reviewId()).isEqualTo(REVIEW_ID);
        assertThat(result.deleted()).isTrue();
        assertThat(review.getType().getReviewType()).isEqualTo(ReviewType.Type.DELETED);

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, USER_ID);
        verify(reviewStatisticsCachePort).delete(RESTAURANT_ID.toString());
        verify(reviewOutboxPort).saveReviewDeletedEvent(any(ReviewDeletedEvent.class));
    }

    @Test
    @DisplayName("리뷰 삭제 실패 - 리뷰가 존재하지 않음")
    void deleteReview_리뷰없음() {
        // given
        DeleteReviewCommand command = new DeleteReviewCommand(REVIEW_ID, USER_ID, USER_INFO_DTO);

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.empty());
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reviewService.deleteReview(command)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        });

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, USER_ID);
        verify(reviewRepository).findById(REVIEW_ID);
        verify(reviewStatisticsCachePort, never()).delete(anyString());
        verify(reviewOutboxPort, never()).saveReviewDeletedEvent(any());
    }

    @Test
    @DisplayName("리뷰 삭제 실패 - 권한 없음 (다른 사용자의 리뷰)")
    void deleteReview_권한없음() {
        // given
        DeleteReviewCommand command = new DeleteReviewCommand(REVIEW_ID, OTHER_USER_ID, new UserInfoDto(OTHER_USER_ID, Role.USER));

        Review review = Review.builder().id(REVIEW_ID).reservationId(RESERVATION_ID).userId(USER_ID) // 다른 사용자의 리뷰
                .restaurantId(RESTAURANT_ID).content("다른 사용자 리뷰").rating(Rating.of(5)).type(ReviewType.of(ReviewType.Type.NORMAL)).build();

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, OTHER_USER_ID)).thenReturn(Optional.empty());
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        // when & then
        assertThatThrownBy(() -> reviewService.deleteReview(command)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
        });

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, OTHER_USER_ID);
        verify(reviewRepository).findById(REVIEW_ID);
        verify(reviewStatisticsCachePort, never()).delete(anyString());
        verify(reviewOutboxPort, never()).saveReviewDeletedEvent(any());
    }

    @Test
    @DisplayName("보상 트랜잭션 - 리뷰 롤백 성공 테스트")
    void cancelReview_성공() {
        // given
        CancelReviewCommand command = new CancelReviewCommand(REVIEW_ID, USER_ID);

        Review review = Review.builder().id(REVIEW_ID).reservationId(RESERVATION_ID).userId(USER_ID).restaurantId(RESTAURANT_ID).content("롤백할 리뷰").rating(Rating.of(5)).type(ReviewType.of(ReviewType.Type.NORMAL)).build();

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.of(review));

        // when
        reviewService.cancelReview(command);

        // then
        assertThat(review.getType().getReviewType()).isEqualTo(ReviewType.Type.DELETED);
        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, USER_ID);
    }

    @Test
    @DisplayName("보상 트랜잭션 - 리뷰 롤백 실패 (리뷰 없음)")
    void cancelReview_리뷰없음() {
        // given
        CancelReviewCommand command = new CancelReviewCommand(REVIEW_ID, USER_ID);

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.empty());
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> reviewService.cancelReview(command)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        });

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, USER_ID);
        verify(reviewRepository).findById(REVIEW_ID);
    }

    @Test
    @DisplayName("보상 트랜잭션 - 리뷰 롤백 실패 (작성자 불일치)")
    void cancelReview_작성자불일치() {
        // given
        CancelReviewCommand command = new CancelReviewCommand(REVIEW_ID, OTHER_USER_ID // 다른 사용자
        );

        Review review = Review.builder().id(REVIEW_ID).reservationId(RESERVATION_ID).userId(USER_ID) // 다른 사용자의 리뷰
                .restaurantId(RESTAURANT_ID).content("롤백할 리뷰").rating(Rating.of(5)).type(ReviewType.of(ReviewType.Type.NORMAL)).build();

        when(reviewRepositoryCustom.findByIdAndUserId(REVIEW_ID, OTHER_USER_ID)).thenReturn(Optional.empty());
        when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

        // when & then
        assertThatThrownBy(() -> reviewService.cancelReview(command)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
        });

        verify(reviewRepositoryCustom).findByIdAndUserId(REVIEW_ID, OTHER_USER_ID);
        verify(reviewRepository).findById(REVIEW_ID);
    }
}
