package com.waitless.review.application.service;

import com.waitless.common.command.CancelReviewCommand;
import com.waitless.common.domain.Role;
import com.waitless.common.domain.UserInfoDto;
import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
import com.waitless.review.application.dto.client.VisitedReservationRequestDto;
import com.waitless.review.application.dto.client.VisitedReservationResponseDto;
import com.waitless.review.application.dto.command.DeleteReviewCommand;
import com.waitless.review.application.dto.command.PostReviewCommand;
import com.waitless.review.application.dto.command.UpdateReviewCommand;
import com.waitless.review.application.dto.result.PostReviewResult;
import com.waitless.review.application.port.in.ReviewCommandUseCase;
import com.waitless.review.application.port.out.VisitedReservationPort;
import com.waitless.review.domain.entity.Review;
import com.waitless.review.domain.repository.ReviewRepository;
import com.waitless.review.domain.vo.ReviewType;
import com.waitless.review.infrastructure.adaptor.out.persistence.JpaReviewRepository;
import com.waitless.review.infrastructure.adaptor.outbox.entity.ReviewOutboxMessage;
import com.waitless.review.infrastructure.adaptor.outbox.repository.JpaReviewOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "review-created-events", "review-deleted-events" })
@Transactional
@DisplayName("ReviewService 통합 테스트")
class ReviewServiceIntegrationTest {

        @Autowired
        private ReviewService reviewService;

        @Autowired
        private ReviewCommandUseCase reviewCommandUseCase;

        @Autowired
        private ReviewRepository reviewRepository;

        @Autowired
        private JpaReviewRepository jpaReviewRepository;

        @Autowired
        private JpaReviewOutboxRepository outboxRepository;

        @Autowired
        private StringRedisTemplate redisTemplate;

        @MockitoBean
        private VisitedReservationPort visitedReservationPort;

        private static final UUID RESERVATION_ID = UUID.randomUUID();
        private static final UUID RESTAURANT_ID = UUID.randomUUID();
        private static final Long USER_ID = 1L;
        private static final Long OTHER_USER_ID = 2L;
        private static final UserInfoDto USER_INFO_DTO = new UserInfoDto(USER_ID, Role.USER);
        private static final UserInfoDto OTHER_USER_INFO_DTO = new UserInfoDto(OTHER_USER_ID, Role.USER);

        private static final String CACHE_KEY_PREFIX = "review:stats:";

        @BeforeEach
        void setUp() {
                // Redis 캐시 초기화 (통합테스트는 Redis 필수. 로컬 Redis 실행 여부 확인)
                try {
                        Set<String> keys = redisTemplate.keys(CACHE_KEY_PREFIX + "*");
                        if (keys != null && !keys.isEmpty()) {
                                redisTemplate.delete(keys);
                        }
                } catch (Exception e) {
                        throw new IllegalStateException(
                                        "통합테스트는 Redis가 필요합니다. 로컬 Redis(docker-compose 등) 실행 여부를 확인하세요.", e);
                }
                // Outbox 초기화
                outboxRepository.deleteAll();

                // Review 초기화
                jpaReviewRepository.deleteAll();
        }

        @Test
        @DisplayName("리뷰 생성 통합 테스트 - DB 저장, Outbox 저장, 캐시 삭제 검증")
        void createReview_통합테스트() {
                // given
                PostReviewCommand command = new PostReviewCommand(RESERVATION_ID, USER_ID, RESTAURANT_ID, "맛있어요!", 5,
                                USER_INFO_DTO);

                // 방문 완료 예약 Mock 설정
                VisitedReservationResponseDto visitedReservation = new VisitedReservationResponseDto(RESERVATION_ID,
                                USER_ID,
                                RESTAURANT_ID);
                when(visitedReservationPort.getVisitedReservations(any(VisitedReservationRequestDto.class)))
                                .thenReturn(List.of(visitedReservation));

                // 캐시에 통계 데이터 설정
                String cacheKey = CACHE_KEY_PREFIX + RESTAURANT_ID;
                try {
                        redisTemplate.opsForValue().set(cacheKey, "{\"averageRating\":4.5,\"totalCount\":10}");
                } catch (Exception e) {
                        // Redis 연결 문제 시 테스트 스킵하지 않고 계속 진행
                        // (실제 서비스에서는 캐시가 없어도 동작해야 함)
                }

                // when
                PostReviewResult result = reviewService.createReview(command);

                // then
                // 1. DB 저장 검증
                Optional<Review> savedReview = reviewRepository.findById(result.reviewId());
                assertThat(savedReview).isPresent();
                Review review = savedReview.get();
                assertThat(review.getReservationId()).isEqualTo(RESERVATION_ID);
                assertThat(review.getUserId()).isEqualTo(USER_ID);
                assertThat(review.getRestaurantId()).isEqualTo(RESTAURANT_ID);
                assertThat(review.getContent()).isEqualTo("맛있어요!");
                assertThat(review.getRating().getRatingValue()).isEqualTo(5);
                assertThat(review.getType().getReviewType()).isEqualTo(ReviewType.Type.NORMAL);

                // 2. Outbox 저장 검증
                List<ReviewOutboxMessage> outboxMessages = outboxRepository.findAll();
                assertThat(outboxMessages).hasSize(1);
                ReviewOutboxMessage outbox = outboxMessages.get(0);
                assertThat(outbox.getType()).isEqualTo("review-created");
                assertThat(outbox.getAggregateType()).isEqualTo("REVIEW");
                assertThat(outbox.getStatus()).isEqualTo(ReviewOutboxMessage.OutboxStatus.PENDING);
                assertThat(outbox.getPayload()).contains(result.reviewId().toString());
                assertThat(outbox.getPayload()).contains(RESERVATION_ID.toString());

                // 3. 캐시 삭제 검증(캐시 채워져 있으면 안됨)
                String cachedValue = redisTemplate.opsForValue().get(cacheKey);
                assertThat(cachedValue).isNull();
        }

        @Test
        @DisplayName("리뷰 수정 통합 테스트 - DB 업데이트, 캐시 삭제 검증")
        void updateReview_통합테스트() {
                // given: 리뷰 생성
                Review existingReview = Review.builder().id(UUID.randomUUID()).reservationId(RESERVATION_ID)
                                .userId(USER_ID)
                                .restaurantId(RESTAURANT_ID).content("기존 내용")
                                .rating(com.waitless.review.domain.vo.Rating.of(3))
                                .type(com.waitless.review.domain.vo.ReviewType.of(ReviewType.Type.NORMAL)).build();
                Review saved = reviewRepository.save(existingReview);

                // 캐시에 통계 데이터 설정
                String cacheKey = CACHE_KEY_PREFIX + RESTAURANT_ID;
                redisTemplate.opsForValue().set(cacheKey, "{\"averageRating\":3.0,\"totalCount\":1}");
                UpdateReviewCommand command = new UpdateReviewCommand(saved.getId(), USER_ID, "수정된 내용입니다.", 4,
                                USER_INFO_DTO);

                // when
                reviewService.updateReview(command); // 반환값은 통합 테스트에서 DB/캐시로 검증

                // then
                // 1. DB 업데이트 검증
                Optional<Review> updatedReview = reviewRepository.findById(saved.getId());
                assertThat(updatedReview).isPresent();
                Review review = updatedReview.get();
                assertThat(review.getContent()).isEqualTo("수정된 내용입니다.");
                assertThat(review.getRating().getRatingValue()).isEqualTo(4);
                assertThat(review.getType().getReviewType()).isEqualTo(ReviewType.Type.EDITED);

                // 2. 캐시 삭제 검증(캐시 채워져 있으면 안됨)
                String cachedValue = redisTemplate.opsForValue().get(cacheKey);
                assertThat(cachedValue).isNull();
        }

        @Test
        @DisplayName("리뷰 삭제 통합 테스트 - Soft Delete, Outbox 저장, 캐시 삭제 검증")
        void deleteReview_통합테스트() {
                // given: 리뷰 생성
                Review existingReview = Review.builder().id(UUID.randomUUID()).reservationId(RESERVATION_ID)
                                .userId(USER_ID)
                                .restaurantId(RESTAURANT_ID).content("삭제할 리뷰")
                                .rating(com.waitless.review.domain.vo.Rating.of(5))
                                .type(com.waitless.review.domain.vo.ReviewType.of(ReviewType.Type.NORMAL)).build();
                Review saved = reviewRepository.save(existingReview);

                // 캐시에 통계 데이터 설정
                String cacheKey = CACHE_KEY_PREFIX + RESTAURANT_ID;
                redisTemplate.opsForValue().set(cacheKey, "{\"averageRating\":5.0,\"totalCount\":1}");
                DeleteReviewCommand command = new DeleteReviewCommand(saved.getId(), USER_ID, USER_INFO_DTO);

                // when
                reviewService.deleteReview(command);

                // then
                // 1. Soft Delete 검증 (DB에는 존재하지만 isDeleted=true)
                Optional<Review> deletedReview = reviewRepository.findById(saved.getId());
                assertThat(deletedReview).isPresent();
                Review review = deletedReview.get();
                assertThat(review.getType().getReviewType()).isEqualTo(ReviewType.Type.DELETED);
                assertThat(review.isDeleted()).isTrue();

                // 2. Outbox 저장 검증
                List<ReviewOutboxMessage> outboxMessages = outboxRepository.findAll();
                assertThat(outboxMessages).hasSize(1);
                ReviewOutboxMessage outbox = outboxMessages.get(0);
                assertThat(outbox.getType()).isEqualTo("review-deleted");
                assertThat(outbox.getAggregateType()).isEqualTo("REVIEW");
                assertThat(outbox.getStatus()).isEqualTo(ReviewOutboxMessage.OutboxStatus.PENDING);
                assertThat(outbox.getPayload()).contains(saved.getId().toString());

                // 3. 캐시 삭제 검증(캐시 채워져 있으면 안됨)
                String cachedValue = redisTemplate.opsForValue().get(cacheKey);
                assertThat(cachedValue).isNull();
        }

        @Test
        @DisplayName("보상 트랜잭션 - 리뷰 롤백 통합 테스트")
        void cancelReview_통합테스트() {
                // given: 리뷰 생성
                Review existingReview = Review.builder().id(UUID.randomUUID()).reservationId(RESERVATION_ID)
                                .userId(USER_ID)
                                .restaurantId(RESTAURANT_ID).content("롤백할 리뷰")
                                .rating(com.waitless.review.domain.vo.Rating.of(5))
                                .type(com.waitless.review.domain.vo.ReviewType.of(ReviewType.Type.NORMAL)).build();
                Review saved = reviewRepository.save(existingReview);
                CancelReviewCommand command = new CancelReviewCommand(saved.getId(), USER_ID);

                // when
                reviewCommandUseCase.cancelReview(command);

                // then: Soft Delete 검증
                Optional<Review> cancelledReview = reviewRepository.findById(saved.getId());
                assertThat(cancelledReview).isPresent();
                Review review = cancelledReview.get();
                assertThat(review.getType().getReviewType()).isEqualTo(ReviewType.Type.DELETED);
                assertThat(review.isDeleted()).isTrue();
        }

        @Test
        @DisplayName("리뷰 생성 실패 - 중복 예약 리뷰 통합 테스트")
        void createReview_중복예약_통합테스트() {
                // given: 이미 존재하는 리뷰
                Review existingReview = Review.builder().id(UUID.randomUUID()).reservationId(RESERVATION_ID)
                                .userId(USER_ID)
                                .restaurantId(RESTAURANT_ID).content("기존 리뷰")
                                .rating(com.waitless.review.domain.vo.Rating.of(5))
                                .type(com.waitless.review.domain.vo.ReviewType.of(ReviewType.Type.NORMAL)).build();
                reviewRepository.save(existingReview);

                // 방문 완료 예약 Mock 설정
                VisitedReservationResponseDto visitedReservation = new VisitedReservationResponseDto(RESERVATION_ID,
                                USER_ID,
                                RESTAURANT_ID);
                when(visitedReservationPort.getVisitedReservations(any(VisitedReservationRequestDto.class)))
                                .thenReturn(List.of(visitedReservation));
                PostReviewCommand command = new PostReviewCommand(RESERVATION_ID, USER_ID, RESTAURANT_ID, "중복 리뷰", 5,
                                USER_INFO_DTO);

                // when & then
                assertThatThrownBy(() -> reviewService.createReview(command)).isInstanceOf(BusinessException.class)
                                .satisfies(exception -> {
                                        BusinessException be = (BusinessException) exception;
                                        assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.CONSTRAINT_VIOLATION);
                                });

                // Outbox에 저장되지 않았는지 검증
                List<ReviewOutboxMessage> outboxMessages = outboxRepository.findAll();
                assertThat(outboxMessages).isEmpty();
        }

        @Test
        @DisplayName("리뷰 생성 실패 - 방문 미완료 예약 통합 테스트")
        void createReview_방문미완료_통합테스트() {
                // given: 방문하지 않은 예약 (빈 리스트 반환)
                when(visitedReservationPort.getVisitedReservations(any(VisitedReservationRequestDto.class)))
                                .thenReturn(List.of());
                PostReviewCommand command = new PostReviewCommand(RESERVATION_ID, USER_ID, RESTAURANT_ID, "리뷰", 5,
                                USER_INFO_DTO);

                // when & then
                assertThatThrownBy(() -> reviewService.createReview(command)).isInstanceOf(BusinessException.class)
                                .satisfies(exception -> {
                                        BusinessException be = (BusinessException) exception;
                                        assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                                });

                // DB에 저장되지 않았는지 검증
                List<Review> reviews = jpaReviewRepository.findAll();
                assertThat(reviews).isEmpty();

                // Outbox에 저장되지 않았는지 검증
                List<ReviewOutboxMessage> outboxMessages = outboxRepository.findAll();
                assertThat(outboxMessages).isEmpty();
        }

        @Test
        @DisplayName("리뷰 수정 실패 - 권한 없음 통합 테스트")
        void updateReview_권한없음_통합테스트() {
                // given: 다른 사용자의 리뷰
                Review existingReview = Review.builder().id(UUID.randomUUID()).reservationId(RESERVATION_ID)
                                .userId(USER_ID) // 작성자 USER_ID
                                .restaurantId(RESTAURANT_ID).content("기존 내용")
                                .rating(com.waitless.review.domain.vo.Rating.of(3))
                                .type(com.waitless.review.domain.vo.ReviewType.of(ReviewType.Type.NORMAL)).build();
                Review saved = reviewRepository.save(existingReview);

                UpdateReviewCommand command = new UpdateReviewCommand(saved.getId(), OTHER_USER_ID, // 다른 사용자
                                "수정 시도", 4, OTHER_USER_INFO_DTO);

                // when & then
                assertThatThrownBy(() -> reviewService.updateReview(command)).isInstanceOf(BusinessException.class)
                                .satisfies(exception -> {
                                        BusinessException be = (BusinessException) exception;
                                        assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
                                });

                // 리뷰가 변경되지 않았는지 검증(변경되면 안됨)
                Optional<Review> review = reviewRepository.findById(saved.getId());
                assertThat(review).isPresent();
                assertThat(review.get().getContent()).isEqualTo("기존 내용");
                assertThat(review.get().getRating().getRatingValue()).isEqualTo(3);
        }

        @Test
        @DisplayName("리뷰 삭제 실패 - 권한 없음 통합 테스트")
        void deleteReview_권한없음_통합테스트() {
                // given: 다른 사용자의 리뷰
                Review existingReview = Review.builder().id(UUID.randomUUID()).reservationId(RESERVATION_ID)
                                .userId(USER_ID) // 작성자는 USER_ID
                                .restaurantId(RESTAURANT_ID).content("다른 사용자 리뷰")
                                .rating(com.waitless.review.domain.vo.Rating.of(5))
                                .type(com.waitless.review.domain.vo.ReviewType.of(ReviewType.Type.NORMAL)).build();
                Review saved = reviewRepository.save(existingReview);

                DeleteReviewCommand command = new DeleteReviewCommand(saved.getId(), OTHER_USER_ID, // 다른 사용자
                                OTHER_USER_INFO_DTO);

                // when & then
                assertThatThrownBy(() -> reviewService.deleteReview(command)).isInstanceOf(BusinessException.class)
                                .satisfies(exception -> {
                                        BusinessException be = (BusinessException) exception;
                                        assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
                                });

                // 리뷰가 삭제되지 않았는지 검증(삭제되면 안됨)
                Optional<Review> review = reviewRepository.findById(saved.getId());
                assertThat(review).isPresent();
                assertThat(review.get().getType().getReviewType()).isEqualTo(ReviewType.Type.NORMAL);
                assertThat(review.get().isDeleted()).isFalse();

                // Outbox에 저장되지 않았는지 검증
                List<ReviewOutboxMessage> outboxMessages = outboxRepository.findAll();
                assertThat(outboxMessages).isEmpty();
        }
}
