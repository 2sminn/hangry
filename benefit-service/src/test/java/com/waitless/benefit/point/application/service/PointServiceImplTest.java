package com.waitless.benefit.point.application.service;

import com.waitless.benefit.point.application.dto.command.PostPointCommand;
import com.waitless.benefit.point.application.dto.result.PostPointResult;
import com.waitless.benefit.point.application.mapper.PointServiceMapper;
import com.waitless.benefit.point.application.port.out.PointOutboxPort;
import com.waitless.benefit.point.application.port.out.PointRankingCachePort;
import com.waitless.benefit.point.application.port.out.PointStatisticsCachePort;
import com.waitless.benefit.point.domain.entity.Point;
import com.waitless.benefit.point.domain.repository.PointRepository;
import com.waitless.benefit.point.domain.repository.PointRepositoryCustom;
import com.waitless.benefit.point.domain.vo.PointAmount;
import com.waitless.benefit.point.domain.vo.PointType;
import com.waitless.common.event.PointIssuedEvent;
import com.waitless.common.event.PointIssuedFailedEvent;
import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PointServiceImpl 단위 테스트")
class PointServiceImplTest {

    @Mock
    private PointRepository pointRepository;

    @Mock
    private PointRepositoryCustom pointRepositoryCustom;

    @Mock
    private PointServiceMapper pointServiceMapper;

    @Mock
    private PointOutboxPort pointOutboxPort;

    @Mock
    private PointRankingCachePort pointRankingCachePort;

    @Mock
    private PointStatisticsCachePort pointStatisticsCachePort;

    @InjectMocks
    private PointServiceImpl pointService;

    private static final UUID POINT_ID = UUID.randomUUID();
    private static final UUID REVIEW_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();
    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Integer POINT_AMOUNT = 100;
    private static final PointType.Type POINT_TYPE = PointType.Type.REVIEW_REWARD;
    private static final String DESCRIPTION = "리뷰 작성 보상";

    @Test
    @DisplayName("포인트 적립 성공 테스트")
    void createPoint_성공() {
        // given
        PostPointCommand command = new PostPointCommand(USER_ID, REVIEW_ID, RESERVATION_ID, POINT_AMOUNT, POINT_TYPE, DESCRIPTION);

        Point point = Point.builder().id(POINT_ID).userId(USER_ID).reviewId(REVIEW_ID).reservationId(RESERVATION_ID).amount(PointAmount.of(POINT_AMOUNT)).type(PointType.of(POINT_TYPE)).description(DESCRIPTION).build();

        when(pointRepository.existsByUserIdAndReservationId(USER_ID, RESERVATION_ID)).thenReturn(false);
        when(pointServiceMapper.toEntity(command)).thenReturn(point);
        when(pointRepository.save(any(Point.class))).thenReturn(point);
        when(pointRepositoryCustom.getTotalPointByUserId(USER_ID)).thenReturn(POINT_AMOUNT); // 누적 총합(적립 1건)

        // when
        PostPointResult result = pointService.createPoint(command);

        // then
        assertThat(result).isNotNull();
        
        assertThat(result.pointId()).isEqualTo(POINT_ID);
        assertThat(result.userId()).isEqualTo(USER_ID);
        assertThat(result.reviewId()).isEqualTo(REVIEW_ID);
        assertThat(result.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(result.amount().getPointValue()).isEqualTo(POINT_AMOUNT);
        assertThat(result.type().getPointType()).isEqualTo(POINT_TYPE);
        assertThat(result.description()).isEqualTo(DESCRIPTION);

        // 중복 체크 검증
        verify(pointRepository).existsByUserIdAndReservationId(USER_ID, RESERVATION_ID);

        // 매퍼 및 저장 검증
        verify(pointServiceMapper).toEntity(command);
        verify(pointRepository).save(any(Point.class));

        // 캐시 삭제 검증 (총합 + 개인 랭킹)
        verify(pointStatisticsCachePort).deleteAmount(USER_ID);
        verify(pointStatisticsCachePort).deleteMyRanking(USER_ID);

        // 랭킹 업데이트 검증 (ArgumentCaptor로 값 검증)
        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> pointValueCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(pointRankingCachePort).updateRanking(userIdCaptor.capture(), pointValueCaptor.capture());
        assertThat(userIdCaptor.getValue()).isEqualTo(USER_ID);
        assertThat(pointValueCaptor.getValue()).isEqualTo(POINT_AMOUNT);

        // Outbox 이벤트 저장 검증
        ArgumentCaptor<PointIssuedEvent> eventCaptor = ArgumentCaptor.forClass(PointIssuedEvent.class);
        verify(pointOutboxPort).savePointIssuedEvent(eventCaptor.capture());
        PointIssuedEvent savedEvent = eventCaptor.getValue();
        assertThat(savedEvent.getPointId()).isEqualTo(POINT_ID);
        assertThat(savedEvent.getUserId()).isEqualTo(USER_ID);
        assertThat(savedEvent.getReviewId()).isEqualTo(REVIEW_ID);
        assertThat(savedEvent.getReservationId()).isEqualTo(RESERVATION_ID);
        assertThat(savedEvent.getAmount()).isEqualTo(POINT_AMOUNT);
    }

    @Test
    @DisplayName("포인트 적립 실패 - 중복 적립 (같은 예약에 대한 포인트 이미 존재)")
    void createPoint_중복적립() {
        // given
        PostPointCommand command = new PostPointCommand(USER_ID, REVIEW_ID, RESERVATION_ID, POINT_AMOUNT, POINT_TYPE, DESCRIPTION);

        when(pointRepository.existsByUserIdAndReservationId(USER_ID, RESERVATION_ID)).thenReturn(true);

        // when
        PostPointResult result = pointService.createPoint(command);

        // then
        assertThat(result).isNull();

        // 중복 체크 검증
        verify(pointRepository).existsByUserIdAndReservationId(USER_ID, RESERVATION_ID);

        // 실패 이벤트 발행 검증
        ArgumentCaptor<PointIssuedFailedEvent> failedEventCaptor = ArgumentCaptor.forClass(PointIssuedFailedEvent.class);
        verify(pointOutboxPort).savePointIssuedFailedEvent(failedEventCaptor.capture());
        PointIssuedFailedEvent failedEvent = failedEventCaptor.getValue();
        assertThat(failedEvent.getReviewId()).isEqualTo(REVIEW_ID);
        assertThat(failedEvent.getUserId()).isEqualTo(USER_ID);
        assertThat(failedEvent.getReservationId()).isEqualTo(RESERVATION_ID);

        // 사이드 이펙트 미호출 검증
        verify(pointServiceMapper, never()).toEntity(any());
        verify(pointRepository, never()).save(any());
        verify(pointStatisticsCachePort, never()).deleteAmount(anyLong());
        verify(pointStatisticsCachePort, never()).deleteMyRanking(anyLong());
        verify(pointRankingCachePort, never()).updateRanking(anyLong(), anyInt());
        verify(pointOutboxPort, never()).savePointIssuedEvent(any());
    }

    @Test
    @DisplayName("포인트 적립 실패 - 포인트 값 음수 (도메인 규칙 위반)")
    void createPoint_포인트값음수() {
        // given: 도메인 규칙(PointAmount) 위반 시 mapper에서 IllegalArgumentException
        PostPointCommand command = new PostPointCommand(USER_ID, REVIEW_ID, RESERVATION_ID, -100, // 음수 포인트
                POINT_TYPE, DESCRIPTION);

        when(pointRepository.existsByUserIdAndReservationId(USER_ID, RESERVATION_ID)).thenReturn(false);

        // mapper.toEntity()에서 PointAmount.of() 호출 시 IllegalArgumentException 발생
        when(pointServiceMapper.toEntity(command)).thenThrow(new IllegalArgumentException("포인트 양은 0보다 적을 수 없습니다."));

        // when & then
        assertThatThrownBy(() -> pointService.createPoint(command)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0보다 적을 수 없습니다");

        // 중복 체크는 호출됨
        verify(pointRepository).existsByUserIdAndReservationId(USER_ID, RESERVATION_ID);

        // 매퍼 호출 시 예외 발생
        verify(pointServiceMapper).toEntity(command);

        // 사이드 이펙트 미호출 검증
        verify(pointRepository, never()).save(any());
        verify(pointStatisticsCachePort, never()).deleteAmount(anyLong());
        verify(pointStatisticsCachePort, never()).deleteMyRanking(anyLong());
        verify(pointRankingCachePort, never()).updateRanking(anyLong(), anyInt());
        verify(pointOutboxPort, never()).savePointIssuedEvent(any());
        verify(pointOutboxPort, never()).savePointIssuedFailedEvent(any());
    }

    @Test
    @DisplayName("리뷰 삭제 시 포인트 삭제 성공 - 남은 포인트 없으면 ZSET에서 제거")
    void deletePointByReview_성공_남은포인트없음() {
        // given: 해당 유저의 유일한 포인트 삭제 → 삭제 후 누적 총합 0
        Point existingPoint = Point.builder().id(POINT_ID).userId(USER_ID).reviewId(REVIEW_ID).reservationId(RESERVATION_ID).amount(PointAmount.of(POINT_AMOUNT)).type(PointType.of(POINT_TYPE)).description(DESCRIPTION).build();

        when(pointRepository.findByReviewIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.of(existingPoint));
        when(pointRepositoryCustom.getTotalPointByUserId(USER_ID)).thenReturn(0); // 삭제 후 남은 총합 없음

        // when
        pointService.deletePointByReview(REVIEW_ID, USER_ID);

        // then
        assertThat(existingPoint.isDeleted()).isTrue();
        verify(pointRepository).findByReviewIdAndUserId(REVIEW_ID, USER_ID);
        verify(pointStatisticsCachePort).deleteAmount(USER_ID);
        verify(pointStatisticsCachePort).deleteMyRanking(USER_ID);
        verify(pointRankingCachePort).removeUser(USER_ID); // ZSET에서 제거
        verify(pointRankingCachePort, never()).updateRanking(anyLong(), anyInt());
        verify(pointOutboxPort, never()).savePointIssuedEvent(any());
        verify(pointOutboxPort, never()).savePointIssuedFailedEvent(any());
    }

    @Test
    @DisplayName("리뷰 삭제 시 포인트 삭제 성공 - 남은 포인트 있으면 ZSET 점수 갱신")
    void deletePointByReview_성공_남은포인트있음() {
        // given: 해당 유저가 여러 포인트 보유 중 하나만 삭제 → 삭제 후 누적 총합 50
        Point existingPoint = Point.builder().id(POINT_ID).userId(USER_ID).reviewId(REVIEW_ID).reservationId(RESERVATION_ID).amount(PointAmount.of(POINT_AMOUNT)).type(PointType.of(POINT_TYPE)).description(DESCRIPTION).build();

        when(pointRepository.findByReviewIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.of(existingPoint));
        when(pointRepositoryCustom.getTotalPointByUserId(USER_ID)).thenReturn(50); // 삭제 후 남은 총합

        // when
        pointService.deletePointByReview(REVIEW_ID, USER_ID);

        // then
        assertThat(existingPoint.isDeleted()).isTrue();
        verify(pointRepository).findByReviewIdAndUserId(REVIEW_ID, USER_ID);
        verify(pointStatisticsCachePort).deleteAmount(USER_ID);
        verify(pointStatisticsCachePort).deleteMyRanking(USER_ID);
        verify(pointRankingCachePort).updateRanking(USER_ID, 50); // 누적 총합으로 ZSET 갱신
        verify(pointRankingCachePort, never()).removeUser(anyLong());
        verify(pointOutboxPort, never()).savePointIssuedEvent(any());
        verify(pointOutboxPort, never()).savePointIssuedFailedEvent(any());
    }

    @Test
    @DisplayName("포인트 삭제 실패 - 포인트가 존재하지 않음")
    void deletePointByReview_포인트없음() {
        // given
        when(pointRepository.findByReviewIdAndUserId(REVIEW_ID, USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> pointService.deletePointByReview(REVIEW_ID, USER_ID)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        });

        // 조회 검증
        verify(pointRepository).findByReviewIdAndUserId(REVIEW_ID, USER_ID);

        // 사이드 이펙트 미호출 검증
        verify(pointStatisticsCachePort, never()).deleteAmount(anyLong());
        verify(pointStatisticsCachePort, never()).deleteMyRanking(anyLong());
        verify(pointRankingCachePort, never()).removeUser(anyLong());
    }

    @Test
    @DisplayName("포인트 삭제 실패 - 권한 없음 (다른 사용자의 포인트)")
    void deletePointByReview_권한없음() {
        // given
        // 다른 사용자의 포인트를 조회하려고 하면 Optional.empty() 반환
        when(pointRepository.findByReviewIdAndUserId(REVIEW_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> pointService.deletePointByReview(REVIEW_ID, OTHER_USER_ID)).isInstanceOf(BusinessException.class).satisfies(exception -> {
            BusinessException be = (BusinessException) exception;
            assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        });

        // 조회 검증
        verify(pointRepository).findByReviewIdAndUserId(REVIEW_ID, OTHER_USER_ID);

        // 사이드 이펙트 미호출 검증
        verify(pointStatisticsCachePort, never()).deleteAmount(anyLong());
        verify(pointStatisticsCachePort, never()).deleteMyRanking(anyLong());
        verify(pointRankingCachePort, never()).removeUser(anyLong());
    }

    @Test
    @DisplayName("포인트 적립 성공 - 랭킹 업데이트 값 정확성 검증")
    void createPoint_랭킹업데이트값정확성() {
        // given
        Integer customPointAmount = 250;
        PostPointCommand command = new PostPointCommand(USER_ID, REVIEW_ID, RESERVATION_ID, customPointAmount, POINT_TYPE, DESCRIPTION);

        Point point = Point.builder().id(POINT_ID).userId(USER_ID).reviewId(REVIEW_ID).reservationId(RESERVATION_ID).amount(PointAmount.of(customPointAmount)).type(PointType.of(POINT_TYPE)).description(DESCRIPTION).build();

        when(pointRepository.existsByUserIdAndReservationId(USER_ID, RESERVATION_ID)).thenReturn(false);
        when(pointServiceMapper.toEntity(command)).thenReturn(point);
        when(pointRepository.save(any(Point.class))).thenReturn(point);
        when(pointRepositoryCustom.getTotalPointByUserId(USER_ID)).thenReturn(customPointAmount); // 누적 총합(1건 적립 시 동일)

        // when
        pointService.createPoint(command);

        // then: 랭킹 업데이트 시 누적 총합이 전달되는지 검증
        ArgumentCaptor<Integer> pointValueCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(pointRankingCachePort).updateRanking(eq(USER_ID), pointValueCaptor.capture());
        assertThat(pointValueCaptor.getValue()).isEqualTo(customPointAmount);
    }
}
