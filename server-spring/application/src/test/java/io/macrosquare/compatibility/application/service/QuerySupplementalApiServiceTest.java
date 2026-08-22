package io.macrosquare.compatibility.application.service;

import io.macrosquare.compatibility.application.model.SupplementalApiModels;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.Document;
import io.macrosquare.compatibility.application.model.SupplementalApiModels.TextPayload;
import io.macrosquare.compatibility.application.port.out.LoadSupplementalApiPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuerySupplementalApiServiceTest {

    @Test
    void normalizesAndBoundsUserControlledQueries() {
        var stub = new StubPort();
        var service = new QuerySupplementalApiService(stub);

        service.companies(null, " nvda ", "ai-power", "technology", "0", "999");
        service.correlation("999", List.of("NASDAQ,GOLD", "DXY"));
        service.sectorBacktest("1");
        service.backtestPortfolio("bad");

        assertEquals("buy:NVDA:ai-power:technology:1:100", stub.companies);
        assertEquals("500:[NASDAQ, GOLD, DXY]", stub.correlation);
        assertEquals(3, stub.sectorYears);
        assertEquals(3, stub.portfolioYears);
    }

    @Test
    void rejectsTraversalAndUnboundedKeyCardinalityBeforeCallingTheAdapter() {
        var service = new QuerySupplementalApiService(new StubPort());
        assertThrows(IllegalArgumentException.class, () -> service.bottleneckTheme("../secret"));
        assertThrows(IllegalArgumentException.class, () -> service.correlation("60",
                java.util.stream.IntStream.range(0, 33).mapToObj(index -> "K" + index).toList()));
    }

    private static final class StubPort implements LoadSupplementalApiPort {
        private String companies;
        private String correlation;
        private int sectorYears;
        private int portfolioYears;

        private static Document empty() {
            return new Document(new SupplementalApiModels.ObjectValue(Map.of()));
        }

        @Override public Document loadSmartMoney() { return empty(); }
        @Override public Document loadSectorBacktest(int years) { sectorYears = years; return empty(); }
        @Override public Document loadBottleneckThemes() { return empty(); }
        @Override public Document loadBottleneckTheme(String id) { return empty(); }
        @Override public Document loadCompanies(String sort, String query, String themeId, String sectorId, int page, int pageSize) {
            companies = String.join(":", sort, query, themeId, sectorId, Integer.toString(page), Integer.toString(pageSize));
            return empty();
        }
        @Override public Document loadHighlights() { return empty(); }
        @Override public Document loadEarnings() { return empty(); }
        @Override public Document loadCorrelation(int lookback, List<String> keys) { correlation = lookback + ":" + keys; return empty(); }
        @Override public Document loadDomesticReports() { return empty(); }
        @Override public Document loadWeeklyReportJson() { return empty(); }
        @Override public TextPayload loadWeeklyReportText() { return new TextPayload(""); }
        @Override public Document loadBacktestSummary() { return empty(); }
        @Override public Document loadBacktestPortfolio(int years) { portfolioYears = years; return empty(); }
        @Override public Document loadBacktestUserPlan(int years) { return empty(); }
    }
}
