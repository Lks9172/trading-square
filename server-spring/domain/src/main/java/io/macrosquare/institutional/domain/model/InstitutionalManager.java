package io.macrosquare.institutional.domain.model;

public record InstitutionalManager(String id, String name, String cik) {
    public InstitutionalManager {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("manager id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("manager name is required");
        if (cik == null || !cik.matches("\\d{10}")) {
            throw new IllegalArgumentException("manager CIK must contain ten digits");
        }
    }
}
