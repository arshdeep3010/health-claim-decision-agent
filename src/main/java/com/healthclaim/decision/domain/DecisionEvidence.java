package com.healthclaim.decision.domain;

import com.healthclaim.common.domain.ImmutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A snapshot of one piece of retrieved policy evidence that supported a decision.
 * Chunk/document/policy references are stored as ids (a snapshot), not live
 * associations, so evidence remains stable even if sources later change.
 */
@Entity
@Table(name = "decision_evidence")
public class DecisionEvidence extends ImmutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decision_id", nullable = false)
    private Decision decision;

    @Column(name = "policy_chunk_id")
    private UUID policyChunkId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "section")
    private String section;

    @Column(name = "similarity")
    private Double similarity;

    @Column(name = "evidence_rank")
    private Integer rank;

    @Column(name = "snippet", columnDefinition = "text")
    private String snippet;

    public Decision getDecision() {
        return decision;
    }

    public void setDecision(Decision decision) {
        this.decision = decision;
    }

    public UUID getPolicyChunkId() {
        return policyChunkId;
    }

    public void setPolicyChunkId(UUID policyChunkId) {
        this.policyChunkId = policyChunkId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public UUID getPolicyId() {
        return policyId;
    }

    public void setPolicyId(UUID policyId) {
        this.policyId = policyId;
    }

    public Integer getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(Integer pageNumber) {
        this.pageNumber = pageNumber;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public Double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(Double similarity) {
        this.similarity = similarity;
    }

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }
}
