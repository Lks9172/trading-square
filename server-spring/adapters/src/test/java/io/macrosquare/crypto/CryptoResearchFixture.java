package io.macrosquare.crypto;

import io.macrosquare.crypto.application.model.CryptoResearchModels.AssetSummary;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomChart;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomMetric;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomSignal;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomUp;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BuyScore;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ChartMarker;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ChartPoint;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ConfirmedBottom;
import io.macrosquare.crypto.application.model.CryptoResearchModels.DecisionFreshness;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ExecutionBridge;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Flows;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Macro;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Market;
import io.macrosquare.crypto.application.model.CryptoResearchModels.MarketRegime;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Moat;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Narrative;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Onchain;
import io.macrosquare.crypto.application.model.CryptoResearchModels.OneLiners;
import io.macrosquare.crypto.application.model.CryptoResearchModels.PositionSizing;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Profile;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Scenarios;
import io.macrosquare.crypto.application.model.CryptoResearchModels.SupplyPressure;
import io.macrosquare.crypto.application.model.CryptoResearchModels.TrendCharts;
import io.macrosquare.crypto.application.model.CryptoResearchModels.TrendPoint;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Verdicts;

import java.math.BigDecimal;
import java.util.List;

public final class CryptoResearchFixture {

    private CryptoResearchFixture() {
    }

    public static Catalog catalog() {
        return new Catalog(
                List.of(research(true)),
                new MarketRegime(
                        "RISK_ON", "공격 가능", "혼조장", 15,
                        "코인장 자체는 열려 있습니다.", List.of("스테이블 수요 확장")
                ),
                List.of(new AssetSummary("BTC", "Bitcoin", "디지털 금", "디지털 금")),
                freshness()
        );
    }

    public static Research research(boolean withExecutionBridge) {
        var trendPoint = new TrendPoint("2026-07-18", new BigDecimal("56.5"));
        return new Research(
                new Profile(
                        "BTC", "BTC-USD", "bitcoin", "bitcoin", "Bitcoin", "디지털 금",
                        "디지털 금", "GOLD", 88, 82, 90, 89,
                        List.of("유동성"), List.of("기관 수요"), List.of("달러 강세")
                ),
                new Market(
                        "2026-07-19", new BigDecimal("64653.91"), new BigDecimal("1.4"),
                        new BigDecimal("1.8"), new BigDecimal("-14.8"), new BigDecimal("-18.5"),
                        new BigDecimal("1.7"), new BigDecimal("-48.2"), new BigDecimal("10.4")
                ),
                new Macro(72, 55, 75, "중립", "거시는 중립입니다.", List.of("유동성 우호")),
                new Narrative("디지털 금", "EARLY", 28, "과열 전 단계입니다."),
                new BottomUp(82, 90, 89, "기초 체력이 강합니다.", List.of("기관 수요"), List.of("달러")),
                new Moat("기관/디지털 금 해자", 88, "해자가 선명합니다.", List.of("ETF 접근성")),
                new SupplyPressure(
                        "낮음", "낮음", 85, 0L, new BigDecimal("95.5"),
                        "공급 압력은 관리 가능합니다.", List.of("유통 비율 95.5%")
                ),
                new Onchain(
                        new BigDecimal("5328263349.336395"), new BigDecimal("2.5"), 6168L,
                        new BigDecimal("346.8"), 100L, null, 95,
                        "온체인이 강합니다.", List.of("TVL 증가")
                ),
                new Flows(
                        85, "확장", new BigDecimal("13.5"), 62, "중립", "혼조입니다.",
                        50, "균형", new BigDecimal("56.5"), "강함",
                        new BigDecimal("132296394.775"), new BigDecimal("75673283.5449996"),
                        "유출 우세", "축적 여지가 있습니다.", "낮음", "낮음",
                        new BigDecimal("1.2"), "흐름은 균형입니다.", List.of("스테이블 확장")
                ),
                new TrendCharts(
                        List.of(trendPoint), List.of(trendPoint), List.of(trendPoint),
                        List.of(trendPoint), List.of(trendPoint)
                ),
                freshness(),
                new BuyScore(77, 17, 79, "BUY", "매수 가능", List.of("기초체력 89/100")),
                new BottomSignal(
                        72, "재시험 구간", "확인 우선", "재시험 통과를 봐야 합니다.", 56, 42,
                        List.of(new BottomMetric("macro", "거시 받침", 68, "positive", "거시 중립")),
                        new BottomChart(
                                List.of(new ChartPoint("2026-07-18", new BigDecimal("64653.91"))),
                                List.of(new ChartMarker(
                                        "current", "2026-07-18", new BigDecimal("64653.91"), "현재"
                                ))
                        ),
                        new ConfirmedBottom(
                                47, "미충족", "대기", "2026-07-16", 3,
                                "확신형으로 보기 어렵습니다.", new BigDecimal("0.91"),
                                new BigDecimal("0.54"), new BigDecimal("-21.3"),
                                new BigDecimal("2.4"), new BigDecimal("2.5"),
                                List.of("낙폭 둔화"), List.of("거래량 우위 약함")
                        ),
                        List.of("거시 받침"), List.of(), List.of("거래량 확인 부족")
                ),
                new PositionSizing(8, 25, 75, "분할 진입 구간입니다."),
                new Verdicts(
                        "강함", "중립", "부담 낮음", "BUY",
                        new OneLiners("기본 체력이 강합니다.", "분할 접근이 적절합니다.", "매수 가능입니다.")
                ),
                new Scenarios("유동성 완화", "작은 비중 대응", "달러 강세"),
                withExecutionBridge
                        ? new ExecutionBridge(
                        "GOLD", "SCALE_IN", "보유/관찰", 26, "mixed", "분할 현물",
                        "방향이 충돌하지 않습니다.", "분할 접근합니다.", List.of("거시 방향 일치")
                )
                        : null
        );
    }

    private static DecisionFreshness freshness() {
        return new DecisionFreshness(
                "2026-07-19", "2026-07-18", 0, 1, 2, 7,
                true, "CURRENT", "가격과 보조근거가 최신입니다."
        );
    }
}
