package io.macrosquare.notification.application.model;

import io.macrosquare.notification.domain.InvestmentCandidate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record NotificationState(
        Set<String> candidateKeys,
        String marketFingerprint,
        String integrityFingerprint,
        Instant updatedAt,
        List<InvestmentCandidate> candidates
) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public NotificationState {
        candidateKeys = Set.copyOf(candidateKeys == null ? Set.of() : candidateKeys);
        marketFingerprint = marketFingerprint == null ? "" : marketFingerprint;
        integrityFingerprint = integrityFingerprint == null ? "" : integrityFingerprint;
        if (!integrityFingerprint.isBlank() && !SHA_256.matcher(integrityFingerprint).matches()) {
            throw new IllegalArgumentException("integrityFingerprint must be an empty value or SHA-256");
        }
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        candidates = List.copyOf(candidates == null ? List.of() : candidates);
    }

    /** Compatibility constructor for state created before integrity incidents were persisted. */
    public NotificationState(
            Set<String> candidateKeys,
            String marketFingerprint,
            Instant updatedAt,
            List<InvestmentCandidate> candidates
    ) {
        this(candidateKeys, marketFingerprint, "", updatedAt, candidates);
    }

    public NotificationState withIntegrityFingerprint(String fingerprint, Instant at) {
        return new NotificationState(candidateKeys, marketFingerprint, fingerprint, at, candidates);
    }

    public static NotificationState empty() {
        return new NotificationState(Set.of(), "", "", Instant.EPOCH, List.of());
    }
}
