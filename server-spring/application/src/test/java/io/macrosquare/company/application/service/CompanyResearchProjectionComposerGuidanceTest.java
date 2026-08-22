package io.macrosquare.company.application.service;

import io.macrosquare.company.domain.model.CompanyGuidanceAnalysis;
import io.macrosquare.company.domain.model.CompanyGuidanceSummary;
import io.macrosquare.company.domain.model.CompanyIrMaterial;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanyResearchProjectionComposerGuidanceTest {

    @Test
    void sameDayExplicitEarningsExhibitWinsOverAccountingLanguageInTenQ() {
        var date = LocalDate.parse("2026-08-03");
        var accounting = analysis(
                "10-Q", date, "https://example.test/10q",
                new CompanyGuidanceSummary(
                        CompanyGuidanceSummary.Stance.UNCLEAR,
                        null, null, null, null, List.of("accounting estimate")
                )
        );
        var raised = analysis(
                "8-K", date, "https://example.test/exhibit",
                new CompanyGuidanceSummary(
                        CompanyGuidanceSummary.Stance.RAISED,
                        null, null, null, null, List.of("raising full-year guidance")
                )
        );

        var selected = CompanyResearchProjectionComposer.latestBestGuidance(
                List.of(raised, accounting)
        ).orElseThrow();

        assertEquals("https://example.test/exhibit", selected.url());
        assertEquals(CompanyGuidanceSummary.Stance.RAISED, selected.summary().stance());
    }

    private static CompanyGuidanceAnalysis analysis(
            String form,
            LocalDate date,
            String url,
            CompanyGuidanceSummary summary
    ) {
        return new CompanyGuidanceAnalysis(
                form, form, date, url, CompanyIrMaterial.ContentType.HTML, summary
        );
    }
}
