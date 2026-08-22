package io.macrosquare.integrity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record DataIntegrityReport(
        Instant observedAt,
        List<DataIntegrityViolation> violations,
        List<String> hardCollectionSources
) {
    public DataIntegrityReport {
        Objects.requireNonNull(observedAt, "observedAt");
        violations = List.copyOf(violations == null ? List.of() : violations);
        hardCollectionSources = List.copyOf(
                hardCollectionSources == null ? List.of() : hardCollectionSources);
    }

    public boolean healthy() {
        return violations.isEmpty();
    }

    public String fingerprint() {
        if (healthy()) return "";
        // Counts and stale ages can change on every run while the underlying
        // incident class is unchanged. Fingerprinting them would emit a new
        // Telegram alert every minute. New violation classes or a newly
        // failing collection source still produce a different fingerprint.
        var material = violations.stream()
                .sorted(Comparator.comparing(DataIntegrityViolation::code))
                .map(DataIntegrityViolation::code)
                .collect(java.util.stream.Collectors.joining("|"));
        var sources = hardCollectionSources.stream()
                .map(DataIntegrityReport::collectionFailureSignature)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        material += "#" + sources;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String collectionFailureSignature(String value) {
        if (value == null) return "";
        var fields = value.trim().split(":", 4);
        if (fields.length < 3) return value.trim();
        var keyField = fields.length == 4 ? fields[3] : fields[2];
        var keys = java.util.Arrays.stream(keyField.split("[,;\\s]+"))
                .map(String::trim)
                .filter(key -> !key.isBlank())
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
        var prefix = fields[0].trim() + ':' + fields[1].trim();
        if (fields.length == 4) prefix += ':' + fields[2].trim();
        return prefix + ':' + keys;
    }
}
