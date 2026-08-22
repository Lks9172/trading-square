package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyIdentity;
import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.BooleanValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.port.out.CompanyRevenueMixUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import io.macrosquare.company.domain.model.CompanyRevenueMixDimension;
import io.macrosquare.company.domain.model.CompanyRevenueMixEvidence;
import io.macrosquare.company.domain.model.CompanyRevenueMixFact;
import io.macrosquare.company.domain.model.CompanyRevenueTotal;
import io.macrosquare.company.domain.model.CompanySubmissionsEvidence;
import io.macrosquare.company.domain.service.CompanyRevenueMixPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateCompanyRevenueMixParityServiceTest {

    private static final String CIK = "0001045810";
    private static final String QUARTER_ACCESSION = "0001045810-26-000040";
    private static final String ANNUAL_ACCESSION = "0001045810-26-000021";
    private static final String QUARTER_URL = "https://www.sec.gov/Archives/edgar/data/1045810/"
            + "000104581026000040/nvda-20260430.htm";
    private static final String ANNUAL_URL = "https://www.sec.gov/Archives/edgar/data/1045810/"
            + "000104581026000021/nvda-20260125.htm";

    @Test
    void reportsDirectActualSegmentAndGeographyCoverageWithoutUsingLegacyAsInput() {
        var report = service(false).evaluate(" nvda ");

        assertTrue(report.migrationReady());
        assertTrue(report.directCoveragePassed());
        assertTrue(report.percentageValidationPassed());
        assertTrue(report.legacyCoveragePreserved());
        assertTrue(report.segmentActualAvailable());
        assertTrue(report.geographyActualAvailable());
        assertEquals(2, report.candidateFilingCount());
        assertEquals(2, report.analyzedFilingCount());
        assertEquals(List.of(QUARTER_ACCESSION, ANNUAL_ACCESSION), report.selectedFilingAccessions());
        assertEquals(LocalDate.parse("2026-04-30"), report.spring().segment().periodEnd());
        assertEquals(LocalDate.parse("2026-01-25"), report.spring().geography().periodEnd());
        assertEquals(new BigDecimal("100.0"), report.spring().segment().entries().stream()
                .map(entry -> entry.percentOfTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals("legacy fallback", report.legacy().note());
    }

    @Test
    void marksTheSliceNotReadyWhenAnySelectedFilingCannotBeParsed() {
        var report = service(true).evaluate("NVDA");

        assertFalse(report.migrationReady());
        assertFalse(report.directCoveragePassed());
        assertEquals(1, report.analyzedFilingCount());
        assertEquals(List.of(QUARTER_ACCESSION), report.extractionFailures());
        assertTrue(report.differences().contains("filing[" + QUARTER_ACCESSION + "].extraction"));
    }

    @Test
    void selectsAForeignIssuerAnnualFormWhenNoDomesticQuarterExists() {
        var annual = new CompanyFilingEvidence(
                "0001193125-26-000001", LocalDate.parse("2026-03-01"), "20-F",
                "foreign-20251231.htm", "20-F", null,
                "https://www.sec.gov/Archives/edgar/data/1045810/000119312526000001/foreign-20251231.htm"
        );
        var submissions = new CompanySubmissionsEvidence(
                CIK, "TEST FOREIGN", List.of("NVDA"), List.of("Nasdaq"), "3674", List.of(annual)
        );
        var service = new EvaluateCompanyRevenueMixParityService(
                new StubCompanyReadPort(research(List.of(annual))),
                ticker -> new CompanyIdentity("NVDA", CIK, "TEST FOREIGN"),
                ignored -> submissions,
                ignored -> annualEvidence(),
                new CompanyRevenueMixPolicy()
        );

        var report = service.evaluate("NVDA");

        assertEquals(1, report.candidateFilingCount());
        assertEquals(List.of("0001193125-26-000001"), report.selectedFilingAccessions());
        assertTrue(report.segmentActualAvailable());
    }

    private static EvaluateCompanyRevenueMixParityService service(boolean failQuarter) {
        var filings = filings();
        var submissions = new CompanySubmissionsEvidence(
                CIK, "NVIDIA CORP", List.of("NVDA"), List.of("Nasdaq"), "3674", filings
        );
        return new EvaluateCompanyRevenueMixParityService(
                new StubCompanyReadPort(research(filings)),
                ticker -> new CompanyIdentity("NVDA", CIK, "NVIDIA CORP"),
                ignored -> submissions,
                url -> {
                    if (failQuarter && QUARTER_URL.equals(url)) {
                        throw new CompanyRevenueMixUnavailableException("failed");
                    }
                    return QUARTER_URL.equals(url) ? quarterEvidence() : annualEvidence();
                },
                new CompanyRevenueMixPolicy()
        );
    }

    private static List<CompanyFilingEvidence> filings() {
        return List.of(
                new CompanyFilingEvidence(
                        QUARTER_ACCESSION, LocalDate.parse("2026-05-01"), "10-Q",
                        "nvda-20260430.htm", "10-Q", null, QUARTER_URL
                ),
                new CompanyFilingEvidence(
                        ANNUAL_ACCESSION, LocalDate.parse("2026-02-25"), "10-K",
                        "nvda-20260125.htm", "10-K", null, ANNUAL_URL
                ),
                new CompanyFilingEvidence(
                        "0001045810-26-000051", LocalDate.parse("2026-05-20"), "8-K",
                        "nvda-20260520.htm", "8-K", null,
                        "https://www.sec.gov/Archives/edgar/data/1045810/000104581026000051/nvda.htm"
                )
        );
    }

    private static CompanyRevenueMixEvidence quarterEvidence() {
        var start = LocalDate.parse("2026-01-26");
        var end = LocalDate.parse("2026-04-30");
        return new CompanyRevenueMixEvidence(
                QUARTER_URL,
                List.of(
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Compute", 80, start, end),
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Graphics", 20, start, end)
                ),
                List.of(total(100, start, end))
        );
    }

    private static CompanyRevenueMixEvidence annualEvidence() {
        var start = LocalDate.parse("2025-01-27");
        var end = LocalDate.parse("2026-01-25");
        return new CompanyRevenueMixEvidence(
                ANNUAL_URL,
                List.of(
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Compute", 80, start, end),
                        fact(CompanyRevenueMixDimension.REPORTABLE_SEGMENT, "Graphics", 20, start, end),
                        fact(CompanyRevenueMixDimension.GEOGRAPHY, "United States", 70, start, end),
                        fact(CompanyRevenueMixDimension.GEOGRAPHY, "Other Countries", 30, start, end)
                ),
                List.of(total(100, start, end))
        );
    }

    private static CompanyRevenueMixFact fact(
            CompanyRevenueMixDimension dimension,
            String label,
            int value,
            LocalDate start,
            LocalDate end
    ) {
        return new CompanyRevenueMixFact(
                dimension,
                dimension == CompanyRevenueMixDimension.GEOGRAPHY
                        ? "Statement Geographical" : "Statement Business Segments",
                label,
                BigDecimal.valueOf(value),
                "USD",
                start,
                end
        );
    }

    private static CompanyRevenueTotal total(int value, LocalDate start, LocalDate end) {
        return new CompanyRevenueTotal(BigDecimal.valueOf(value), "USD", start, end);
    }

    private static Research research(List<CompanyFilingEvidence> filings) {
        var filingValues = filings.stream().map(filing -> object(
                "accessionNumber", text(filing.accessionNumber()),
                "filingDate", text(filing.filingDate().toString()),
                "form", text(filing.form()),
                "primaryDocument", nullableText(filing.primaryDocument()),
                "primaryDocDescription", nullableText(filing.primaryDocumentDescription()),
                "isEarningsRelated", new BooleanValue(false),
                "filingUrl", nullableText(filing.sourceUrl())
        )).map(StructuredValue.class::cast).toList();
        var segment = array(
                mixEntry("Compute", 80), mixEntry("Graphics", 20)
        );
        var geography = array(
                mixEntry("United States", 70), mixEntry("Other Countries", 30)
        );
        return new Research(
                object(
                        "ticker", text("NVDA"),
                        "cik", text(CIK),
                        "name", text("NVIDIA CORP"),
                        "exchange", text("Nasdaq"),
                        "sic", text("3674")
                ),
                object(),
                object(
                        "segmentGeoMixNote", text("legacy fallback"),
                        "segmentMix", segment,
                        "geoMix", geography
                ),
                object(), object(),
                new ArrayValue(filingValues), array(), array(),
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, array()
        );
    }

    private static ObjectValue mixEntry(String label, int percent) {
        return object(
                "label", text(label),
                "value", NullValue.INSTANCE,
                "unit", NullValue.INSTANCE,
                "percentOfTotal", number(percent)
        );
    }

    private static ObjectValue object(Object... entries) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        for (var index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], (StructuredValue) entries[index + 1]);
        }
        return new ObjectValue(fields);
    }

    private static ArrayValue array(StructuredValue... values) {
        return new ArrayValue(List.of(values));
    }

    private static TextValue text(String value) {
        return new TextValue(value);
    }

    private static StructuredValue nullableText(String value) {
        return value == null ? NullValue.INSTANCE : text(value);
    }

    private static NumberValue number(int value) {
        return new NumberValue(BigDecimal.valueOf(value));
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
