package com.healthclaim.decision.dto;

import java.util.UUID;

public record ExplanationResponse(UUID claimId, String decision, String explanation) {
}
