package com.waitless.review.application.validator;

import com.waitless.common.exception.BusinessException;
import com.waitless.common.exception.code.CommonErrorCode;
import com.waitless.review.application.dto.client.VisitedReservationRequestDto;
import com.waitless.review.application.dto.client.VisitedReservationResponseDto;
import com.waitless.review.application.dto.command.PostReviewCommand;
import com.waitless.review.application.port.out.VisitedReservationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class VisitedReservationValidatorImpl implements VisitedReservationValidator {

    private final VisitedReservationPort visitedReservationPort;
    @Override
    public void validate(PostReviewCommand command) {
        List<VisitedReservationResponseDto> visited = visitedReservationPort
                .getVisitedReservations(new VisitedReservationRequestDto(command.reservationId()));

        VisitedReservationResponseDto dto = visited.stream()
                .filter(r -> r.reservationId().equals(command.reservationId()))
                .findFirst()
                .orElseThrow(() -> BusinessException.from(CommonErrorCode.NOT_FOUND));

        if (!dto.userId().equals(command.userInfoDto().userId())
                || !dto.restaurantId().equals(command.restaurantId())) {
            throw BusinessException.from(CommonErrorCode.FORBIDDEN);
        }
    }
}