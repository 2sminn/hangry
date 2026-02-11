package com.waitless.benefit.point.infrastructure.adaptor.out.persistence;

import com.waitless.benefit.point.domain.repository.PointStatisticsProjection;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class PointStatisticsProjectionDto implements PointStatisticsProjection {
    private Long userId;
    private Integer totalPoint;
}