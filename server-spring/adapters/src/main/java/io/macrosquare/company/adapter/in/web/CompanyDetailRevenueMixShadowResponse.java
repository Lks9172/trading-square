package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead;
import io.macrosquare.company.application.port.in.CompanyDetailRevenueMixShadowReport;

import java.math.BigDecimal;
import java.util.List;

public record CompanyDetailRevenueMixShadowResponse(
        String ticker,
        String publicEndpointMode,
        boolean contractCompatible,
        boolean servingSnapshotMatched,
        boolean shadowServeReady,
        boolean directMigrationReady,
        boolean fallbackUsed,
        boolean directCoveragePassed,
        boolean percentageValidationPassed,
        boolean segmentActualAvailable,
        boolean geographyActualAvailable,
        String segmentSource,
        String geographySource,
        String selectedCik,
        int candidateFilingCount,
        int analyzedFilingCount,
        int dimensionalFactCount,
        List<String> extractionFailures,
        ComparisonResponse result
) {
    static CompanyDetailRevenueMixShadowResponse from(CompanyDetailRevenueMixShadowReport report) {
        var direct = report.directParity();
        var composition = report.composition();
        return new CompanyDetailRevenueMixShadowResponse(
                report.ticker(),
                "legacy-unchanged",
                report.contractCompatible(),
                report.servingSnapshotMatched(),
                report.shadowServeReady(),
                report.directMigrationReady(),
                composition.fallbackUsed(),
                direct.directCoveragePassed(),
                direct.percentageValidationPassed(),
                direct.segmentActualAvailable(),
                direct.geographyActualAvailable(),
                composition.segmentSource().value(),
                composition.geographySource().value(),
                direct.selectedCik(),
                direct.candidateFilingCount(),
                direct.analyzedFilingCount(),
                direct.dimensionalFactCount(),
                direct.extractionFailures(),
                new ComparisonResponse(
                        MixResponse.from(composition.baseline()),
                        MixResponse.from(composition.resolved())
                )
        );
    }

    public record ComparisonResponse(MixResponse serving, MixResponse shadow) {
    }

    public record MixResponse(
            String note,
            List<EntryResponse> segment,
            List<EntryResponse> geography
    ) {
        static MixResponse from(CompanyRevenueMixLegacyRead value) {
            return new MixResponse(
                    value.note(),
                    value.segment().stream().map(EntryResponse::from).toList(),
                    value.geography().stream().map(EntryResponse::from).toList()
            );
        }
    }

    public record EntryResponse(
            String label,
            BigDecimal value,
            String unit,
            BigDecimal percentOfTotal
    ) {
        static EntryResponse from(CompanyRevenueMixLegacyRead.Entry value) {
            return new EntryResponse(
                    value.label(), value.value(), value.unit(), value.percentOfTotal()
            );
        }
    }
}
