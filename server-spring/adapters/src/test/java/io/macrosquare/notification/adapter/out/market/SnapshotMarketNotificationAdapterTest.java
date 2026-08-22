package io.macrosquare.notification.adapter.out.market;

import io.macrosquare.execution.application.port.in.QueryWeeklyReviewUseCase;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static io.macrosquare.market.application.model.MarketReadModels.document;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnapshotMarketNotificationAdapterTest {

    @Test
    void carriesLiquidityPlumbingAndEmergingMarketSpilloverIntoMarketNotifications() {
        var snapshotStore = mock(LoadMarketSnapshotProjectionPort.class);
        var weeklyReview = mock(QueryWeeklyReviewUseCase.class);
        when(snapshotStore.loadCurrentOrSeed()).thenReturn(document(Map.of(
                "timestamp", new TextValue("2026-07-26T10:00:00Z"),
                "regime", object(Map.of(
                        "regime", new TextValue("RISK_ON"),
                        "score", number(74)
                )),
                "allocation", object(Map.of(
                        "allocations", object(Map.of("cash", number(30), "nasdaq", number(70))),
                        "leverageAllowed", new BooleanValue(false)
                )),
                "signals", new ArrayValue(List.of(object(Map.of(
                        "asset", new TextValue("NASDAQ"),
                        "signal", new TextValue("BUY"),
                        "conditionsMet", number(5),
                        "conditionsTotal", number(7)
                )))),
                "meta", object(Map.of(
                        "collectionHealth", object(Map.of(
                                "status", new TextValue("DEGRADED"),
                                "sources", object(Map.of(
                                        "SENTIMENT", object(Map.of(
                                                "status", new TextValue("DEGRADED"),
                                                "failureKeys", new ArrayValue(List.of(
                                                        new TextValue("NAAIM_EXPOSURE")))
                                        ))
                                ))
                        )),
                        "inputFreshness", object(Map.of(
                                "rawExcluded", number(1),
                                "derivedExcluded", number(2)
                        ))
                )),
                "derived", object(Map.ofEntries(
                        Map.entry("LIQUIDITY_PLUMBING_SIGNAL", indicator(2)),
                        Map.entry("LIQUIDITY_PLUMBING_BULLISH_AXES", indicator(3)),
                        Map.entry("LIQUIDITY_PLUMBING_BEARISH_AXES", indicator(0)),
                        Map.entry("LIQUIDITY_PLUMBING_NEUTRAL_AXES", indicator(0)),
                        Map.entry("LIQUIDITY_PLUMBING_CONFIDENCE", indicator(100)),
                        Map.entry("WRESBAL_LEVEL_TN", decimalIndicator(2.944059)),
                        Map.entry("TGA_LIQUIDITY_CONTRIBUTION_4W_BN", indicator(75)),
                        Map.entry("RRP_LIQUIDITY_CONTRIBUTION_4W_BN", indicator(18)),
                        Map.entry("NET_LIQUIDITY_IMPULSE_4W_BN", indicator(120)),
                        Map.entry("NET_LIQUIDITY_IMPULSE_STATE", indicator(2)),
                        Map.entry("NET_LIQUIDITY_ACCELERATION_4W_BN", indicator(80)),
                        Map.entry("NET_LIQUIDITY_TURN_SIGNAL", indicator(1)),
                        Map.entry("LIQUIDITY_TRANSMISSION_STRESS_SCORE", indicator(2)),
                        Map.entry("LIQUIDITY_TRANSMISSION_COVERAGE", indicator(100)),
                        Map.entry("TGA_LAGGED_ISSUANCE_CONTEXT", indicator(1, "2026-08-12", true)),
                        Map.entry("TREASURY_ISSUANCE_DIRECTION", indicator(1, "2026-01-01", true)),
                        Map.entry("RRP_BUFFER_LOW", indicator(1)),
                        Map.entry("DOLLAR_LIQUIDITY_SPILLOVER_SIGNAL", indicator(1)),
                        Map.entry("DOLLAR_LIQUIDITY_SPILLOVER_CONFIDENCE", indicator(75)),
                        Map.entry("NASDAQ_MACD_POSITION", indicator(1, "2026-07-25", true)),
                        Map.entry("NASDAQ_MACD_CROSS", indicator(1, "2026-07-25", true)),
                        Map.entry("NASDAQ_MACD_CROSS_AGE", indicator(2, "2026-07-25", true)),
                        Map.entry("NASDAQ_MACD_HISTOGRAM_STATE", indicator(2, "2026-07-25", true)),
                        Map.entry("NASDAQ_MACD_DIVERGENCE", indicator(-1, "2026-07-25", true)),
                        Map.entry("NASDAQ_MACD_DIVERGENCE_ACTIVE", indicator(1, "2026-07-25", true)),
                        Map.entry("NASDAQ_MACD_DIVERGENCE_AGE", indicator(1, "2026-07-25", true))
                ))
        )));
        var adapter = new SnapshotMarketNotificationAdapter(snapshotStore, weeklyReview);

        var snapshot = adapter.loadCurrent();

        assertTrue(snapshot.breadthLines().contains(
                "   • 현재 유동성 3축: 전축 공급 (+2) / 공급 3·흡수 0·중립 0 / 데이터 100%"));
        assertTrue(snapshot.breadthLines().contains(
                "   • 은행 준비금: 2.94T / ⚠ 3T 모니터링선 아래 (공식 안전선·수익 신호 아님)"));
        assertTrue(snapshot.breadthLines().contains(
                "   • 4주 배관 기여: TGA +75B / ON RRP +18B (위험자산 직접 순유입 아님)"));
        assertTrue(snapshot.breadthLines().contains(
                "   • 미국 순유동성 4주: 강한 확장 / +120B / 가속 +80B / 4주 구간 확장 전환 ON"));
        assertTrue(snapshot.breadthLines().stream().anyMatch(line -> line.contains("유동성 전달 스트레스 2/3")));
        assertTrue(snapshot.breadthLines().stream().anyMatch(line -> line.contains("TGA 감소와")));
        assertTrue(snapshot.breadthLines().stream().anyMatch(line -> line.contains("분기 기준 2026-01-01")));
        assertTrue(snapshot.breadthLines().stream().anyMatch(line -> line.contains("ON RRP 저잔액")));
        assertTrue(snapshot.breadthLines().contains(
                "   • 달러→신흥국: 전이 우호 (+1) / 데이터 75%"));
        assertTrue(snapshot.breadthLines().contains(
                "   • 수집 심리: 부분 성공 / 결측 NAAIM_EXPOSURE"));
        assertTrue(snapshot.breadthLines().contains(
                "   • 신호 신선도: 3개 산식 제외 (원천 1·파생 2)"));
        assertTrue(snapshot.breadthLines().contains(
                "   • NASDAQ MACD 일봉: 2026-07-25 기준 · 상방 골든크로스(2일 전)"
                        + " · 시그널 위 · 양(확대) · 하락 다이버전스 ON(1일 전)"));
        assertTrue(snapshot.breadthLines().contains(
                "   · MACD/다이버전스는 후행 보조지표이며 단독 매수·매도 신호가 아님"));
        assertEquals(100, snapshot.signals().getFirst().dataCoveragePct());
    }

    @Test
    void neverPublishesAnIneligibleRetainedLiquidityValueAsCurrent() {
        var snapshotStore = mock(LoadMarketSnapshotProjectionPort.class);
        var weeklyReview = mock(QueryWeeklyReviewUseCase.class);
        when(snapshotStore.loadCurrentOrSeed()).thenReturn(document(Map.of(
                "timestamp", new TextValue("2026-08-16T00:00:00Z"),
                "regime", object(Map.of("regime", new TextValue("UNKNOWN"), "score", number(0))),
                "allocation", object(Map.of(
                        "allocations", object(Map.of()), "leverageAllowed", new BooleanValue(false))),
                "signals", new ArrayValue(List.of()),
                "meta", object(Map.of()),
                "derived", object(Map.of(
                        "LIQUIDITY_PLUMBING_SIGNAL", indicator(2, "2026-07-01", false),
                        "NET_LIQUIDITY_IMPULSE_4W_BN", indicator(120, "2026-07-01", false)
                ))
        )));

        var snapshot = new SnapshotMarketNotificationAdapter(snapshotStore, weeklyReview).loadCurrent();

        assertTrue(snapshot.breadthLines().stream().noneMatch(line -> line.contains("현재 유동성 3축")));
        assertTrue(snapshot.breadthLines().stream().noneMatch(line -> line.contains("미국 순유동성 4주")));
    }

    private static ObjectValue object(Map<String, io.macrosquare.market.application.model.MarketReadModels.StructuredValue> values) {
        return new ObjectValue(values);
    }

    private static ObjectValue indicator(long value) {
        return object(Map.of("value", number(value)));
    }

    private static ObjectValue indicator(long value, String date, boolean eligible) {
        return object(Map.of(
                "value", number(value),
                "date", new TextValue(date),
                "eligibleForSignals", new BooleanValue(eligible)
        ));
    }

    private static NumberValue number(long value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    private static ObjectValue decimalIndicator(double value) {
        return object(Map.of("value", new NumberValue(BigDecimal.valueOf(value))));
    }
}
