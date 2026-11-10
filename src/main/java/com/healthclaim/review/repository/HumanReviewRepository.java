package com.healthclaim.review.repository;

import com.healthclaim.review.domain.HumanReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HumanReviewRepository extends JpaRepository<HumanReview, UUID> {
    List<HumanReview> findByClaimId(UUID claimId);

    Page<HumanReview> findByReviewerId(String reviewerId, Pageable pageable);
}
