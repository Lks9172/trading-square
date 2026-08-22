package io.macrosquare.company.application.service;

import io.macrosquare.company.domain.model.CompanyFilingDocumentContent;
import io.macrosquare.company.domain.service.CompanyIrMaterialPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeCompanyFilingDocumentServiceTest {

    @Test
    void returnsBoundedDiagnosticsWithoutExposingTheCompleteDocument() {
        var text = "Investor Presentation revenue guidance was raised. " + "x".repeat(700);
        var service = new ProbeCompanyFilingDocumentService(
                ignored -> new CompanyFilingDocumentContent(
                        text,
                        CompanyFilingDocumentContent.Format.PDF,
                        90,
                        90,
                        false
                ),
                new CompanyIrMaterialPolicy()
        );

        var report = service.probe("https://www.sec.gov/Archives/edgar/data/1/deck.pdf");

        assertEquals(CompanyFilingDocumentContent.Format.PDF, report.format());
        assertEquals(90, report.totalPages());
        assertEquals(text.length(), report.textCharacters());
        assertEquals(500, report.preview().length());
        assertTrue(report.preview().startsWith("Investor Presentation"));
        assertNotNull(report.summary());
    }
}
