package io.macrosquare.crypto.application.service;

import io.macrosquare.crypto.CryptoResearchFixture;
import io.macrosquare.crypto.application.model.CryptoPricePoint;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichCryptoResearchFreshnessTest {

    @Test
    void keepsLivePriceVisibleButBlocksActionsWhenSupportingEvidenceIsStale() {
        var service = new EnrichCryptoResearchService(
                ignored -> List.of(new CryptoPricePoint(LocalDate.of(2026, 8, 6), 64_627.11)),
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.enrich(CryptoResearchFixture.catalog());
        var bitcoin = result.items().getFirst();

        assertEquals("2026-08-06", bitcoin.market().asOf());
        assertEquals("2026-07-18", bitcoin.freshness().supportingEvidenceObservedOn());
        assertFalse(bitcoin.freshness().eligibleForDecisions());
        assertEquals("STALE", bitcoin.freshness().status());
        assertEquals("HOLD", bitcoin.buyScore().action());
        assertEquals(0, bitcoin.positionSizing().targetPositionPct());
        assertEquals("관찰 대기", result.marketRegime().action());
        assertEquals(0, result.marketRegime().targetTotalExposurePct());
    }

    @Test
    void preservesCalculatedActionsWhenBothPriceAndSupportingEvidenceAreCurrent() {
        var service = new EnrichCryptoResearchService(
                ignored -> List.of(new CryptoPricePoint(LocalDate.of(2026, 7, 19), 64_653.91)),
                Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.enrich(CryptoResearchFixture.catalog());

        assertTrue(result.freshness().eligibleForDecisions());
        assertEquals("BUY", result.items().getFirst().buyScore().action());
        assertEquals("공격 가능", result.marketRegime().action());
        assertEquals(15, result.marketRegime().targetTotalExposurePct());
    }

    @Test
    void usesTheOldestLastObservationSoOneFreshSeriesCannotHideAnotherStaleSeries() {
        var currentPoint = new io.macrosquare.crypto.application.model.CryptoResearchModels.TrendPoint(
                "2026-08-06", 60
        );
        var stalePoint = new io.macrosquare.crypto.application.model.CryptoResearchModels.TrendPoint(
                "2026-07-17", 10
        );
        var baseline = CryptoResearchFixture.research(true);
        var mixedCharts = new io.macrosquare.crypto.application.model.CryptoResearchModels.TrendCharts(
                List.of(currentPoint), List.of(currentPoint), List.of(stalePoint),
                List.of(currentPoint), List.of(currentPoint)
        );
        var mixed = new io.macrosquare.crypto.application.model.CryptoResearchModels.Research(
                baseline.profile(), baseline.market(), baseline.macro(), baseline.narrative(), baseline.bottomUp(),
                baseline.moat(), baseline.supplyPressure(), baseline.onchain(), baseline.flows(), mixedCharts,
                baseline.freshness(), baseline.buyScore(), baseline.bottomSignal(), baseline.positionSizing(),
                baseline.verdicts(), baseline.scenarios(), baseline.executionBridge()
        );
        var service = new EnrichCryptoResearchService(
                ignored -> List.of(new CryptoPricePoint(LocalDate.of(2026, 8, 6), 64_627.11)),
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.enrich(mixed);

        assertEquals("2026-07-17", result.freshness().supportingEvidenceObservedOn());
        assertFalse(result.freshness().eligibleForDecisions());
        assertEquals("HOLD", result.buyScore().action());
    }

    @Test
    void treatsAMissingRequiredSeriesAsUnknownAndBlocksExecution() {
        var baseline = CryptoResearchFixture.research(true);
        var missingSeries = new io.macrosquare.crypto.application.model.CryptoResearchModels.TrendCharts(
                baseline.trendCharts().btcDominanceProxy30d(), baseline.trendCharts().stablecoinMcap30d(),
                List.of(), baseline.trendCharts().altSeasonProxy30d(),
                baseline.trendCharts().exchangeNetflowProxy30d()
        );
        var incomplete = new io.macrosquare.crypto.application.model.CryptoResearchModels.Research(
                baseline.profile(), baseline.market(), baseline.macro(), baseline.narrative(), baseline.bottomUp(),
                baseline.moat(), baseline.supplyPressure(), baseline.onchain(), baseline.flows(), missingSeries,
                baseline.freshness(), baseline.buyScore(), baseline.bottomSignal(), baseline.positionSizing(),
                baseline.verdicts(), baseline.scenarios(), baseline.executionBridge()
        );
        var service = new EnrichCryptoResearchService(
                ignored -> List.of(new CryptoPricePoint(LocalDate.of(2026, 8, 6), 64_627.11)),
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.enrich(incomplete);

        assertEquals("UNKNOWN", result.freshness().status());
        assertFalse(result.freshness().eligibleForDecisions());
        assertEquals(0, result.positionSizing().targetPositionPct());
    }

    @Test
    void freshSupportingSeriesCannotReviveAPriceDerivedScoreFromAnOlderMarketDate() {
        var currentPoint = new io.macrosquare.crypto.application.model.CryptoResearchModels.TrendPoint(
                "2026-08-06", 60
        );
        var baseline = CryptoResearchFixture.research(true);
        var currentCharts = new io.macrosquare.crypto.application.model.CryptoResearchModels.TrendCharts(
                List.of(currentPoint), List.of(currentPoint), List.of(currentPoint),
                List.of(currentPoint), List.of(currentPoint)
        );
        var mixed = new io.macrosquare.crypto.application.model.CryptoResearchModels.Research(
                baseline.profile(), baseline.market(), baseline.macro(), baseline.narrative(), baseline.bottomUp(),
                baseline.moat(), baseline.supplyPressure(), baseline.onchain(), baseline.flows(), currentCharts,
                baseline.freshness(), baseline.buyScore(), baseline.bottomSignal(), baseline.positionSizing(),
                baseline.verdicts(), baseline.scenarios(), baseline.executionBridge()
        );
        var service = new EnrichCryptoResearchService(
                ignored -> List.of(new CryptoPricePoint(LocalDate.of(2026, 8, 6), 64_627.11)),
                Clock.fixed(Instant.parse("2026-08-06T00:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.enrich(mixed);

        assertFalse(result.freshness().eligibleForDecisions());
        assertEquals("HOLD", result.buyScore().action());
        assertEquals(0, result.bottomSignal().score());
        assertEquals("미충족", result.bottomSignal().state());
        assertNull(result.bottomSignal().confirmedBottom());
        assertTrue(result.freshness().explanation().contains("재계산하지 못해"));
    }
}
