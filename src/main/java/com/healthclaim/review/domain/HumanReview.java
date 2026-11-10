package com.healthclaim.review.domain;

import com.healthclaim.claim.domain.Claim;
import com.healthclaim.common.domain.BaseEntity;
import com.healthclaim.decision.domain.Decision;
import com.healthclaim.decision.domain.DecisionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A human reviewer's verdict on a claim, capturing agreement/override vs the AI.
 */
@Entity
@Table(name = "human_review")
public class HumanReview extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decision_id")
    private Decision decision;

    @Column(name = "reviewer_id", nullable = false, length = 128)
    private String reviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_decision", nullable = false, length = 32)
    private ReviewDecision reviewDecision;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_ai_decision", length = 16)
    private DecisionType previousAiDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_decision", length = 16)
    private DecisionType finalDecision;

    @Column(name = "agreed_with_ai")
    private Boolean agreedWithAi;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    public Claim getClaim() {
        return claim;
    }

    public void setClaim(Claim claim) {
        this.claim = claim;
    }

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public ReviewDecision getReviewDecision() {
        return reviewDecision;
    }

    public void setReviewDecision(ReviewDecision reviewDecision) {
        this.reviewDecision = reviewDecision;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public DecisionType getPreviousAiDecision() {
        return previousAiDecision;
    }

    public void setPreviousAiDecision(DecisionType previousAiDecision) {
        this.previousAiDecision = previousAiDecision;
    }

    public DecisionType getFinalDecision() {
        return finalDecision;
    }

    public void setFinalDecision(DecisionType finalDecision) {
        this.finalDecision = finalDecision;
    }

    public Boolean getAgreedWithAi() {
        return agreedWithAi;
    }

    public void setAgreedWithAi(Boolean agreedWithAi) {
        this.agreedWithAi = agreedWithAi;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
