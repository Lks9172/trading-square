package io.macrosquare.integrity.domain;

public record DataIntegrityViolation(
        String code,
        long actual,
        long expected,
        String description
) {
    public DataIntegrityViolation {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
    }
}
