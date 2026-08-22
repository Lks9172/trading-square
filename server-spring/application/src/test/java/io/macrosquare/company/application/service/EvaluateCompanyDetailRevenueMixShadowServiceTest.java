package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead;
import io.macrosquare.company.application.model.CompanyRevenueMixComposition.Source;
import io.macrosquare.company.application.port.in.CompanyRevenueMixParityReport;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateCompanyDetailRevenueMixShadowServiceTest {

    @Test
    void preparesAnActualFirstFallbackSafeShadowWithoutChangingTheServingRead() {
        var serving = CompanyRevenueMixComposerTest.research(true);
        var analysis = new CompanyRevenueMixAnalysis(
                CompanyRevenueMixComposerTest.breakdown(
                        io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown.Category.SEGMENT,
                        "Cloud", 80, "Consumer", 20
                ),
                null,
                1,
                2
        );
        var observedTicker = new AtomicReference<String>();
        var service = new EvaluateCompanyDetailRevenueMixShadowService(
                new StubCompanyReadPort(serving),
                ticker -> {
                    observedTicker.set(ticker);
                    return parity(false, analysis);
                },
                new CompanyRevenueMixComposer()
        );

        var report = service.evaluate(" nvda ");

        assertEquals("NVDA", observedTicker.get());
        assertTrue(report.contractCompatible());
        assertTrue(report.servingSnapshotMatched());
        assertTrue(report.shadowServeReady());
        assertFalse(report.directMigrationReady());
        assertEquals(Source.DIRECT_SEC_ACTUAL, report.composition().segmentSource());
        assertEquals(Source.BASELINE_FALLBACK, report.composition().geographySource());
        assertEquals("legacy note", CompanyRevenueMixLegacyProjection.from(serving).note());
    }

    @Test
    void doesNotMarkShadowReadyWhenDirectExtractionCoverageFailed() {
        var serving = CompanyRevenueMixComposerTest.research(true);
        var analysis = new CompanyRevenueMixAnalysis(
                CompanyRevenueMixComposerTest.breakdown(
                        io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown.Category.SEGMENT,
                        "Cloud", 80, "Consumer", 20
                ),
                null,
                1,
                2
        );
        var service = new EvaluateCompanyDetailRevenueMixShadowService(
                new StubCompanyReadPort(serving),
                ignored -> parity(true, analysis),
                new CompanyRevenueMixComposer()
        );

        var report = service.evaluate("NVDA");

        assertTrue(report.contractCompatible());
        assertFalse(report.shadowServeReady());
    }

    @Test
    void doesNotMarkShadowReadyAcrossALegacyRefreshBoundary() {
        var serving = CompanyRevenueMixComposerTest.research(true);
        var analysis = new CompanyRevenueMixAnalysis(
                CompanyRevenueMixComposerTest.breakdown(
                        io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown.Category.SEGMENT,
                        "Cloud", 80, "Consumer", 20
                ),
                null,
                1,
                2
        );
        var mismatched = parity(false, analysis);
        mismatched = new CompanyRevenueMixParityReport(
                mismatched.ticker(), mismatched.registryCik(), mismatched.selectedCik(),
                mismatched.scannedFilingCount(), mismatched.candidateFilingCount(),
                mismatched.analyzedFilingCount(), mismatched.dimensionalFactCount(),
                mismatched.migrationReady(), mismatched.directCoveragePassed(),
                mismatched.percentageValidationPassed(), mismatched.legacyCoveragePreserved(),
                mismatched.segmentActualAvailable(), mismatched.geographyActualAvailable(),
                mismatched.selectedFilingAccessions(), mismatched.extractionFailures(),
                mismatched.differences(),
                new CompanyRevenueMixLegacyRead(
                        "refreshed legacy note", mismatched.legacy().segment(), mismatched.legacy().geography()
                ),
                mismatched.spring()
        );
        var finalMismatched = mismatched;
        var service = new EvaluateCompanyDetailRevenueMixShadowService(
                new StubCompanyReadPort(serving),
                ignored -> finalMismatched,
                new CompanyRevenueMixComposer()
        );

        var report = service.evaluate("NVDA");

        assertFalse(report.servingSnapshotMatched());
        assertFalse(report.shadowServeReady());
    }

    private static CompanyRevenueMixParityReport parity(
            boolean extractionFailed,
            CompanyRevenueMixAnalysis analysis
    ) {
        var legacy = new CompanyRevenueMixLegacyRead(
                "legacy note",
                List.of(
                        entry("Legacy Platform", 60), entry("Legacy Service", 40)
                ),
                List.of(
                        entry("Legacy US", 70), entry("Legacy Other", 30)
                )
        );
        return new CompanyRevenueMixParityReport(
                "NVDA", "0001045810", "0001045810",
                10, 1, extractionFailed ? 0 : 1, analysis.dimensionalFactCount(),
                false,
                !extractionFailed,
                true,
                false,
                true,
                false,
                List.of("0001045810-26-000001"),
                extractionFailed ? List.of("0001045810-26-000001") : List.of(),
                extractionFailed ? List.of("filing[0001045810-26-000001].extraction") : List.of("geography.coverage"),
                legacy,
                analysis
        );
    }

    private static CompanyRevenueMixLegacyRead.Entry entry(String label, int percent) {
        return new CompanyRevenueMixLegacyRead.Entry(
                label, null, null, java.math.BigDecimal.valueOf(percent)
        );
    }

    private record StubCompanyReadPort(Research research) implements LoadCompanyReadPort {
        @Override
        public SearchResult search(String normalizedQuery, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SummaryResult summaries(List<String> normalizedTickers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Research detail(String normalizedTicker) {
            return research;
        }
    }
}
