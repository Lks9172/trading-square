package io.macrosquare.execution.adapter.out.market;

import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.macrosquare.market.application.model.MarketReadModels.document;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnapshotWeeklyReviewContextAdapterTest {

    @Test
    void mapsOnlyWeeklyReviewInputsAndRecomputableEventDates() {
        var snapshots = mock(LoadMarketSnapshotProjectionPort.class);
        when(snapshots.loadCurrentOrSeed()).thenReturn(document(Map.of(
                "timestamp", new TextValue("2026-08-05T11:27:49Z"),
                "regime", object(Map.of("regime", new TextValue("BOND_VIGILANTE"), "score", number(69))),
                "allocation", object(Map.of("allocations", object(Map.of("cash", number(8), "gold", number(44))))),
                "signals", new ArrayValue(List.of(object(Map.of(
                        "asset", new TextValue("GOLD"),
                        "signal", new TextValue("BUY"),
                        "conditionsMet", number(5),
                        "conditionsTotal", number(7),
                        "reasons", new ArrayValue(List.of(new TextValue("✓ 방어 수요")))
                )))),
                "derived", object(Map.of(
                        "TAIL_RISK_LEVEL", indicator(2, "꼬리위험 고조"),
                        "LIQUIDITY_PLUMBING_SIGNAL", indicator(-2, "", false)
                )),
                "meta", object(Map.of(
                        "calendar", new ArrayValue(List.of(object(Map.of(
                                "date", new TextValue("2026-08-10"),
                                "name", new TextValue("CPI"),
                                "category", new TextValue("CPI"),
                                "importance", new TextValue("high")
                        )))),
                        "staleness", object(Map.of(
                                "WALCL", object(Map.of(
                                        "daysAgo", number(20), "frequency", new TextValue("주간"),
                                        "eligibleForSignals", new BooleanValue(false))),
                                "TREASURY_MARKETABLE_ISSUANCE", object(Map.of(
                                        "daysAgo", number(230), "frequency", new TextValue("분기"),
                                        "maximumAgeDays", number(270),
                                        "eligibleForSignals", new BooleanValue(true)))
                        ))
                ))
        )));

        var context = new SnapshotWeeklyReviewContextAdapter(snapshots).loadCurrent();

        assertEquals("BOND_VIGILANTE", context.regime());
        assertEquals(44, context.recommendedAllocations().get("gold"));
        assertEquals("✓ 방어 수요", context.signals().getFirst().reasons().getFirst());
        assertEquals(100, context.signals().getFirst().dataCoveragePct());
        assertEquals(LocalDate.parse("2026-08-10"), context.events().getFirst().date());
        assertTrue(context.warnings().contains("꼬리위험 고조"));
        assertTrue(context.warnings().stream().anyMatch(value -> value.contains("WALCL")));
        assertTrue(context.warnings().stream().noneMatch(value -> value.contains("LIQUIDITY_PLUMBING_SIGNAL")));
        assertTrue(context.warnings().stream().noneMatch(value -> value.contains("TREASURY_MARKETABLE_ISSUANCE")));
    }

    private static ObjectValue indicator(long value, String interpretation) {
        return interpretation.isBlank()
                ? object(Map.of("value", number(value)))
                : object(Map.of("value", number(value), "interpretation", new TextValue(interpretation)));
    }

    private static ObjectValue indicator(long value, String interpretation, boolean eligible) {
        var values = new java.util.LinkedHashMap<String, StructuredValue>();
        values.put("value", number(value));
        if (!interpretation.isBlank()) values.put("interpretation", new TextValue(interpretation));
        values.put("eligibleForSignals", new BooleanValue(eligible));
        return object(values);
    }

    private static ObjectValue object(Map<String, StructuredValue> values) {
        return new ObjectValue(values);
    }

    private static NumberValue number(long value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }
}
