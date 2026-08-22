package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.port.in.CompanyFilingDetailParityReport;
import io.macrosquare.company.domain.model.CompanyGuidanceAnalysis;
import io.macrosquare.company.domain.model.CompanyGuidanceMetric;
import io.macrosquare.company.domain.model.CompanyGuidanceMetricValue;
import io.macrosquare.company.domain.model.CompanyGuidanceSummary;
import io.macrosquare.company.domain.model.CompanyIrMaterial;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CompanyFilingDetailParityResponse(
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
        ComparisonResponse result,
        List<GuidanceAnalysisResponse> guidance
) {
    static CompanyFilingDetailParityResponse from(CompanyFilingDetailParityReport report) {
        return new CompanyFilingDetailParityResponse(
                report.ticker(),
                report.registryCik(),
                report.selectedCik(),
                report.scannedFilingCount(),
                report.candidateFilingCount(),
                report.inspectedIndexCount(),
                report.migrationReady(),
                report.legacyMetadataPreserved(),
                report.legacySummariesMatched(),
                report.exactLegacyMatch(),
                report.directCoveragePassed(),
                report.directDiscoveryImprovement(),
                report.pdfExtractionCoveragePassed(),
                report.guidanceExtractionCoveragePassed(),
                report.legacyMaterialCount(),
                report.springMaterialCount(),
                report.directAttachmentCount(),
                report.summarizedDirectAttachmentCount(),
                report.pdfMaterialCount(),
                report.parsedPdfMaterialCount(),
                report.summarizedPdfMaterialCount(),
                report.guidanceEligibleMaterialCount(),
                report.guidanceAnalyzedMaterialCount(),
                report.guidanceRelevantMaterialCount(),
                report.structuredGuidanceMaterialCount(),
                report.structuredGuidanceMetricCount(),
                report.selectedFilingAccessions(),
                report.indexFailures(),
                report.summaryFailures(),
                report.differences(),
                new ComparisonResponse(
                        report.legacy().stream().map(MaterialResponse::from).toList(),
                        report.spring().stream().map(MaterialResponse::from).toList()
                ),
                report.guidance().stream().map(GuidanceAnalysisResponse::from).toList()
        );
    }

    public record ComparisonResponse(List<MaterialResponse> legacy, List<MaterialResponse> spring) {
    }

    public record GuidanceAnalysisResponse(
            String title,
            String form,
            LocalDate filingDate,
            String url,
            String contentType,
            GuidanceSummaryResponse summary
    ) {
        static GuidanceAnalysisResponse from(CompanyGuidanceAnalysis value) {
            return new GuidanceAnalysisResponse(
                    value.title(),
                    value.form(),
                    value.filingDate(),
                    value.url(),
                    value.contentType().value(),
                    GuidanceSummaryResponse.from(value.summary())
            );
        }
    }

    public record GuidanceSummaryResponse(
            String stance,
            GuidanceMetricResponse revenue,
            GuidanceMetricResponse margin,
            GuidanceMetricResponse capex,
            GuidanceMetricResponse fcf,
            List<String> evidence
    ) {
        static GuidanceSummaryResponse from(CompanyGuidanceSummary value) {
            return new GuidanceSummaryResponse(
                    value.stance().value(),
                    GuidanceMetricResponse.from(value.revenue()),
                    GuidanceMetricResponse.from(value.margin()),
                    GuidanceMetricResponse.from(value.capex()),
                    GuidanceMetricResponse.from(value.freeCashFlow()),
                    value.evidence()
            );
        }
    }

    public record GuidanceMetricResponse(
            String direction,
            String text,
            GuidanceValueResponse value
    ) {
        static GuidanceMetricResponse from(CompanyGuidanceMetric metric) {
            if (metric == null) return null;
            return new GuidanceMetricResponse(
                    metric.direction().value(),
                    metric.text(),
                    GuidanceValueResponse.from(metric.value())
            );
        }
    }

    public record GuidanceValueResponse(
            String raw,
            BigDecimal min,
            BigDecimal max,
            String unit
    ) {
        static GuidanceValueResponse from(CompanyGuidanceMetricValue value) {
            if (value == null) return null;
            return new GuidanceValueResponse(
                    value.raw(), value.min(), value.max(), value.unit().value()
            );
        }
    }

    public record MaterialResponse(
            String title,
            String form,
            LocalDate filingDate,
            String url,
            String type,
            String source,
            String contentType,
            String summary
    ) {
        static MaterialResponse from(CompanyIrMaterial value) {
            return new MaterialResponse(
                    value.title(),
                    value.form(),
                    value.filingDate(),
                    value.url(),
                    value.type().value(),
                    value.source().value(),
                    value.contentType().value(),
                    value.summary()
            );
        }
    }
}
