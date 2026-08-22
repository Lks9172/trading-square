package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyIdentity;
import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.BooleanValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.port.out.CompanySubmissionsUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import io.macrosquare.company.domain.model.CompanySubmissionsEvidence;
import io.macrosquare.company.domain.service.CompanyFilingClassificationPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateCompanySubmissionsParityServiceTest {

    @Test
    void directlyLoadsTheServingContinuityCikAndMatchesProfileAndFilingMetadata() {
        var predecessor = "0000034088";
        var successor = "0002115436";
        var observedCik = new AtomicReference<String>();
        var evidence = evidence(predecessor, "XOM", "EXXON MOBIL CORP", "8-K");
        var service = new EvaluateCompanySubmissionsParityService(
                new StubCompanyReadPort(research("XOM", predecessor, "EXXON MOBIL CORP", "8-K", true)),
                ticker -> new CompanyIdentity(
                        "XOM", successor, "ExxonMobil Holdings Corp",
                        List.of(successor, predecessor), List.of(successor, predecessor)
                ),
                cik -> {
                    observedCik.set(cik);
                    return evidence;
                },
                new CompanyFilingClassificationPolicy(),
                10
        );

        var report = service.evaluate(" xom ");

        assertTrue(report.allMatched());
        assertTrue(report.profileMatched());
        assertTrue(report.filingsMatched());
        assertEquals(predecessor, observedCik.get());
        assertEquals(successor, report.registryCik());
        assertEquals(predecessor, report.selectedCik());
        assertEquals(List.of(predecessor, successor), report.submissionCikCandidates());
        assertEquals(1, report.comparedFilingCount());
        assertEquals(1, report.legacyEnrichedFilingCount());
    }

    @Test
    void reportsExactFilingFieldDriftAndKeepsDotTickerForTheLegacyRead() {
        var observedTicker = new AtomicReference<String>();
        var cik = "0001067983";
        var service = new EvaluateCompanySubmissionsParityService(
                new StubCompanyReadPort(research("BRK-B", cik, "BERKSHIRE HATHAWAY INC", "8-K", false), observedTicker),
                ticker -> new CompanyIdentity("BRK-B", cik, "BERKSHIRE HATHAWAY INC"),
                ignored -> evidence(cik, "BRK-B", "BERKSHIRE HATHAWAY INC", "10-Q"),
                new CompanyFilingClassificationPolicy(),
                10
        );

        var report = service.evaluate("brk.b");

        assertFalse(report.allMatched());
        assertFalse(report.filingsMatched());
        assertEquals(List.of("filings[0].form", "filings[0].isEarningsRelated"), report.differences());
        assertEquals("BRK.B", observedTicker.get());
        assertEquals("BRK-B", report.ticker());
    }

    @Test
    void fallsBackToTheNextDirectContinuityCandidateWhenTheFirstIsUnavailable() {
        var first = "0002115436";
        var second = "0000034088";
        var service = new EvaluateCompanySubmissionsParityService(
                new StubCompanyReadPort(research("XOM", "0000000001", "EXXON MOBIL CORP", "8-K", false)),
                ticker -> new CompanyIdentity(
                        "XOM", first, "ExxonMobil Holdings Corp",
                        List.of(first, second), List.of(first, second)
                ),
                cik -> {
                    if (first.equals(cik)) throw new CompanySubmissionsUnavailableException("unavailable");
                    return evidence(second, "XOM", "EXXON MOBIL CORP", "8-K");
                },
                new CompanyFilingClassificationPolicy(),
                10
        );

        var report = service.evaluate("XOM");

        assertEquals(second, report.selectedCik());
        assertFalse(report.profileMatched());
        assertEquals(List.of("profile.cik"), report.differences());
    }

    private static CompanySubmissionsEvidence evidence(
            String cik,
            String ticker,
            String name,
            String form
    ) {
        return new CompanySubmissionsEvidence(
                cik,
                name,
                List.of(ticker),
                List.of("NYSE"),
                "2911",
                List.of(new CompanyFilingEvidence(
                        "0000034088-26-000001",
                        LocalDate.parse("2026-07-17"),
                        form,
                        "xom-20260717.htm",
                        "Item 2.02 Results of Operations",
                        "https://www.sec.gov/Archives/edgar/data/34088/000003408826000001/xom-20260717.htm"
                ))
        );
    }

    private static Research research(
            String ticker,
            String cik,
            String name,
            String form,
            boolean includeEnrichment
    ) {
        var filingFields = new LinkedHashMap<String, StructuredValue>();
        filingFields.put("accessionNumber", text("0000034088-26-000001"));
        filingFields.put("filingDate", text("2026-07-17"));
        filingFields.put("form", text(form));
        filingFields.put("primaryDocument", text("xom-20260717.htm"));
        filingFields.put("primaryDocDescription", text("Item 2.02 Results of Operations"));
        filingFields.put("isEarningsRelated", new BooleanValue("8-K".equals(form)));
        filingFields.put("filingUrl", text(
                "https://www.sec.gov/Archives/edgar/data/34088/000003408826000001/xom-20260717.htm"
        ));
        if (includeEnrichment) filingFields.put("summary", text("legacy detail summary"));
        return new Research(
                object(
                        "ticker", text(ticker),
                        "cik", text(cik),
                        "name", text(name),
                        "exchange", text("NYSE"),
                        "sic", text("2911")
                ),
                object(), object(), object(), object(),
                new ArrayValue(List.of(new ObjectValue(filingFields))),
                array(), array(), NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, array()
        );
    }

    private static ObjectValue object(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("entries must be key/value pairs");
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

    private static final class StubCompanyReadPort implements LoadCompanyReadPort {
        private final Research research;
        private final AtomicReference<String> observedTicker;

        private StubCompanyReadPort(Research research) {
            this(research, new AtomicReference<>());
        }

        private StubCompanyReadPort(Research research, AtomicReference<String> observedTicker) {
            this.research = research;
            this.observedTicker = observedTicker;
        }

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
            observedTicker.set(normalizedTicker);
            return research;
        }
    }
}
