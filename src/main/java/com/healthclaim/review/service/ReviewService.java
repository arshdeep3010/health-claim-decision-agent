package com.healthclaim.review.service;

import com.healthclaim.audit.service.AuditService;
import com.healthclaim.claim.domain.Claim;
import com.healthclaim.claim.domain.ClaimStatus;
import com.healthclaim.claim.exception.ClaimNotFoundException;
import com.healthclaim.claim.repository.ClaimRepository;
import com.healthclaim.common.response.PagedResponse;
import com.healthclaim.decision.domain.Decision;
import com.healthclaim.decision.domain.DecisionType;
import com.healthclaim.decision.repository.DecisionRepository;
import com.healthclaim.review.domain.HumanReview;
import com.healthclaim.review.domain.ReviewDecision;
import com.healthclaim.review.dto.ReviewResponse;
import com.healthclaim.review.dto.SubmitReviewRequest;
import com.healthclaim.review.exception.ReviewNotFoundException;
import com.healthclaim.review.repository.HumanReviewRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

/**
 * Human review use-cases (§17). A reviewer can approve, reject, or request more
 * information; the review records the AI's prior decision, the final decision, and
 * whether the human agreed with the AI. REQUEST_INFORMATION leaves the claim in
 * review (not finalized).
 */
@Service
public class ReviewService {

    private final ClaimRepository claimRepository;
    private final DecisionRepository decisionRepository;
    private final HumanReviewRepository reviewRepository;
    private final AuditService auditService;

    public ReviewService(ClaimRepository claimRepository,
                         DecisionRepository decisionRepository,
                         HumanReviewRepository reviewRepository,
                         AuditService auditService) {
        this.claimRepository = claimRepository;
        this.decisionRepository = decisionRepository;
        this.reviewRepository = reviewRepository;
        this.auditService = auditService;
    }

    @Transactional
    public ReviewResponse submit(UUID claimId, SubmitReviewRequest request, String reviewerId) {
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new ClaimNotFoundException(claimId));

        Decision decision = decisionRepository.findByClaimId(claimId).orElse(null);
        DecisionType previousAi = decision != null ? decision.getDecision() : null;
        DecisionType finalDecision = finalDecisionFor(request.reviewDecision());

        Boolean agreedWithAi = (finalDecision != null && previousAi != null)
                ? finalDecision == previousAi
                : null;

        HumanReview review = new HumanReview();
        review.setClaim(claim);
        review.setDecision(decision);
        review.setReviewerId(reviewerId);
        review.setReviewDecision(request.reviewDecision());
        review.setReason(request.reason());
        review.setPreviousAiDecision(previousAi);
        review.setFinalDecision(finalDecision);
        review.setAgreedWithAi(agreedWithAi);
        review.setReviewedAt(Instant.now());
        HumanReview saved = reviewRepository.save(review);

        // Apply the human's verdict to the claim's status.
        if (finalDecision != null) {
            claim.setStatus(toClaimStatus(finalDecision));
            claimRepository.save(claim);
        }

        boolean override = previousAi != null && finalDecision != null && finalDecision != previousAi;
        auditService.recordHumanReview(claimId, saved.getId(), reviewerId,
                previousAi, finalDecision, override);

        return ReviewMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ReviewResponse get(UUID reviewId) {
        return reviewRepository.findById(reviewId)
                .map(ReviewMapper::toResponse)
                .orElseThrow(() -> new ReviewNotFoundException(reviewId));
    }

    @Transactional(readOnly = true)
    public PagedResponse<ReviewResponse> list(String reviewerId, Pageable pageable) {
        if (StringUtils.hasText(reviewerId)) {
            return PagedResponse.from(reviewRepository.findByReviewerId(reviewerId, pageable)
                    .map(ReviewMapper::toResponse));
        }
        return PagedResponse.from(reviewRepository.findAll(pageable).map(ReviewMapper::toResponse));
    }

    private static DecisionType finalDecisionFor(ReviewDecision reviewDecision) {
        return switch (reviewDecision) {
            case APPROVE -> DecisionType.APPROVE;
            case REJECT -> DecisionType.REJECT;
            case REQUEST_INFORMATION -> null; // not finalized; awaiting information
        };
    }

    private static ClaimStatus toClaimStatus(DecisionType decision) {
        return switch (decision) {
            case APPROVE -> ClaimStatus.APPROVED;
            case REJECT -> ClaimStatus.REJECTED;
            case HUMAN_REVIEW -> ClaimStatus.HUMAN_REVIEW;
        };
    }
}
