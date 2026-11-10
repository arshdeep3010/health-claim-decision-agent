package com.healthclaim.claim.extraction;

import java.util.Optional;

/**
 * The canonical set of claim facts the extractor normalizes to. Each key has a
 * stable wire name (used in prompts, the JSON schema, and the {@code fact_key}
 * column) and an expected value type matching the {@code claim_fact.value_type}
 * CHECK constraint.
 */
public enum FactKey {
    DIAGNOSIS("diagnosis", "STRING"),
    PROCEDURE("procedure", "STRING"),
    TREATMENT("treatment", "STRING"),
    HOSPITALIZATION_DAYS("hospitalizationDays", "NUMBER"),
    CLAIM_AMOUNT("claimAmount", "NUMBER"),
    PRE_EXISTING_CONDITION("preExistingCondition", "BOOLEAN");

    private final String wire;
    private final String valueType;

    FactKey(String wire, String valueType) {
        this.wire = wire;
        this.valueType = valueType;
    }

    public String wire() {
        return wire;
    }

    public String valueType() {
        return valueType;
    }

    public static Optional<FactKey> fromWire(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        for (FactKey key : values()) {
            if (key.wire.equalsIgnoreCase(wire)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }
}
