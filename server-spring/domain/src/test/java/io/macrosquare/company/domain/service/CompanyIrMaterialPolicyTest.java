package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFilingDocumentEvidence;
import io.macrosquare.company.domain.model.CompanyFilingEvidence;
import io.macrosquare.company.domain.model.CompanyIrMaterial;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyIrMaterialPolicyTest {

    private final CompanyIrMaterialPolicy policy = new CompanyIrMaterialPolicy();

    @Test
    void classifiesPrimaryReportsWithLegacyCompatibleRules() {
        var quarterly = filing("10-Q", "Quarterly report", "report.htm");
        var material = policy.primaryMaterial(quarterly, false).orElseThrow();

        assertEquals(CompanyIrMaterial.Type.QUARTERLY_REPORT, material.type());
        assertEquals(CompanyIrMaterial.Source.PRIMARY, material.source());
        assertEquals(CompanyIrMaterial.ContentType.HTML, material.contentType());
        assertTrue(policy.shouldRetain(material));

        var generic = policy.primaryMaterial(filing("8-K", "8-K", "event.htm"), false).orElseThrow();
        assertEquals(CompanyIrMaterial.Type.OTHER, generic.type());
        assertFalse(policy.shouldRetain(generic));
    }

    @Test
    void tracksExhibit99AndPresentationDocumentsFromTheIndex() {
        var filing = filing("8-K", "8-K", "event.htm");
        var release = new CompanyFilingDocumentEvidence(
                2, "EX-99.1", "earnings-release.htm", "EX-99.1", 1200L,
                "https://www.sec.gov/Archives/edgar/data/1/000000000126000001/earnings-release.htm"
        );
        var presentation = new CompanyFilingDocumentEvidence(
                3, "Investor Presentation", "deck.pdf", "EX-99.2", 2400L,
                "https://www.sec.gov/Archives/edgar/data/1/000000000126000001/deck.pdf"
        );

        assertEquals(
                CompanyIrMaterial.Type.EARNINGS_RELEASE,
                policy.indexedMaterial(filing, release).orElseThrow().type()
        );
        var deck = policy.indexedMaterial(filing, presentation).orElseThrow();
        assertEquals(CompanyIrMaterial.Type.PRESENTATION, deck.type());
        assertEquals(CompanyIrMaterial.ContentType.PDF, deck.contentType());

        var xbrlLinkbase = new CompanyFilingDocumentEvidence(
                4, "XBRL TAXONOMY EXTENSION PRESENTATION LINKBASE DOCUMENT",
                "company_pre.xml", "EX-101.PRE", 1000L,
                "https://www.sec.gov/Archives/edgar/data/1/000000000126000001/company_pre.xml"
        );
        assertFalse(policy.isTrackedDocument(xbrlLinkbase));
        assertTrue(policy.indexedMaterial(filing, xbrlLinkbase).isEmpty());

        var slideImage = new CompanyFilingDocumentEvidence(
                5, "presentation slide image", "deck001.jpg", "GRAPHIC", 5000L,
                "https://www.sec.gov/Archives/edgar/data/1/000000000126000001/deck001.jpg"
        );
        assertFalse(policy.isTrackedDocument(slideImage));
    }

    @Test
    void extractsAtMostTwoRelevantCompatibilitySentencesAndCapsTheSummary() {
        var text = "Intro. Revenue increased 20 percent during the quarter. "
                + "Guidance was raised for the full year. Unrelated sentence. "
                + "Free cash flow also improved.";

        var summary = policy.summarize(text).orElseThrow();

        assertTrue(summary.contains("Revenue increased"));
        assertTrue(summary.contains("Guidance was raised"));
        assertFalse(summary.contains("Free cash flow"));
        assertTrue(summary.length() <= 320);
    }

    @Test
    void distinguishesPlainTextDocumentsFromHtmlAndPdf() {
        assertEquals(CompanyIrMaterial.ContentType.TXT,
                policy.contentType("https://www.sec.gov/Archives/edgar/data/1/filing.txt"));
        assertEquals(CompanyIrMaterial.ContentType.HTML,
                policy.contentType("https://www.sec.gov/Archives/edgar/data/1/filing.htm"));
        assertEquals(CompanyIrMaterial.ContentType.PDF,
                policy.contentType("https://www.sec.gov/Archives/edgar/data/1/deck.pdf?download=1"));
    }

    private static CompanyFilingEvidence filing(String form, String description, String document) {
        return new CompanyFilingEvidence(
                "0000000001-26-000001",
                LocalDate.parse("2026-07-17"),
                form,
                document,
                description,
                "2.02,9.01",
                "https://www.sec.gov/Archives/edgar/data/1/000000000126000001/" + document
        );
    }
}
