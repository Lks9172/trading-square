package io.macrosquare.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PublicApiRouteCoverageTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void ownsEveryLegacyPublicApiRouteBeforeTrafficCutover() {
        var actual = new HashSet<String>();
        mappings.getHandlerMethods().forEach((mapping, method) -> {
            var methods = mapping.getMethodsCondition().getMethods();
            mapping.getPatternValues().forEach(pattern -> methods.forEach(httpMethod ->
                    actual.add(httpMethod.name() + " " + pattern)));
        });

        var expected = Set.of(
                "GET /api/snapshot",
                "POST /api/snapshot",
                "POST /api/refresh",
                "GET /api/history/coverage",
                "GET /api/history/{source}/{key}",
                "GET /api/history-series",
                "GET /api/smart-money",
                "GET /api/institutional-flows",
                "GET /api/policy-intelligence",
                "GET /api/research/peers/{ticker}",
                "GET /api/dart/disclosures/{stockCode}",
                "GET /api/company/{ticker}",
                "GET /api/company-summaries",
                "GET /api/company-search",
                "GET /api/research/themes",
                "GET /api/research/sectors/backtest",
                "GET /api/research/sectors/backtest/current",
                "GET /api/research/sectors",
                "GET /api/research/themes/{id}",
                "GET /api/research/sectors/{id}",
                "GET /api/bottleneck/themes",
                "GET /api/bottleneck/themes/{id}",
                "GET /api/narrative/themes",
                "GET /api/narrative/themes/{id}",
                "GET /api/narrative/overview",
                "GET /api/research/crypto",
                "GET /api/research/crypto/{symbol}",
                "GET /api/research/companies",
                "GET /api/research/highlights",
                "GET /api/earnings",
                "GET /api/correlation",
                "POST /api/execution-plan/tranche",
                "GET /api/execution-plan/tranche",
                "DELETE /api/execution-plan/tranche/{asset}",
                "GET /api/execution-plan/purchasing-power",
                "GET /api/plan",
                "POST /api/plan",
                "GET /api/trade-log",
                "POST /api/trade-log",
                "GET /api/domestic-reports",
                "GET /api/weekly-report",
                "GET /api/health",
                "GET /api/backtest/summary",
                "GET /api/backtest/portfolio",
                "GET /api/backtest/user-plan"
        );

        var missing = new HashSet<>(expected);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(), () -> "Missing public API routes: " + missing);
    }
}
