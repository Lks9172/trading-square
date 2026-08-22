package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanySectorAssessment;
import io.macrosquare.company.domain.horizon.CompanyHorizonSignalPolicy;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecisionPolicy;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichCompanyResearchServiceTest {

    @Test
    void attemptsCorrectnessCriticalCoreBeforeReturningTheAsynchronousSeed() {
        var queued = new AtomicReference<Runnable>();
        var coreAttempted = new AtomicBoolean();
        var priceAttempted = new AtomicBoolean();
        var service = new EnrichCompanyResearchService(
                ticker -> {
                    coreAttempted.set(true);
                    throw new IllegalStateException("simulated direct core outage");
                },
                ticker -> {
                    priceAttempted.set(true);
                    throw new IllegalStateException("simulated direct price outage");
                },
                null,
                null,
                null,
                new CompanyRevenueMixComposer(),
                new CompanyHorizonSignalPolicy(),
                ticker -> Optional.of(sector()),
                new CompanyInvestmentDecisionPolicy(),
                queued::set,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(15),
                OperationalEventSink.noop()
        );

        var baseline = withQuote(
                CompanyRevenueMixComposerTest.research(true),
                CompanyRevenueMixComposerTest.object(
                        "symbol", CompanyRevenueMixComposerTest.text("NVDA"),
                        "price", CompanyRevenueMixComposerTest.number(999),
                        "date", CompanyRevenueMixComposerTest.text("2024-01-02")
                ));
        var result = service.enrich("nvda", baseline);

        assertTrue(coreAttempted.get());
        assertTrue(priceAttempted.get());
        var sector = (ObjectValue) result.sectorContext();
        assertEquals(76, number(sector, "rotationScore"));
        assertEquals(2, number(sector, "rotationRank"));
        assertEquals(90, number(sector, "rotationPercentile"));
        assertEquals("Leader", text(sector, "rotationLabel"));

        assertFalse(result.verdicts() instanceof ObjectValue verdicts
                && verdicts.fields().containsKey("investmentDecision"));
        assertEquals(NullValue.INSTANCE, result.reversalConfirmation());
        var quote = (ObjectValue) result.quote();
        assertEquals("NVDA", text(quote, "symbol"));
        assertEquals(NullValue.INSTANCE, quote.fields().get("price"));
        assertEquals(NullValue.INSTANCE, quote.fields().get("date"));
        var bottom = (ObjectValue) result.bottomSignal();
        assertEquals(NullValue.INSTANCE, bottom.fields().get("score"));
        assertEquals("데이터 부족", text(bottom, "state"));
        assertEquals(NullValue.INSTANCE, bottom.fields().get("confirmedBottom"));
        var chart = (ObjectValue) bottom.fields().get("chart");
        assertTrue(((ArrayValue) chart.fields().get("points")).values().isEmpty());
        assertTrue(((ArrayValue) chart.fields().get("markers")).values().isEmpty());
        var positionSizing = (ObjectValue) result.positionSizing();
        assertEquals("HOLD", text(positionSizing, "action"));
        assertNotNull(queued.get());
    }

    private static io.macrosquare.company.application.model.CompanyReadModels.Research withQuote(
            io.macrosquare.company.application.model.CompanyReadModels.Research source,
            ObjectValue quote
    ) {
        return new io.macrosquare.company.application.model.CompanyReadModels.Research(
                source.profile(), quote, source.financials(), source.score(), source.buyScore(),
                source.filings(), source.irMaterials(), source.highlights(), source.peerGroup(),
                source.bottleneck(), source.narrative(), source.capitalFlow(), source.cashFlowQuality(),
                source.multipleInsight(), source.guidanceInsight(), source.timeframeView(),
                source.correctionAssessment(), source.thesisMonitor(), source.reversalConfirmation(),
                source.sectorContext(), source.verdicts(), source.bottomSignal(), source.positionSizing(),
                source.executionBridge(), source.peers()
        );
    }

    private static CompanySectorAssessment sector() {
        return new CompanySectorAssessment(
                "technology",
                "기술",
                "SECTOR_XLK",
                "structural",
                64,
                62,
                53,
                18,
                61,
                null,
                71,
                76,
                2,
                11,
                90,
                75,
                95,
                65,
                68,
                null,
                "neutral",
                "LEADING",
                "Leader",
                "now",
                "이미 주도 구간",
                List.of("중기 상대강도가 확인됐습니다.")
        );
    }

    private static long number(ObjectValue parent, String key) {
        return ((io.macrosquare.company.application.model.CompanyReadModels.NumberValue)
                parent.fields().get(key)).value().longValue();
    }

    private static String text(ObjectValue parent, String key) {
        return ((TextValue) parent.fields().get(key)).value();
    }
}
