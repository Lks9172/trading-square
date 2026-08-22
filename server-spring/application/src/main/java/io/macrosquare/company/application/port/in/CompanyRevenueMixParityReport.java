package io.macrosquare.company.application.port.in;

import io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead;
import io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis;

import java.util.List;
import java.util.Objects;

/** Read-only Strangler report for direct filing-derived revenue mix. */
public record CompanyRevenueMixParityReport(
        String ticker,
        String registryCik,
        String selectedCik,
        int scannedFilingCount,
        int candidateFilingCount,
        int analyzedFilingCount,
        int dimensionalFactCount,
        boolean migrationReady,
        boolean directCoveragePassed,
        boolean percentageValidationPassed,
        boolean legacyCoveragePreserved,
        boolean segmentActualAvailable,
        boolean geographyActualAvailable,
        List<String> selectedFilingAccessions,
        List<String> extractionFailures,
        List<String> differences,
        CompanyRevenueMixLegacyRead legacy,
        CompanyRevenueMixAnalysis spring
) {
    public CompanyRevenueMixParityReport {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        requireCik(registryCik, "registryCik");
        requireCik(selectedCik, "selectedCik");
        if (scannedFilingCount < 0 || candidateFilingCount < 0 || analyzedFilingCount < 0
                || dimensionalFactCount < 0 || analyzedFilingCount > candidateFilingCount) {
            throw new IllegalArgumentException("counts are inconsistent");
        }
        selectedFilingAccessions = List.copyOf(Objects.requireNonNull(
                selectedFilingAccessions, "selectedFilingAccessions"
        ));
        extractionFailures = List.copyOf(Objects.requireNonNull(extractionFailures, "extractionFailures"));
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        legacy = Objects.requireNonNull(legacy, "legacy");
        spring = Objects.requireNonNull(spring, "spring");
        if (selectedFilingAccessions.size() != candidateFilingCount) {
            throw new IllegalArgumentException("selected accession count must equal candidate filing count");
        }
        if (segmentActualAvailable != spring.hasSegment()
                || geographyActualAvailable != spring.hasGeography()
                || dimensionalFactCount != spring.dimensionalFactCount()) {
            throw new IllegalArgumentException("analysis flags must match the Spring result");
        }
    }

    private static void requireCik(String value, String field) {
        if (value == null || !value.matches("\\d{10}")) {
            throw new IllegalArgumentException(field + " must contain exactly ten digits");
        }
    }
}
