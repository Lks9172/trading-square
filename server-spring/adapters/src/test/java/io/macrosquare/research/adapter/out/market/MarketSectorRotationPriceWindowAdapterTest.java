package io.macrosquare.research.adapter.out.market;

import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketSectorRotationPriceWindowAdapterTest {

    @Test
    void anchorsOnlyACompletedCommonSessionAndMeasuresExactTradingSessions() {
        var repository = new FakeObservations();
        var start = LocalDate.parse("2026-01-01");
        var keys = List.of("SPY_TR", "XLK_TR", "XLF_TR", "XLE_TR", "XLV_TR", "XLI_TR",
                "XLY_TR", "XLC_TR", "XLB_TR", "XLRE_TR", "XLU_TR", "XLP_TR");
        for (var key : keys) {
            var points = new ArrayList<MarketObservation>();
            for (var index = 0; index < 140; index++) {
                points.add(new MarketObservation(key, key, 100 + index,
                        start.plusDays(index), MarketDataSource.YAHOO));
            }
            repository.values.put(key, points);
        }
        var adapter = new MarketSectorRotationPriceWindowAdapter(repository);

        // Before the conservative 22:00 UTC close gate, the same UTC date is excluded.
        assertEquals(start.plusDays(9), adapter.latestCompletedCommonDate(
                Instant.parse("2026-01-11T20:00:00Z")).orElseThrow());
        assertEquals(start.plusDays(10), adapter.latestCompletedCommonDate(
                Instant.parse("2026-01-11T22:00:00Z")).orElseThrow());

        var result = adapter.loadForwardWindow(start.plusDays(5), 21).orElseThrow();
        assertEquals(start.plusDays(26), result.endOn());
        assertEquals(21, result.tradingSessions());
        assertEquals((126d / 105d - 1d) * 100d, result.benchmarkReturnPct(), 0.000001);
        assertEquals(11, result.sectorReturnsPct().size());
        assertTrue(adapter.loadForwardWindow(start.plusDays(100), 63).isEmpty());
    }

    private static final class FakeObservations implements MarketObservationRepository {
        private final Map<String, List<MarketObservation>> values = new LinkedHashMap<>();
        @Override public int save(List<MarketObservation> observations) { throw new UnsupportedOperationException(); }
        @Override public List<MarketObservation> loadLatest(MarketDataSource source) { return List.of(); }
        @Override public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
            return values.getOrDefault(key, List.of());
        }
    }
}
