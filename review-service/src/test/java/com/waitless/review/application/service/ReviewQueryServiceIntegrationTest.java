package com.waitless.review.application.service;

import com.waitless.common.domain.Role;
import com.waitless.common.domain.UserInfoDto;
import com.waitless.review.application.dto.command.PageCommand;
import com.waitless.review.application.dto.command.ReviewStatisticsCommand;
import com.waitless.review.application.dto.result.*;
import com.waitless.review.domain.entity.Review;
import com.waitless.review.domain.repository.ReviewRepository;
import com.waitless.review.domain.repository.ReviewRepositoryCustom;
import com.waitless.review.domain.vo.Rating;
import com.waitless.review.domain.vo.ReviewSearchCondition;
import com.waitless.review.domain.vo.ReviewType;
import com.waitless.review.infrastructure.adaptor.out.persistence.JpaReviewRepository;
import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "review-created-events", "review-deleted-events" })
@Transactional
@DisplayName("ReviewService 통합 테스트 - 조회 API")
class ReviewQueryServiceIntegrationTest {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private ReviewRepositoryCustom reviewRepositoryCustom;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private JpaReviewRepository jpaReviewRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID_2 = UUID.randomUUID();
    private static final UUID RESERVATION_ID_3 = UUID.randomUUID();
    private static final UUID RESTAURANT_ID = UUID.randomUUID();
    private static final UUID RESTAURANT_ID_2 = UUID.randomUUID();
    private static final Long USER_ID = 1L;
    private static final Long USER_ID_2 = 2L;
    private static final UserInfoDto USER_INFO_DTO = new UserInfoDto(USER_ID, Role.USER);

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

        // Review 초기화
        jpaReviewRepository.deleteAll();
    }

    @Test
    @DisplayName("리뷰 단건 조회 성공 - ReviewRepositoryCustom 사용")
    void findOne_성공() {
        // given: 리뷰 생성
        Review review = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("맛있어요!")
                .rating(Rating.of(5))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        Review saved = reviewRepository.save(review);

        // when
        GetReviewResult result = reviewService.findOne(
                new ReviewSearchCondition(saved.getId(), null, null, null));

        // then
        assertThat(result).isNotNull();
        assertThat(result.reviewId()).isEqualTo(saved.getId());
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(result.content()).isEqualTo("맛있어요!");
        assertThat(result.rating()).isEqualTo(Rating.of(5));
    }

    @Test
    @DisplayName("리뷰 단건 조회 실패 - 소프트 삭제된 리뷰는 조회되지 않음")
    void findOne_소프트삭제된리뷰_조회안됨() {
        // given: 리뷰 생성 후 소프트 삭제
        Review review = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("삭제된 리뷰")
                .rating(Rating.of(5))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        Review saved = reviewRepository.save(review);
        saved.softDelete();
        reviewRepository.save(saved);

        // when & then: ReviewRepositoryCustom.findOneByCondition은 notDeleted() 조건으로 소프트 삭제 제외
        assertThatThrownBy(() -> reviewService.findOne(
                new ReviewSearchCondition(saved.getId(), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException be = (BusinessException) exception;
                    assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                });

        // 검증: JpaRepository.findById는 소프트 삭제된 리뷰도 조회됨 (차이점 확인)
        Optional<Review> jpaResult = reviewRepository.findById(saved.getId());
        assertThat(jpaResult).isPresent(); // JPA는 조회됨
        assertThat(jpaResult.get().isDeleted()).isTrue(); // 하지만 삭제됨

        // 검증: ReviewRepositoryCustom.findOneByCondition은 조회되지 않음
        Optional<Review> customResult = reviewRepositoryCustom.findOneByCondition(
                new ReviewSearchCondition(saved.getId(), null, null, null));
        assertThat(customResult).isEmpty(); // Custom은 조회 안 됨 (notDeleted() 조건)
    }

    @Test
    @DisplayName("리뷰 리스트 조회 - 페이징 및 소프트 삭제 제외")
    void findList_성공() {
        // given: 같은 유저의 리뷰 3개 생성
        Review review1 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("리뷰 1")
                .rating(Rating.of(5))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        Review review2 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID_2)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("리뷰 2")
                .rating(Rating.of(4))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        Review review3 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID_3)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("리뷰 3")
                .rating(Rating.of(3))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        reviewRepository.save(review1);
        reviewRepository.save(review2);
        Review saved3 = reviewRepository.save(review3);

        // review3 소프트 삭제
        saved3.softDelete();
        reviewRepository.save(saved3);

        // when: 1페이지 조회 (페이지 크기 2)
        Pageable pageable1 = PageRequest.of(0, 2);
        PageCommand<GetReviewListResult> result1 = reviewService.findList(
                new ReviewSearchCondition(null, USER_ID, null, null), pageable1);

        // then: 소프트 삭제된 리뷰 제외하고 2개만 조회됨
        assertThat(result1.content()).hasSize(2);
        assertThat(result1.totalElements()).isEqualTo(2); // review3는 제외됨
        assertThat(result1.totalPages()).isEqualTo(1); // 총 2개, 페이지 크기 2 → 1페이지

        // when: 2페이지 조회 (다음 페이지 확인)
        Pageable pageable2 = PageRequest.of(1, 2);
        PageCommand<GetReviewListResult> result2 = reviewService.findList(
                new ReviewSearchCondition(null, USER_ID, null, null), pageable2);

        // then: 2페이지는 비어있음 (총 2개, 페이지 크기 2)
        assertThat(result2.content()).isEmpty();
        assertThat(result2.totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("리뷰 조건 검색 - userId, restaurantId, rating 조건")
    void findSearch_조건검색_성공() {
        // given: 다양한 조건의 리뷰 생성
        Review review1 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("리뷰 1")
                .rating(Rating.of(5))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        Review review2 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID_2)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("리뷰 2")
                .rating(Rating.of(4))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        Review review3 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID_3)
                .userId(USER_ID_2)
                .restaurantId(RESTAURANT_ID_2)
                .content("리뷰 3")
                .rating(Rating.of(5))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        reviewRepository.save(review1);
        reviewRepository.save(review2);
        reviewRepository.save(review3);

        // when: userId + restaurantId + rating 조건 검색
        Pageable pageable = PageRequest.of(0, 10);
        PageCommand<SearchReviewsResult> result = reviewService.findSearch(
                new ReviewSearchCondition(null, USER_ID, RESTAURANT_ID, 5), pageable);

        // then: 조건에 맞는 리뷰만 조회됨 (review1만)
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).reviewId()).isEqualTo(review1.getId());
        assertThat(result.content().get(0).rating()).isEqualTo(Rating.of(5));
    }

    @Test
    @DisplayName("리뷰 통계 배치 조회 - 여러 레스토랑 통계")
    void getStatisticsBatch_성공() {
        // given: 여러 레스토랑의 리뷰 생성
        Review review1 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("리뷰 1")
                .rating(Rating.of(5))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        Review review2 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID_2)
                .userId(USER_ID)
                .restaurantId(RESTAURANT_ID)
                .content("리뷰 2")
                .rating(Rating.of(4))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        Review review3 = Review.builder()
                .id(UUID.randomUUID())
                .reservationId(RESERVATION_ID_3)
                .userId(USER_ID_2)
                .restaurantId(RESTAURANT_ID_2)
                .content("리뷰 3")
                .rating(Rating.of(5))
                .type(ReviewType.of(ReviewType.Type.NORMAL))
                .build();
        reviewRepository.save(review1);
        reviewRepository.save(review2);
        reviewRepository.save(review3);

        // when
        Map<UUID, ReviewStatisticsResult> result = reviewService.getStatisticsBatch(
                new ReviewStatisticsCommand(List.of(RESTAURANT_ID, RESTAURANT_ID_2), USER_INFO_DTO));

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(RESTAURANT_ID).reviewCount()).isEqualTo(2);
        assertThat(result.get(RESTAURANT_ID).averageRating()).isEqualTo(4.5);
        assertThat(result.get(RESTAURANT_ID_2).reviewCount()).isEqualTo(1);
        assertThat(result.get(RESTAURANT_ID_2).averageRating()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("리뷰 단건 조회 실패 - 리뷰 없음")
    void findOne_리뷰없음() {
        // given: DB에 리뷰 없음

        // when & then
        assertThatThrownBy(() -> reviewService.findOne(
                new ReviewSearchCondition(UUID.randomUUID(), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException be = (BusinessException) exception;
                    assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                });
    }
}