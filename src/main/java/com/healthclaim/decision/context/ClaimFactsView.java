package com.healthclaim.decision.context;

import com.healthclaim.claim.domain.FactStatus;
import com.healthclaim.claim.extraction.FactKey;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed view over the extracted claim facts, keyed by {@link FactKey}. Absent
 * facts are treated as UNKNOWN.
 */
public final class ClaimFactsView {

    public record FactSnapshot(String value, FactStatus status, Double confidence) {
    }

    private final Map<FactKey, FactSnapshot> facts;

    public ClaimFactsView(Map<FactKey, FactSnapshot> facts) {
        this.facts = new EnumMap<>(FactKey.class);
        this.facts.putAll(facts);
    }

    public FactStatus status(FactKey key) {
        FactSnapshot snapshot = facts.get(key);
        return snapshot != null ? snapshot.status() : FactStatus.UNKNOWN;
    }

    /** The value only when the fact is KNOWN. */
    public Optional<String> knownValue(FactKey key) {
        FactSnapshot snapshot = facts.get(key);
        if (snapshot != null && snapshot.status() == FactStatus.KNOWN && snapshot.value() != null) {
            return Optional.of(snapshot.value());
        }
        return Optional.empty();
    }

    public boolean anyConflicting() {
        return facts.values().stream().anyMatch(f -> f.status() == FactStatus.CONFLICTING);
    }

    public List<FactKey> conflictingKeys() {
        return facts.entrySet().stream()
                .filter(e -> e.getValue().status() == FactStatus.CONFLICTING)
                .map(Map.Entry::getKey)
                .toList();
    }

    public Map<FactKey, FactSnapshot> all() {
        return Map.copyOf(facts);
    }
}
