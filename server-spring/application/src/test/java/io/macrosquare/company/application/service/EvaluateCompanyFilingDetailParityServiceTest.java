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
import io.macrosquare.company.application.port.out.CompanyFilingDetailUnavailableException;
import io.macrosquare.company.application.port.out.CompanyFilingDocumentUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.domain.model.CompanyFilingDocumentContent;
import io.macrosquare.company.domain.model.CompanyFilingDetailEvidence;
import io.macrosquare.company.domain.model.CompanyFilingDocumentEvidence;
import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import io.macrosquare.company.domain.model.CompanyIrMaterial;
import io.macrosquare.company.domain.model.CompanySubmissionsEvidence;
import io.macrosquare.company.domain.service.CompanyFilingClassificationPolicy;
import io.macrosquare.company.domain.service.CompanyGuidanceParsingPolicy;
import io.macrosquare.company.domain.service.CompanyIrMaterialPolicy;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluateCompanyFilingDetailParityServiceTest {

    private static final String CIK = "0001045810";
    private static final String TEN_Q_URL = "https://www.sec.gov/Archives/edgar/data/1045810/"
            + "000104581026000040/nvda-20260430.htm";
    private static final String ACCESSION = "0001045810-26-000051";
    private static final String RELEASE_URL = "https://www.sec.gov/Archives/edgar/data/1045810/"
            + "000104581026000051/q1fy27pr.htm";
    private static final String PDF_URL = "https://www.sec.gov/Archives/edgar/data/1045810/"
            + "000104581026000051/investor-deck.pdf";

    @Test
    void preservesLegacyPrimaryMetadataAndAddsDirectExhibit99Material() {
        var service = service(false);

        var report = service.evaluate(" nvda ");

        assertTrue(report.migrationReady());
        assertTrue(report.legacyMetadataPreserved());
        assertTrue(report.legacySummariesMatched());
        assertTrue(report.directCoveragePassed());
        assertTrue(report.directDiscoveryImprovement());
        assertFalse(report.exactLegacyMatch());
        assertEquals(2, report.scannedFilingCount());
        assertEquals(1, report.candidateFilingCount());
        assertEquals(List.of(ACCESSION), report.selectedFilingAccessions());
        assertEquals(1, report.legacyMaterialCount());
        assertEquals(2, report.springMaterialCount());
        assertEquals(1, report.directAttachmentCount());
        assertEquals(1, report.summarizedDirectAttachmentCount());
        assertTrue(report.pdfExtractionCoveragePassed());
        assertEquals(0, report.pdfMaterialCount());
        assertTrue(report.guidanceExtractionCoveragePassed());
        assertEquals(2, report.guidanceEligibleMaterialCount());
        assertEquals(2, report.guidanceAnalyzedMaterialCount());
        assertEquals(1, report.guidanceRelevantMaterialCount());
        assertEquals(1, report.structuredGuidanceMaterialCount());
        assertEquals(2, report.structuredGuidanceMetricCount());
        var guidance = report.guidance().getFirst().summary();
        assertEquals("raised", guidance.stance().value());
        assertEquals("percent", guidance.revenue().value().unit().value());
        assertEquals(0, guidance.revenue().value().min().compareTo(new java.math.BigDecimal("10")));
        assertEquals(0, guidance.revenue().value().max().compareTo(new java.math.BigDecimal("12")));
        assertEquals("usd", guidance.capex().value().unit().value());
        assertEquals(0, guidance.capex().value().min().compareTo(new java.math.BigDecimal("3000000000")));
        assertEquals(0, guidance.capex().value().max().compareTo(new java.math.BigDecimal("4000000000")));
        var direct = report.spring().stream()
                .filter(material -> material.source() == CompanyIrMaterial.Source.INDEX)
                .findFirst().orElseThrow();
        assertEquals(RELEASE_URL, direct.url());
        assertTrue(direct.summary().contains("raised revenue guidance"));
    }

    @Test
    void reportsIndexCoverageFailureWithoutDroppingTheLegacyCompatiblePrimaryMaterial() {
        var report = service(true).evaluate("NVDA");

        assertFalse(report.migrationReady());
        assertFalse(report.directCoveragePassed());
        assertTrue(report.legacyMetadataPreserved());
        assertEquals(List.of(ACCESSION), report.indexFailures());
        assertEquals(1, report.springMaterialCount());
    }

    @Test
    void parsesAndSummarizesPdfInvestorMaterialAsAnIntentionalEnrichment() {
        var report = service(false, true, false).evaluate("NVDA");

        assertTrue(report.migrationReady());
        assertTrue(report.pdfExtractionCoveragePassed());
        assertEquals(1, report.pdfMaterialCount());
        assertEquals(1, report.parsedPdfMaterialCount());
        assertEquals(1, report.summarizedPdfMaterialCount());
        assertEquals(2, report.directAttachmentCount());
        assertEquals(2, report.summarizedDirectAttachmentCount());
        assertTrue(report.guidanceExtractionCoveragePassed());
        assertEquals(3, report.guidanceEligibleMaterialCount());
        assertEquals(3, report.guidanceAnalyzedMaterialCount());
        assertEquals(2, report.guidanceRelevantMaterialCount());
        assertEquals(2, report.structuredGuidanceMaterialCount());
        assertEquals(3, report.structuredGuidanceMetricCount());
        var pdf = report.spring().stream()
                .filter(material -> material.contentType() == CompanyIrMaterial.ContentType.PDF)
                .findFirst().orElseThrow();
        assertTrue(pdf.summary().contains("Investor Presentation"));
    }

    @Test
    void failsPdfCoverageWithoutDroppingOtherFilingMaterials() {
        var report = service(false, true, true).evaluate("NVDA");

        assertFalse(report.migrationReady());
        assertFalse(report.pdfExtractionCoveragePassed());
        assertEquals(1, report.pdfMaterialCount());
        assertEquals(0, report.parsedPdfMaterialCount());
        assertFalse(report.guidanceExtractionCoveragePassed());
        assertEquals(3, report.guidanceEligibleMaterialCount());
        assertEquals(2, report.guidanceAnalyzedMaterialCount());
        assertEquals(List.of(PDF_URL), report.summaryFailures());
        assertEquals(3, report.springMaterialCount());
    }

    private static EvaluateCompanyFilingDetailParityService service(boolean failIndex) {
        return service(failIndex, false, false);
    }

    private static EvaluateCompanyFilingDetailParityService service(
            boolean failIndex,
            boolean includePdf,
            boolean failPdf
    ) {
        var evidence = new CompanySubmissionsEvidence(
                CIK,
                "NVIDIA CORP",
                List.of("NVDA"),
                List.of("Nasdaq"),
                "3674",
                List.of(
                        new CompanyFilingEvidence(
                                "0001045810-26-000040", LocalDate.parse("2026-04-30"), "10-Q",
                                "nvda-20260430.htm", "10-Q", null, TEN_Q_URL
                        ),
                        new CompanyFilingEvidence(
                                ACCESSION, LocalDate.parse("2026-05-20"), "8-K",
                                "nvda-20260520.htm", "8-K", "2.02,9.01",
                                "https://www.sec.gov/Archives/edgar/data/1045810/000104581026000051/nvda-20260520.htm"
                        )
                )
        );
        var documents = new ArrayList<CompanyFilingDocumentEvidence>();
        documents.add(new CompanyFilingDocumentEvidence(
                2, "EX-99.1", "q1fy27pr.htm", "EX-99.1", 274829L, RELEASE_URL
        ));
        if (includePdf) {
            documents.add(new CompanyFilingDocumentEvidence(
                    3, "Investor Presentation", "investor-deck.pdf", "EX-99.2", 4466113L, PDF_URL
            ));
        }
        var detail = new CompanyFilingDetailEvidence(
                CIK,
                ACCESSION,
                "https://www.sec.gov/Archives/edgar/data/1045810/000104581026000051/"
                        + ACCESSION + "-index.htm",
                documents
        );
        return new EvaluateCompanyFilingDetailParityService(
                new StubCompanyReadPort(research()),
                ticker -> new CompanyIdentity("NVDA", CIK, "NVIDIA CORP"),
                ignored -> evidence,
                (cik, accession) -> {
                    if (failIndex) throw new CompanyFilingDetailUnavailableException("failed");
                    return detail;
                },
                url -> {
                    if (failPdf && PDF_URL.equals(url)) {
                        throw new CompanyFilingDocumentUnavailableException("failed");
                    }
                    if (PDF_URL.equals(url)) {
                        return new CompanyFilingDocumentContent(
                                "Investor Presentation revenue guidance was raised to 10% to 12%.",
                                CompanyFilingDocumentContent.Format.PDF,
                                42,
                                42,
                                false
                        );
                    }
                    return new CompanyFilingDocumentContent(
                            TEN_Q_URL.equals(url)
                                    ? "Revenue increased."
                                    : "The company raised revenue guidance to between 10% and 12% "
                                            + "and expects capex between $3 and $4 billion.",
                            CompanyFilingDocumentContent.Format.HTML,
                            null,
                            null,
                            false
                    );
                },
                new CompanyFilingClassificationPolicy(),
                new CompanyIrMaterialPolicy(),
                new CompanyGuidanceParsingPolicy(),
                10,
                3,
                20
        );
    }

    private static Research research() {
        var filing = object(
                "accessionNumber", text("0001045810-26-000040"),
                "filingDate", text("2026-04-30"),
                "form", text("10-Q"),
                "primaryDocument", text("nvda-20260430.htm"),
                "primaryDocDescription", text("10-Q"),
                "isEarningsRelated", new BooleanValue(false),
                "filingUrl", text(TEN_Q_URL)
        );
        var ir = object(
                "title", text("10-Q"),
                "form", text("10-Q"),
                "filingDate", text("2026-04-30"),
                "url", text(TEN_Q_URL),
                "type", text("quarterly-report"),
                "source", text("primary"),
                "contentType", text("html"),
                "summary", text("Revenue increased.")
        );
        return new Research(
                object(
                        "ticker", text("NVDA"),
                        "cik", text(CIK),
                        "name", text("NVIDIA CORP"),
                        "exchange", text("Nasdaq"),
                        "sic", text("3674")
                ),
                object(), object(), object(), object(),
                array(filing), array(ir), array(),
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, array()
        );
    }

    private static ObjectValue object(Object... entries) {
        if (entries.length % 2 != 0) throw new IllegalArgumentException("entries must be pairs");
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
