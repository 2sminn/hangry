package com.waitless.review.domain.repository;

import com.waitless.review.domain.entity.Review;

import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository {
    Review save(Review review);

    default Review update(Review review) {
        return save(review);
    }

    Optional<Review> findById(UUID reviewId);

    boolean existsByReservationId(UUID reservationId);
}
