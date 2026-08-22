package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead;
import io.macrosquare.company.application.port.in.CompanyRevenueMixParityReport;
import io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis;
import io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown;
import io.macrosquare.company.domain.model.CompanyRevenueMixEntry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CompanyRevenueMixParityResponse(
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
        ComparisonResponse result
) {
    static CompanyRevenueMixParityResponse from(CompanyRevenueMixParityReport report) {
        return new CompanyRevenueMixParityResponse(
                report.ticker(),
                report.registryCik(),
                report.selectedCik(),
                report.scannedFilingCount(),
                report.candidateFilingCount(),
                report.analyzedFilingCount(),
                report.dimensionalFactCount(),
                report.migrationReady(),
                report.directCoveragePassed(),
                report.percentageValidationPassed(),
                report.legacyCoveragePreserved(),
                report.segmentActualAvailable(),
                report.geographyActualAvailable(),
                report.selectedFilingAccessions(),
                report.extractionFailures(),
                report.differences(),
                new ComparisonResponse(
                        LegacyResponse.from(report.legacy()),
                        SpringResponse.from(report.spring())
                )
        );
    }

    public record ComparisonResponse(LegacyResponse legacy, SpringResponse spring) {
    }

    public record LegacyResponse(
            String note,
            List<LegacyEntryResponse> segment,
            List<LegacyEntryResponse> geography
    ) {
        static LegacyResponse from(CompanyRevenueMixLegacyRead value) {
            return new LegacyResponse(
                    value.note(),
                    value.segment().stream().map(LegacyEntryResponse::from).toList(),
                    value.geography().stream().map(LegacyEntryResponse::from).toList()
            );
        }
    }

    public record LegacyEntryResponse(
            String label,
            BigDecimal value,
            String unit,
            BigDecimal percentOfTotal
    ) {
        static LegacyEntryResponse from(CompanyRevenueMixLegacyRead.Entry value) {
            return new LegacyEntryResponse(
                    value.label(), value.value(), value.unit(), value.percentOfTotal()
            );
        }
    }

    public record SpringResponse(
            int sourceDocumentCount,
            int dimensionalFactCount,
            BreakdownResponse segment,
            BreakdownResponse geography
    ) {
        static SpringResponse from(CompanyRevenueMixAnalysis value) {
            return new SpringResponse(
                    value.sourceDocumentCount(),
                    value.dimensionalFactCount(),
                    BreakdownResponse.from(value.segment()),
                    BreakdownResponse.from(value.geography())
            );
        }
    }

    public record BreakdownResponse(
            String category,
            String dimension,
            String dimensionName,
            LocalDate periodStart,
            LocalDate periodEnd,
            String unit,
            BigDecimal consolidatedTotal,
            BigDecimal selectedTotal,
            BigDecimal coveragePercent,
            String source,
            List<EntryResponse> entries
    ) {
        static BreakdownResponse from(CompanyRevenueMixBreakdown value) {
            if (value == null) return null;
            return new BreakdownResponse(
                    value.category().value(),
                    value.dimension().value(),
                    value.dimensionName(),
                    value.periodStart(),
                    value.periodEnd(),
                    value.unit(),
                    value.consolidatedTotal(),
                    value.selectedTotal(),
                    value.coveragePercent(),
                    value.source(),
                    value.entries().stream().map(EntryResponse::from).toList()
            );
        }
    }

    public record EntryResponse(String label, BigDecimal value, BigDecimal percentOfTotal) {
        static EntryResponse from(CompanyRevenueMixEntry value) {
            return new EntryResponse(value.label(), value.value(), value.percentOfTotal());
        }
    }
}
