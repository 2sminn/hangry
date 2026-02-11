package com.waitless.benefit.point.application.service;

import com.waitless.benefit.point.application.dto.command.GetMyRankingCommand;
import com.waitless.benefit.point.application.dto.command.GetPointAmountCommand;
import com.waitless.benefit.point.application.dto.command.PageCommand;
import com.waitless.benefit.point.application.dto.command.SearchRankingCommand;
import com.waitless.benefit.point.application.dto.result.*;
import com.waitless.benefit.point.domain.entity.Point;
import com.waitless.benefit.point.domain.repository.PointRepository;
import com.waitless.benefit.point.domain.repository.PointRepositoryCustom;
import com.waitless.benefit.point.domain.vo.PointAmount;
import com.waitless.benefit.point.domain.vo.PointSearchCondition;
import com.waitless.benefit.point.domain.vo.PointType;
import com.waitless.benefit.point.infrastructure.adaptor.out.persistence.JpaPointRepository;
import com.waitless.common.domain.Role;
import com.waitless.common.domain.UserInfoDto;
import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "point-issued-events", "point-issued-failed-events" })
@Transactional
@DisplayName("PointQueryService 통합 테스트 - 조회 API")
class PointQueryServiceIntegrationTest {

    @Autowired
    private PointQueryService pointQueryService;

    @Autowired
    private PointRepositoryCustom pointRepositoryCustom;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private JpaPointRepository jpaPointRepository;

    @Autowired
    @Qualifier("pointRedisTemplate")
    private RedisTemplate<String, Object> redisTemplate;

    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID_2 = UUID.randomUUID();
    private static final UUID REVIEW_ID_3 = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID_2 = UUID.randomUUID();
    private static final UUID RESERVATION_ID_3 = UUID.randomUUID();
    private static final Long USER_ID = 1L;
    private static final Long USER_ID_2 = 2L;
    private static final Integer POINT_AMOUNT = 100;
    private static final Integer POINT_AMOUNT_2 = 50;
    private static final Integer POINT_AMOUNT_3 = 75;
    private static final PointType.Type POINT_TYPE = PointType.Type.REVIEW_REWARD;
    private static final String DESCRIPTION = "리뷰 작성 보상";
    private static final UserInfoDto USER_INFO_DTO = new UserInfoDto(USER_ID, Role.USER);

    private static final String RANKING_KEY = "point:ranking:global";
    private static final String POINT_AMOUNT_PREFIX = "point:amount:";
    private static final String POINT_RANKING_PREFIX = "point:ranking:me:";

    @BeforeEach
    void setUp() {
        // Redis 초기화 (통합테스트는 Redis 필수. 로컬 Redis 실행 여부 확인)
        // 포인트 관련 prefix만 삭제하여 다른 서비스/테스트 데이터 보호
        try {
            // 포인트 총합 캐시 삭제
            Set<String> amountKeys = redisTemplate.keys(POINT_AMOUNT_PREFIX + "*");
            if (amountKeys != null && !amountKeys.isEmpty()) {
                redisTemplate.delete(amountKeys);
            }
            // 포인트 개인 랭킹 캐시 삭제
            Set<String> rankingKeys = redisTemplate.keys(POINT_RANKING_PREFIX + "*");
            if (rankingKeys != null && !rankingKeys.isEmpty()) {
                redisTemplate.delete(rankingKeys);
            }
            // 전역 랭킹 ZSET 삭제
            redisTemplate.delete(RANKING_KEY);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "통합테스트는 Redis가 필요합니다. 로컬 Redis(docker-compose 등) 실행 여부를 확인하세요.", e);
        }

        // Point 초기화 (DB는 @Transactional로 롤백됨)
        jpaPointRepository.deleteAll();
    }

    @Test
    @DisplayName("리뷰 ID 기준 포인트 단건 조회 성공 - PointRepositoryCustom 사용")
    void findOne_성공() {
        // given: 포인트 생성
        Point point = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID)
                .reservationId(RESERVATION_ID)
                .amount(PointAmount.of(POINT_AMOUNT))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        Point saved = pointRepository.save(point);

        // when
        GetPointResult result = pointQueryService.findOne(
                new PointSearchCondition(REVIEW_ID, null));

        // then
        assertThat(result).isNotNull();
        assertThat(result.pointId()).isEqualTo(saved.getId());
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.reviewId()).isEqualTo(REVIEW_ID);
        assertThat(result.amount()).isEqualTo(PointAmount.of(POINT_AMOUNT));
    }

    @Test
    @DisplayName("리뷰 ID 기준 포인트 단건 조회 실패 - 소프트 삭제된 포인트는 조회되지 않음")
    void findOne_소프트삭제된포인트_조회안됨() {
        // given: 포인트 생성 후 소프트 삭제
        Point point = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID)
                .reservationId(RESERVATION_ID)
                .amount(PointAmount.of(POINT_AMOUNT))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        Point saved = pointRepository.save(point);
        saved.softDelete();
        pointRepository.save(saved);

        // when & then: PointRepositoryCustom.findByReviewId는 notDeleted() 조건으로 소프트 삭제 제외
        assertThatThrownBy(() -> pointQueryService.findOne(
                new PointSearchCondition(REVIEW_ID, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException be = (BusinessException) exception;
                    assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                });

        // 검증: JpaRepository.findById는 소프트 삭제된 포인트도 조회됨 (차이점 확인)
        Optional<Point> jpaResult = jpaPointRepository.findById(saved.getId());
        assertThat(jpaResult).isPresent(); // JPA는 조회됨
        assertThat(jpaResult.get().isDeleted()).isTrue(); // 하지만 삭제됨

        // 검증: PointRepositoryCustom.findByReviewId는 조회되지 않음
        Optional<Point> customResult = pointRepositoryCustom.findByReviewId(REVIEW_ID);
        assertThat(customResult).isEmpty(); // Custom은 조회 안 됨 (notDeleted() 조건)
    }

    @Test
    @DisplayName("유저 ID 기준 포인트 리스트 조회 - 페이징 및 소프트 삭제 제외")
    void findList_성공() {
        // given: 같은 유저의 포인트 4개 생성 (페이징 테스트를 위해 충분한 데이터)
        Point point1 = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID)
                .reservationId(RESERVATION_ID)
                .amount(PointAmount.of(POINT_AMOUNT))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        Point point2 = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID_2)
                .reservationId(RESERVATION_ID_2)
                .amount(PointAmount.of(POINT_AMOUNT_2))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        Point point3 = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID_3)
                .reservationId(RESERVATION_ID_3)
                .amount(PointAmount.of(POINT_AMOUNT_3))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        pointRepository.save(point1);
        pointRepository.save(point2);
        Point saved3 = pointRepository.save(point3);

        // point3 소프트 삭제
        saved3.softDelete();
        pointRepository.save(saved3);

        // when: 1페이지 조회 (페이지 크기 2)
        Pageable pageable1 = PageRequest.of(0, 2);
        PageCommand<GetPointListResult> result1 = pointQueryService.findList(
                new PointSearchCondition(null, USER_ID), pageable1);

        // then: 소프트 삭제된 포인트 제외하고 2개만 조회됨
        assertThat(result1.content()).hasSize(2);
        assertThat(result1.totalElements()).isEqualTo(2); // point3는 제외됨
        assertThat(result1.totalPages()).isEqualTo(1); // 총 2개, 페이지 크기 2 → 1페이지
        assertThat(result1.content()).extracting(GetPointListResult::reviewId)
                .containsExactlyInAnyOrder(REVIEW_ID, REVIEW_ID_2);

        // when: 2페이지 조회 (다음 페이지 확인)
        Pageable pageable2 = PageRequest.of(1, 2);
        PageCommand<GetPointListResult> result2 = pointQueryService.findList(
                new PointSearchCondition(null, USER_ID), pageable2);

        // then: 2페이지는 비어있음 (총 2개, 페이지 크기 2)
        assertThat(result2.content()).isEmpty();
        assertThat(result2.totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("유저 총 포인트 조회 - Cache-Aside 패턴 및 소프트 삭제 제외")
    void getTotalAmount_성공() {
        // given: 같은 유저의 포인트 3개 생성
        Point point1 = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID)
                .reservationId(RESERVATION_ID)
                .amount(PointAmount.of(POINT_AMOUNT))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        Point point2 = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID_2)
                .reservationId(RESERVATION_ID_2)
                .amount(PointAmount.of(POINT_AMOUNT_2))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        Point point3 = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID_3)
                .reservationId(RESERVATION_ID_3)
                .amount(PointAmount.of(POINT_AMOUNT_3))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        pointRepository.save(point1);
        pointRepository.save(point2);
        Point saved3 = pointRepository.save(point3);

        // point3 소프트 삭제
        saved3.softDelete();
        pointRepository.save(saved3);

        // when: 1회 호출 (캐시 MISS → DB 조회 → 캐시 저장)
        GetPointAmountResult result1 = pointQueryService.getTotalAmount(
                new GetPointAmountCommand(USER_ID, USER_INFO_DTO));

        // then: 소프트 삭제된 포인트 제외하고 총합 계산 (100 + 50 = 150)
        assertThat(result1.totalPoint()).isEqualTo(POINT_AMOUNT + POINT_AMOUNT_2);
        assertThat(result1.userId()).isEqualTo(USER_ID);

        // when: 2회 호출 (캐시 HIT → DB 조회 없이 캐시에서 반환)
        GetPointAmountResult result2 = pointQueryService.getTotalAmount(
                new GetPointAmountCommand(USER_ID, USER_INFO_DTO));

        // then: 동일한 결과 반환 (캐시 재사용)
        assertThat(result2.totalPoint()).isEqualTo(POINT_AMOUNT + POINT_AMOUNT_2);
        assertThat(result2.userId()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("Top N 랭킹 조회 - Redis ZSET + DB 조회 통합 및 순위 정확성 검증")
    void getTopRanking_성공() {
        // given: 여러 유저의 포인트 생성 및 랭킹에 추가
        Point point1 = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID)
                .reservationId(RESERVATION_ID)
                .amount(PointAmount.of(POINT_AMOUNT))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        Point point2 = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID_2)
                .reviewId(REVIEW_ID_2)
                .reservationId(RESERVATION_ID_2)
                .amount(PointAmount.of(POINT_AMOUNT_2))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        pointRepository.save(point1);
        pointRepository.save(point2);

        // 랭킹 ZSET에 누적 총합으로 설정 (테스트 데이터)
        redisTemplate.opsForZSet().add(RANKING_KEY, USER_ID, POINT_AMOUNT.doubleValue());
        redisTemplate.opsForZSet().add(RANKING_KEY, USER_ID_2, POINT_AMOUNT_2.doubleValue());

        // when
        PageCommand<SearchRankingResult> result = pointQueryService.getTopRanking(
                new SearchRankingCommand(2, USER_INFO_DTO));

        // then: 랭킹 결과 및 순위 정확성 검증
        assertThat(result.content()).hasSize(2);
        assertThat(result.content()).extracting(SearchRankingResult::userId)
                .containsExactly(USER_ID, USER_ID_2); // 점수 높은 순 (100 > 50)

        // 1등 검증
        SearchRankingResult first = result.content().get(0);
        assertThat(first.userId()).isEqualTo(USER_ID);
        assertThat(first.totalPoint()).isEqualTo(POINT_AMOUNT);
        assertThat(first.rank()).isEqualTo(1); // 1등

        // 2등 검증
        SearchRankingResult second = result.content().get(1);
        assertThat(second.userId()).isEqualTo(USER_ID_2);
        assertThat(second.totalPoint()).isEqualTo(POINT_AMOUNT_2);
        assertThat(second.rank()).isEqualTo(2); // 2등
    }

    @Test
    @DisplayName("본인 랭킹 조회 - Cache-Aside 패턴 및 Redis ZSET 조회")
    void getMyRanking_성공() {
        // given: 포인트 생성 및 랭킹에 추가
        Point point = Point.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .reviewId(REVIEW_ID)
                .reservationId(RESERVATION_ID)
                .amount(PointAmount.of(POINT_AMOUNT))
                .type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION)
                .build();
        pointRepository.save(point);

        redisTemplate.opsForZSet().add(RANKING_KEY, USER_ID, POINT_AMOUNT.doubleValue());

        // when: 1회 호출 (캐시 MISS → ZSET + DB 조회 → 캐시 저장)
        GetMyRankingResult result1 = pointQueryService.getMyRanking(
                new GetMyRankingCommand(USER_ID, USER_INFO_DTO));

        // then: 기본 검증
        assertThat(result1.userId()).isEqualTo(USER_ID);
        assertThat(result1.totalPoint()).isEqualTo(POINT_AMOUNT);
        assertThat(result1.rank()).isGreaterThan(0);

        // when: 2회 호출 (캐시 HIT → ZSET/DB 조회 없이 캐시에서 반환)
        GetMyRankingResult result2 = pointQueryService.getMyRanking(
                new GetMyRankingCommand(USER_ID, USER_INFO_DTO));

        // then: 동일한 결과 반환 (캐시 재사용)
        assertThat(result2.userId()).isEqualTo(USER_ID);
        assertThat(result2.totalPoint()).isEqualTo(POINT_AMOUNT);
        assertThat(result2.rank()).isEqualTo(result1.rank()); // 동일한 순위
    }

    @Test
    @DisplayName("리뷰 ID 기준 포인트 단건 조회 실패 - 포인트 없음")
    void findOne_포인트없음() {
        // given: DB에 포인트 없음

        // when & then
        assertThatThrownBy(() -> pointQueryService.findOne(
                new PointSearchCondition(REVIEW_ID, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> {
                    BusinessException be = (BusinessException) exception;
                    assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                });
    }
}
