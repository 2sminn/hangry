package com.waitless.benefit.point.application.service;

import com.waitless.benefit.point.application.dto.command.PostPointCommand;
import com.waitless.benefit.point.application.dto.result.PostPointResult;
import com.waitless.benefit.point.application.port.in.PointCommandUseCase;
import com.waitless.benefit.point.domain.entity.Point;
import com.waitless.benefit.point.domain.vo.PointAmount;
import com.waitless.benefit.point.domain.vo.PointType;
import com.waitless.benefit.point.infrastructure.adaptor.out.persistence.JpaPointRepository;
import com.waitless.benefit.point.infrastructure.adaptor.outbox.entity.PointOutboxMessage;
import com.waitless.benefit.point.infrastructure.adaptor.outbox.repository.JpaPointOutboxRepository;
import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = { "point-issued-events", "point-issued-failed-events" })
@Transactional
@DisplayName("PointService 통합 테스트")
class PointServiceIntegrationTest {

    @Autowired
    private PointCommandUseCase pointCommandUseCase;

    @Autowired
    private JpaPointRepository jpaPointRepository;

    @Autowired
    private JpaPointOutboxRepository outboxRepository;

    @Autowired
    @Qualifier("pointRedisTemplate")
    private RedisTemplate<String, Object> redisTemplate;

    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID_2 = UUID.randomUUID();
    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Integer POINT_AMOUNT = 100;
    private static final Integer POINT_AMOUNT_2 = 50;
    private static final PointType.Type POINT_TYPE = PointType.Type.REVIEW_REWARD;
    private static final String DESCRIPTION = "리뷰 작성 보상";

    private static final String RANKING_KEY = "point:ranking:global";

    @BeforeEach
    void setUp() {
        // Redis 초기화 (통합테스트는 Redis 필수. 로컬 Redis 실행 여부 확인)
        try {
            Set<String> keys = redisTemplate.keys("*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "통합테스트는 Redis가 필요합니다. 로컬 Redis(docker-compose 등) 실행 여부를 확인하세요.", e);
        }

        // Outbox 초기화
        outboxRepository.deleteAll();

        // Point 초기화
        jpaPointRepository.deleteAll();
    }

    @Test
    @DisplayName("포인트 적립 통합 테스트 - DB 저장, 랭킹 업데이트, 캐시 삭제, Outbox 저장 검증")
    void createPoint_통합테스트() {
        // given
        PostPointCommand command = new PostPointCommand(USER_ID, REVIEW_ID, RESERVATION_ID, POINT_AMOUNT, POINT_TYPE,
                DESCRIPTION);

        // when
        PostPointResult result = pointCommandUseCase.createPoint(command);

        // then
        // 1. DB 저장 검증
        Optional<Point> savedPoint = jpaPointRepository.findById(result.pointId());
        assertThat(savedPoint).isPresent();
        Point point = savedPoint.get();
        assertThat(point.getUserId()).isEqualTo(USER_ID);
        assertThat(point.getReviewId()).isEqualTo(REVIEW_ID);
        assertThat(point.getReservationId()).isEqualTo(RESERVATION_ID);
        assertThat(point.getAmount().getPointValue()).isEqualTo(POINT_AMOUNT);
        assertThat(point.getType().getPointType()).isEqualTo(POINT_TYPE);
        assertThat(point.getDescription()).isEqualTo(DESCRIPTION);

        // 2. ZSET 랭킹 업데이트 검증 (포인트 도메인만의 특성)
        Double rankingScore = redisTemplate.opsForZSet().score(RANKING_KEY, USER_ID);
        assertThat(rankingScore).isNotNull();
        assertThat(rankingScore).isEqualTo(POINT_AMOUNT.doubleValue());

        // 3. Outbox 저장 검증
        List<PointOutboxMessage> outboxMessages = outboxRepository.findAll();
        assertThat(outboxMessages).hasSize(1);
        PointOutboxMessage outbox = outboxMessages.get(0);
        assertThat(outbox.getType()).isEqualTo("point-issued");
        assertThat(outbox.getAggregateType()).isEqualTo("POINT");
        assertThat(outbox.getStatus()).isEqualTo(PointOutboxMessage.OutboxStatus.PENDING);
        assertThat(outbox.getPayload()).contains(result.pointId().toString());
        assertThat(outbox.getPayload()).contains(USER_ID.toString());
        assertThat(outbox.getPayload()).contains(POINT_AMOUNT.toString());
    }

    @Test
    @DisplayName("포인트 중복 적립 통합 테스트 - 실패 이벤트 발행 검증")
    void createPoint_중복적립_통합테스트() {
        // given: 이미 존재하는 포인트
        Point existingPoint = Point.builder().id(UUID.randomUUID()).userId(USER_ID).reviewId(REVIEW_ID)
                .reservationId(RESERVATION_ID).amount(PointAmount.of(POINT_AMOUNT)).type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION).build();
        ((JpaRepository<Point, UUID>) jpaPointRepository).save(existingPoint);

        PostPointCommand command = new PostPointCommand(USER_ID, REVIEW_ID, RESERVATION_ID, // 같은 reservationId
                POINT_AMOUNT, POINT_TYPE, DESCRIPTION);

        // when
        PostPointResult result = pointCommandUseCase.createPoint(command);

        // then
        // 1. null 반환 검증 (예외가 아닌 부드러운 처리)
        assertThat(result).isNull();

        // 2. DB에 중복 저장되지 않음 검증
        List<Point> points = jpaPointRepository.findAll();
        assertThat(points).hasSize(1); // 기존 포인트만 존재

        // 3. 실패 이벤트 발행 검증
        List<PointOutboxMessage> outboxMessages = outboxRepository.findAll();
        assertThat(outboxMessages).hasSize(1);
        PointOutboxMessage outbox = outboxMessages.get(0);
        assertThat(outbox.getType()).isEqualTo("point-issued-failed");
        assertThat(outbox.getAggregateType()).isEqualTo("POINT");
        assertThat(outbox.getStatus()).isEqualTo(PointOutboxMessage.OutboxStatus.PENDING);
        assertThat(outbox.getPayload()).contains(REVIEW_ID.toString());
        assertThat(outbox.getPayload()).contains(USER_ID.toString());
        assertThat(outbox.getPayload()).contains(RESERVATION_ID.toString());

        // 4. 랭킹 업데이트되지 않음 검증
        // 초기화했으므로 랭킹에 없어야 함 (중복 적립 시 랭킹 업데이트 안 됨)
        Double rankingScore = redisTemplate.opsForZSet().score(RANKING_KEY, USER_ID);
        assertThat(rankingScore).isNull();
    }

    @Test
    @DisplayName("포인트 삭제 통합 테스트 - Soft Delete, 랭킹 제거, 캐시 삭제 검증")
    void deletePointByReview_통합테스트() {
        // given: 포인트 생성
        Point existingPoint = Point.builder().id(UUID.randomUUID()).userId(USER_ID).reviewId(REVIEW_ID)
                .reservationId(RESERVATION_ID).amount(PointAmount.of(POINT_AMOUNT)).type(PointType.of(POINT_TYPE))
                .description(DESCRIPTION).build();
        Point saved = ((JpaRepository<Point, UUID>) jpaPointRepository).save(existingPoint);

        // 랭킹에 추가
        redisTemplate.opsForZSet().add(RANKING_KEY, USER_ID, POINT_AMOUNT.doubleValue());

        // when
        pointCommandUseCase.deletePointByReview(REVIEW_ID, USER_ID);

        // then
        // 1. Soft Delete 검증 (DB에는 존재하지만 isDeleted=true)
        Optional<Point> deletedPoint = jpaPointRepository.findById(saved.getId());
        assertThat(deletedPoint).isPresent();
        Point point = deletedPoint.get();
        assertThat(point.isDeleted()).isTrue();

        // 2. ZSET 랭킹 제거 검증 (포인트 도메인만의 특성)
        Double rankingScore = redisTemplate.opsForZSet().score(RANKING_KEY, USER_ID);
        assertThat(rankingScore).isNull(); // ZSET에서 제거됨

        // 3. Outbox에는 이벤트 저장되지 않음 (포인트 삭제는 이벤트 발행하지 않음)
        List<PointOutboxMessage> outboxMessages = outboxRepository.findAll();
        assertThat(outboxMessages).isEmpty();
    }

    @Test
    @DisplayName("포인트 여러 번 적립 시 랭킹 업데이트 통합 테스트")
    void createPoint_여러번적립_랭킹업데이트_통합테스트() {
        // given: 같은 사용자가 여러 번 포인트 적립
        PostPointCommand command1 = new PostPointCommand(USER_ID, REVIEW_ID, RESERVATION_ID, POINT_AMOUNT, POINT_TYPE,
                DESCRIPTION);

        PostPointCommand command2 = new PostPointCommand(USER_ID, UUID.randomUUID(), RESERVATION_ID_2, // 다른 reservationId
                POINT_AMOUNT_2, POINT_TYPE, DESCRIPTION);

        // when
        pointCommandUseCase.createPoint(command1);
        pointCommandUseCase.createPoint(command2);

        // then
        // 1. DB에 2개의 포인트 저장됨
        List<Point> points = jpaPointRepository.findAll();
        assertThat(points).hasSize(2);

        // 2. ZSET 랭킹이 누적 총합으로 업데이트됨
        Double rankingScore = redisTemplate.opsForZSet().score(RANKING_KEY, USER_ID);
        assertThat(rankingScore).isNotNull();
        assertThat(rankingScore).isEqualTo((double) (POINT_AMOUNT + POINT_AMOUNT_2)); // 누적 총합 (100 + 50)

        // 3. Outbox에 2개의 이벤트 저장됨
        List<PointOutboxMessage> outboxMessages = outboxRepository.findAll();
        assertThat(outboxMessages).hasSize(2);
        assertThat(outboxMessages).extracting(PointOutboxMessage::getType).containsExactly("point-issued", "point-issued");
    }

    @Test
    @DisplayName("포인트 삭제 실패 - 포인트 없음 통합 테스트")
    void deletePointByReview_포인트없음_통합테스트() {
        // given: DB에 해당 reviewId+userId 조합의 포인트 없음 (setUp에서 비어 있음)

        // when & then
        assertThatThrownBy(() -> pointCommandUseCase.deletePointByReview(REVIEW_ID, USER_ID))
                .isInstanceOf(BusinessException.class).satisfies(exception -> {
                    BusinessException be = (BusinessException) exception;
                    assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                });

        // DB 상태 변화 없음
        List<Point> points = jpaPointRepository.findAll();
        assertThat(points).isEmpty();

        // 랭킹 변화 없음
        Double rankingScore = redisTemplate.opsForZSet().score(RANKING_KEY, USER_ID);
        assertThat(rankingScore).isNull();
    }

    @Test
    @DisplayName("포인트 삭제 실패 - 권한 없음 통합 테스트")
    void deletePointByReview_권한없음_통합테스트() {
        // given: 다른 사용자의 포인트
        Point existingPoint = Point.builder().id(UUID.randomUUID()).userId(USER_ID) // 작성자는 USER_ID
                .reviewId(REVIEW_ID).reservationId(RESERVATION_ID).amount(PointAmount.of(POINT_AMOUNT))
                .type(PointType.of(POINT_TYPE)).description(DESCRIPTION).build();
        ((JpaRepository<Point, UUID>) jpaPointRepository).save(existingPoint);

        // 랭킹에 추가
        redisTemplate.opsForZSet().add(RANKING_KEY, USER_ID, POINT_AMOUNT.doubleValue());

        // when & then
        assertThatThrownBy(() -> pointCommandUseCase.deletePointByReview(REVIEW_ID, OTHER_USER_ID))
                .isInstanceOf(BusinessException.class).satisfies(exception -> {
                    BusinessException be = (BusinessException) exception;
                    assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
                });

        // DB 상태 변화 없음
        Optional<Point> point = jpaPointRepository.findById(existingPoint.getId());
        assertThat(point).isPresent();
        assertThat(point.get().isDeleted()).isFalse();

        // 랭킹 변화 없음
        Double rankingScore = redisTemplate.opsForZSet().score(RANKING_KEY, USER_ID);
        assertThat(rankingScore).isEqualTo(POINT_AMOUNT.doubleValue());
    }
}
