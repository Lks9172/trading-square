package io.macrosquare.execution.application.service;

import io.macrosquare.execution.application.model.WeeklyReviewMarketContext;
import io.macrosquare.execution.application.model.WeeklyReviewMarketContext.MarketEvent;
import io.macrosquare.execution.application.model.WeeklyReviewMarketContext.MarketSignal;
import io.macrosquare.execution.application.port.out.InvestmentPlanRepository;
import io.macrosquare.execution.application.port.out.TradeLogRepository;
import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.service.PortfolioAllocationPolicy;
import io.macrosquare.execution.domain.service.WeeklyPlanReviewPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryWeeklyReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T12:00:00Z");

    @Test
    void buildsCurrentReportAndNormalizesLegacyKrwHoldings() {
        var plan = new InvestmentPlan(
                InvestmentHorizon.MEDIUM, 12, 25, 90, 15, 25, 15, 1_000_000,
                Map.of("cash", 6_000_000d, "gold", 4_000_000d), 10_000_000L,
                null, null, null, null, null, null, null, null, NOW
        );
        var context = new WeeklyReviewMarketContext(
                NOW,
                "BOND_VIGILANTE",
                69,
                Map.of("cash", 20, "gold", 80),
                List.of(new MarketSignal("GOLD", "BUY", 5, 7, 100, List.of(),
                        List.of("✓ 방어 국면 수요"),
                        List.of("⚠ 액션 상한: 추세 회복 확인 우선"))),
                List.of("유동성 흡수 우위"),
                List.of(
                        new MarketEvent(LocalDate.parse("2026-07-28"), "지난 FOMC", "FOMC", "high"),
                        new MarketEvent(LocalDate.parse("2026-08-10"), "CPI", "CPI", "high")
                )
        );
        var service = new QueryWeeklyReviewService(
                new FixedPlanRepository(plan),
                new EmptyTradeLogRepository(),
                () -> context,
                new WeeklyPlanReviewPolicy(new PortfolioAllocationPolicy()),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        var report = service.review();

        assertEquals(LocalDate.parse("2026-07-30"), report.periodFrom());
        assertEquals(LocalDate.parse("2026-08-05"), report.periodTo());
        assertEquals("5/7", report.keySignals().getFirst().met());
        assertEquals(1, report.nextEvents().size());
        assertEquals(5, report.nextEvents().getFirst().dday());
        assertEquals(60, report.holdings().percentages().get("cash"));
        assertTrue(report.warnings().stream().anyMatch(value -> value.contains("KRW 보유금액")));
        assertTrue(report.topReasons().getFirst().contains("추세 회복 확인 우선"));
        assertTrue(report.ruleViolations().stream().anyMatch(value -> value.contains("gold 40%p")));
        assertFalse(report.text().contains("6000000%"));
        assertTrue(report.text().contains("현재 Spring 스냅샷/투자계획 기준"));
    }

    private record FixedPlanRepository(InvestmentPlan plan) implements InvestmentPlanRepository {
        @Override
        public Optional<InvestmentPlan> load() {
            return Optional.of(plan);
        }

        @Override
        public InvestmentPlan save(InvestmentPlan value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PlanMutation updateAtomically(InvestmentPlan initialPlan, UnaryOperator<InvestmentPlan> mutation) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class EmptyTradeLogRepository implements TradeLogRepository {
        @Override
        public void append(TradeLogEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<TradeLogEntry> recent(int limit) {
            return List.of();
        }
    }
}
