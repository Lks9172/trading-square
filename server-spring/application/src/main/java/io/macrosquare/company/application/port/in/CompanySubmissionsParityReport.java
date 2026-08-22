package io.macrosquare.company.application.port.in;

import io.macrosquare.company.application.model.CompanySubmissionsSnapshot;

import java.util.List;
import java.util.Objects;

public record CompanySubmissionsParityReport(
        String ticker,
        String registryCik,
        String selectedCik,
        List<String> submissionCikCandidates,
        boolean allMatched,
        boolean profileMatched,
        boolean filingsMatched,
        int comparedFilingCount,
        int directAvailableFilingCount,
        int legacyEnrichedFilingCount,
        List<String> differences,
        CompanySubmissionsSnapshot legacy,
        CompanySubmissionsSnapshot spring
) {
    public CompanySubmissionsParityReport {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        if (registryCik == null || !registryCik.matches("\\d{10}")) {
            throw new IllegalArgumentException("registryCik must contain exactly ten digits");
        }
        if (selectedCik == null || !selectedCik.matches("\\d{10}")) {
            throw new IllegalArgumentException("selectedCik must contain exactly ten digits");
        }
        submissionCikCandidates = List.copyOf(Objects.requireNonNull(
                submissionCikCandidates, "submissionCikCandidates"
        ));
        if (comparedFilingCount < 0 || directAvailableFilingCount < 0 || legacyEnrichedFilingCount < 0) {
            throw new IllegalArgumentException("filing counts must not be negative");
        }
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        legacy = Objects.requireNonNull(legacy, "legacy");
        spring = Objects.requireNonNull(spring, "spring");
    }
}
