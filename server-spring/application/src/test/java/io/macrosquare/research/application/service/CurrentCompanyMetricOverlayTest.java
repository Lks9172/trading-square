package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.CurrentCompanyMetric;
import io.macrosquare.research.application.model.ResearchCatalogModels.CompanyItem;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDefinition;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CurrentCompanyMetricOverlayTest {

    @Test
    void capturedFinancialsNeverSurviveWhenCurrentMetricsExistOrArePending() {
        var source = new ThemeDetail(
                new ThemeDefinition("test", "Test", "", List.of("PG", "MISS"), List.of()),
                List.of(captured("PG", 304.97), captured("MISS", 250.0)),
                List.of(), null, "buy", "buy");
        var current = new CurrentCompanyMetric(
                "PG", 400_000_000_000.0, 78, 82, 76, "매수 우호", 79, 31,
                3.26, 24.5, 4.1, 62, 70, 22, 74, "CONVICTION", true,
                Instant.parse("2026-08-06T00:00:00Z"));

        var result = CurrentCompanyMetricOverlay.theme(source, Map.of("PG", current));

        var pg = result.items().stream().filter(item -> item.ticker().equals("PG")).findFirst().orElseThrow();
        var missing = result.items().stream().filter(item -> item.ticker().equals("MISS")).findFirst().orElseThrow();
        assertEquals(3.26, pg.revenueGrowthYoY().doubleValue(), 1e-9);
        assertEquals("확신", pg.confirmedBottomState());
        assertNull(missing.revenueGrowthYoY());
        assertEquals("현재 Spring 기업 지표 계산 대기 중", missing.error());
    }

    @Test
    void partialCurrentMetricsDoNotExposeCapturedScoresAsComparable() {
        var source = new ThemeDetail(
                new ThemeDefinition("test", "Test", "", List.of("ASML"), List.of()),
                List.of(captured("ASML", 30.0)), List.of(), null, "buy", "buy");
        var partial = new CurrentCompanyMetric(
                "ASML", 600_000_000_000.0, null, null, null, null, null, null,
                null, null, null, 50, 40, 30, 0, "UNMET", true,
                Instant.parse("2026-08-06T00:00:00Z"));

        var result = CurrentCompanyMetricOverlay.theme(source, Map.of("ASML", partial));

        var asml = result.items().getFirst();
        assertNull(asml.totalScore());
        assertNull(asml.buyScore());
        assertEquals("핵심 재무 지표가 비교 불가하여 기업/매수 점수를 보류함", asml.error());
    }

    private static CompanyItem captured(String ticker, double growth) {
        return new CompanyItem(
                ticker, ticker, 1_000_000_000, 99, 99, "매수 우호", 99, 20,
                growth, 10, 1, "TEST", 80, 80, 80, 10,
                "후보", 80, "후보", 1, null);
    }
}
