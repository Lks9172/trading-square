package io.macrosquare.company.application.port.in;

import io.macrosquare.company.domain.model.CompanyGuidanceAnalysis;
import io.macrosquare.company.domain.model.CompanyIrMaterial;

import java.util.List;
import java.util.Objects;

/**
 * Migration report that separates legacy compatibility from intentional SEC
 * attachment-discovery improvements.
 */
public record CompanyFilingDetailParityReport(
        String ticker,
        String registryCik,
        String selectedCik,
        int scannedFilingCount,
        int candidateFilingCount,
        int inspectedIndexCount,
        boolean migrationReady,
        boolean legacyMetadataPreserved,
        boolean legacySummariesMatched,
        boolean exactLegacyMatch,
        boolean directCoveragePassed,
        boolean directDiscoveryImprovement,
        boolean pdfExtractionCoveragePassed,
        boolean guidanceExtractionCoveragePassed,
        int legacyMaterialCount,
        int springMaterialCount,
        int directAttachmentCount,
        int summarizedDirectAttachmentCount,
        int pdfMaterialCount,
        int parsedPdfMaterialCount,
        int summarizedPdfMaterialCount,
        int guidanceEligibleMaterialCount,
        int guidanceAnalyzedMaterialCount,
        int guidanceRelevantMaterialCount,
        int structuredGuidanceMaterialCount,
        int structuredGuidanceMetricCount,
        List<String> selectedFilingAccessions,
        List<String> indexFailures,
        List<String> summaryFailures,
        List<String> differences,
        List<CompanyIrMaterial> legacy,
        List<CompanyIrMaterial> spring,
        List<CompanyGuidanceAnalysis> guidance
) {
    public CompanyFilingDetailParityReport {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        requireCik(registryCik, "registryCik");
        requireCik(selectedCik, "selectedCik");
        if (scannedFilingCount < 0 || candidateFilingCount < 0 || inspectedIndexCount < 0
                || legacyMaterialCount < 0 || springMaterialCount < 0
                || directAttachmentCount < 0 || summarizedDirectAttachmentCount < 0
                || pdfMaterialCount < 0 || parsedPdfMaterialCount < 0 || summarizedPdfMaterialCount < 0
                || guidanceEligibleMaterialCount < 0 || guidanceAnalyzedMaterialCount < 0
                || guidanceRelevantMaterialCount < 0 || structuredGuidanceMaterialCount < 0
                || structuredGuidanceMetricCount < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        if (parsedPdfMaterialCount > pdfMaterialCount || summarizedPdfMaterialCount > parsedPdfMaterialCount) {
            throw new IllegalArgumentException("PDF extraction counts are inconsistent");
        }
        if (guidanceAnalyzedMaterialCount > guidanceEligibleMaterialCount
                || guidanceRelevantMaterialCount > guidanceAnalyzedMaterialCount
                || structuredGuidanceMaterialCount > guidanceRelevantMaterialCount
                || structuredGuidanceMetricCount > structuredGuidanceMaterialCount * 4) {
            throw new IllegalArgumentException("guidance extraction counts are inconsistent");
        }
        selectedFilingAccessions = List.copyOf(Objects.requireNonNull(
                selectedFilingAccessions, "selectedFilingAccessions"
        ));
        indexFailures = List.copyOf(Objects.requireNonNull(indexFailures, "indexFailures"));
        summaryFailures = List.copyOf(Objects.requireNonNull(summaryFailures, "summaryFailures"));
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        legacy = List.copyOf(Objects.requireNonNull(legacy, "legacy"));
        spring = List.copyOf(Objects.requireNonNull(spring, "spring"));
        guidance = List.copyOf(Objects.requireNonNull(guidance, "guidance"));
        if (guidance.size() != guidanceRelevantMaterialCount) {
            throw new IllegalArgumentException("guidance list size must equal relevant material count");
        }
    }

    private static void requireCik(String value, String field) {
        if (value == null || !value.matches("\\d{10}")) {
            throw new IllegalArgumentException(field + " must contain exactly ten digits");
        }
    }
}
