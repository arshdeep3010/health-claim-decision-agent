package com.healthclaim.claim.extraction;

import com.healthclaim.claim.domain.FactStatus;

/**
 * A single normalized, validated fact produced by the extractor. {@code value}
 * is null when unknown; {@code confidence} is null unless the model supplied a
 * valid [0,1] value (never fabricated).
 */
public record ExtractedFact(FactKey key, String value, FactStatus status, Double confidence) {

    public static ExtractedFact unknown(FactKey key) {
        return new ExtractedFact(key, null, FactStatus.UNKNOWN, null);
    }
}
