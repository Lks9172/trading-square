package io.macrosquare.market.domain.signal;

import io.macrosquare.market.domain.regime.MacroRegimeAssessment;
import io.macrosquare.market.domain.regime.MacroRegime;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Multi-axis decision policy. No single oversold or narrative metric can create a buy by itself. */
public final class CoreAssetSignalPolicy {

    public List<CoreAssetSignal> evaluate(
            Map<String, Double> raw,
            Map<String, Double> derived,
            MacroRegimeAssessment regime,
            LocalDate asOf
    ) {
        return List.of(
                nasdaq(raw, derived, regime, asOf),
                gold(raw, derived, regime, asOf),
                silver(raw, derived, regime, asOf),
                copper(raw, derived, regime, asOf),
                cash(raw, derived, regime, asOf),
                leverage(raw, derived, regime, asOf),
                kospi(raw, derived, regime, asOf),
                emerging(raw, derived, regime, asOf)
        );
    }

    private static CoreAssetSignal nasdaq(Map<String, Double> raw, Map<String, Double> d,
                                           MacroRegimeAssessment regime, LocalDate date) {
        var b = builder("NASDAQ", date);
        b.add(regime.score() >= 55, 20, "거시 국면 점수 55 이상", "거시 국면 점수 55 미만");
        b.add(notOne(d, "CREDIT_STRESS_FLAG"), 18, "크레딧 스트레스 미발동", "크레딧 스트레스 발동",
                "크레딧 스트레스 데이터 없음");
        b.add(between(d.get("NASDAQ_DISPARITY"), -20, 12), 16,
                "NASDAQ 200일선 이격이 추격·붕괴 구간 밖", "NASDAQ 이격이 과도한 추격 또는 붕괴 구간");
        b.add(any(positive(d, "NASDAQ_ABOVE_200DMA"), lessOrEqual(d, "NASDAQ_DISPARITY", -10)), 14,
                "추세 유지 또는 의미 있는 눌림", "추세·눌림 조건 미충족", "NASDAQ 추세·이격 데이터 없음");
        b.add(less(raw, "VIXCLS", 30), 12, "VIX 위기 임계 미만", "VIX 30 이상", "VIX 데이터 없음");
        b.add(greater(d, "NET_LIQUIDITY_IMPULSE_4W_BN", 0), 10,
                "연준·TGA·RRP 순유동성 4주 확장", "순유동성 4주 수축·정체",
                "순유동성 충격 데이터 없음");
        b.add(notOne(d, "OVERHEATED"), 10, "과열 플래그 미발동", "과열 플래그 발동", "과열 데이터 없음");
        b.requireData(d.get("LIQUIDITY_PLUMBING_SIGNAL"), 5, "단기 유동성 배관 데이터 없음");
        b.requireData(d.get("LIQUIDITY_DIRECTION"), 5, "유동성 방향 데이터 없음");
        b.requireData(d.get("LIQUIDITY_TRANSMISSION_COVERAGE"), 5, "유동성 전달 스트레스 커버리지 없음");
        b.requireData(d.get("NASDAQ_STRUCTURE_SCORE"), 10, "NASDAQ 가격구조 점수 없음");
        b.requireData(d.get("NASDAQ_FIB_LAST_DEFENSE_BROKEN"), 5, "NASDAQ 피보 구조 데이터 없음");
        var signal = b.build();
        if (missing(d, "LIQUIDITY_PLUMBING_SIGNAL", "LIQUIDITY_DIRECTION", "LIQUIDITY_TRANSMISSION_COVERAGE",
                "NASDAQ_STRUCTURE_SCORE", "NASDAQ_FIB_LAST_DEFENSE_BROKEN")) {
            signal = cap(signal, CoreSignalAction.BUY,
                    "유동성 또는 가격구조 게이트 데이터가 없어 적극 매수로 올리지 않습니다.");
        }
        if (greater(d, "NASDAQ_DISPARITY", 8) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.BUY, "NASDAQ 200일선 이격이 8%를 넘어 적극 매수보다 추격 제한이 우선입니다.");
        }
        if (less(d, "LIQUIDITY_PLUMBING_SIGNAL", 0) == Boolean.TRUE
                || less(d, "LIQUIDITY_DIRECTION", 0) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.BUY, "단기 유동성 배관이 흡수 우위라 적극 매수 상한을 제한합니다.");
        }
        if (greaterOrEqual(d, "LIQUIDITY_TRANSMISSION_STRESS_SCORE", 2) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.BUY,
                    "유동성이 늘어도 신용·변동성·자금시장 스트레스가 전달을 막을 수 있어 적극 매수를 제한합니다.");
        }
        if (less(d, "LIQUIDITY_TRANSMISSION_COVERAGE", 67) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.BUY,
                    "유동성 전달 스트레스 데이터가 2/3 미만이라 적극 매수를 제한합니다.");
        }
        if (less(d, "NASDAQ_STRUCTURE_SCORE", 40) == Boolean.TRUE
                || one(d, "NASDAQ_FIB_LAST_DEFENSE_BROKEN") == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.HOLD, "가격 구조 훼손이 남아 신규 매수보다 회복 확인이 우선입니다.");
        }
        return signal;
    }

    private static CoreAssetSignal gold(Map<String, Double> raw, Map<String, Double> d,
                                         MacroRegimeAssessment regime, LocalDate date) {
        var b = builder("GOLD", date);
        b.add(lessOrEqual(d, "REAL_YIELD", 2.25), 18, "실질금리 부담 제한", "실질금리 부담 높음", "실질금리 데이터 없음");
        b.add(lessOrEqual(raw, "DXY", 105), 16, "달러 강세 제한", "DXY 105 초과", "DXY 데이터 없음");
        b.add(positive(d, "GOLD_ABOVE_200DMA"), 16, "금 200일선 상단", "금 200일선 하단", "금 200일선 데이터 없음");
        b.add(all(greater(d, "GOLD_RSI_14", 35), lessOrEqual(d, "GOLD_RSI_14", 75)), 12,
                "금 RSI가 건강한 추세 구간", "금 RSI 과매도/과열 구간", "금 RSI 데이터 없음");
        b.add(any(regime.regime() != MacroRegime.RISK_ON,
                        one(d, "STAGFLATION_WARNING"), one(d, "BOND_VIGILANTE_WARNING")),
                14, "방어·인플레 국면 수요", "강한 위험선호로 방어 수요 약함");
        b.add(less(d, "GOLD_SILVER_RATIO", 130), 10, "금은비 극단 미만", "금은비 130 이상 극단", "금은비 데이터 없음");
        b.add(any(notOne(d, "CREDIT_STRESS_FLAG"), regime.score() < 45), 14,
                "크레딧/방어 조건 정합", "크레딧 스트레스와 금 추세 불정합", "크레딧 스트레스 데이터 없음");
        b.add(greater(d, "NET_LIQUIDITY_IMPULSE_4W_BN", 0), 10,
                "순유동성 확장 확인", "순유동성 확장 미확인", "순유동성 충격 데이터 없음");
        b.requireData(d.get("GOLD_FIB_SWING_DIRECTION"), 10, "금 주요 파동 방향 데이터 없음");
        var signal = b.build();
        if (missing(d, "GOLD_FIB_SWING_DIRECTION")) {
            signal = cap(signal, CoreSignalAction.BUY,
                    "금 주요 파동 데이터가 없어 적극 매수로 올리지 않습니다.");
        }
        if (less(d, "GOLD_FIB_SWING_DIRECTION", 0) == Boolean.TRUE
                && positive(d, "GOLD_ABOVE_200DMA") == Boolean.FALSE) {
            signal = cap(signal, CoreSignalAction.HOLD, "하락 주요 파동과 200일선 하회가 겹쳐 매수보다 추세 회복 확인이 우선입니다.");
        }
        return signal;
    }

    private static CoreAssetSignal silver(Map<String, Double> raw, Map<String, Double> d,
                                           MacroRegimeAssessment regime, LocalDate date) {
        var b = builder("SILVER", date);
        b.add(greater(d, "GOLD_SILVER_RATIO", 80), 20, "금은비 80 이상으로 은 상대 저평가", "금은비 상대 저평가 미충족", "금은비 데이터 없음");
        b.add(signalFriendly(regime), 15, "거시 국면이 은 수요에 우호", "침체·신용 위험이 은 수요에 불리");
        b.add(greater(d, "COPPER_GOLD_RATIO_TREND", 0), 15, "산업 수요 방향 개선", "산업 수요 방향 약함", "구리/금 추세 데이터 없음");
        b.add(lessOrEqual(raw, "DXY", 105), 15, "달러 부담 제한", "달러 강세 부담", "DXY 데이터 없음");
        b.add(notOne(d, "CREDIT_STRESS_FLAG"), 15, "크레딧 스트레스 미발동", "크레딧 스트레스 발동", "크레딧 스트레스 데이터 없음");
        b.add(greater(d, "GLOBAL_M2_PROXY", 0), 10, "미국 M2 프록시 양수", "미국 M2 프록시 비우호", "미국 M2 데이터 없음");
        b.add(any(notOne(d, "STAGFLATION_WARNING"), greater(d, "COPPER_GOLD_RATIO_TREND", 0)), 10,
                "스태그플레이션 성장축 확인", "스태그플레이션에서 성장축 미확인", "스태그플레이션·구리/금 데이터 없음");
        return cap(b.build(), CoreSignalAction.BUY,
                "은 자체 가격·거래량 진입축이 없어 상대가치·거시 조건만으로 적극 매수하지 않습니다.");
    }

    private static CoreAssetSignal copper(Map<String, Double> raw, Map<String, Double> d,
                                           MacroRegimeAssessment regime, LocalDate date) {
        var b = builder("COPPER", date);
        b.add(greater(d, "COPPER_GOLD_RATIO_TREND", 0), 20, "구리/금 추세 상승", "구리/금 추세 약화", "구리/금 추세 데이터 없음");
        b.add(any(one(d, "COPPER_GOLD_RATIO_UPTURN"), greater(d, "SECTOR_XLI", 0)), 18,
                "경기민감 전환 또는 산업재 강세", "경기민감 확인 부족", "구리/금 전환·산업재 데이터 없음");
        b.add(regime.regime() != MacroRegime.RECESSION_RISK && regime.regime() != MacroRegime.CORRECTION,
                16, "거시 국면이 심각한 침체가 아님", "침체·조정 국면");
        b.add(greater(d, "GLOBAL_M2_PROXY", 0), 12, "미국 M2 프록시 양수", "미국 M2 프록시 비우호", "미국 M2 데이터 없음");
        b.add(lessOrEqual(raw, "DXY", 105), 12, "달러 부담 제한", "달러 강세 부담", "DXY 데이터 없음");
        b.add(notOne(d, "CREDIT_STRESS_FLAG"), 12, "크레딧 스트레스 미발동", "크레딧 스트레스 발동", "크레딧 스트레스 데이터 없음");
        b.add(less(raw, "WTI", 100), 10, "원가 충격 임계 미만", "유가 100 이상 충격", "WTI 데이터 없음");
        return cap(b.build(), CoreSignalAction.BUY, "구리 자체 가격·거래량 진입축이 없어 거시 우호 신호만으로 적극 매수하지 않습니다.");
    }

    private static CoreAssetSignal cash(Map<String, Double> raw, Map<String, Double> d,
                                         MacroRegimeAssessment regime, LocalDate date) {
        var b = builder("CASH", date);
        b.add(regime.score() < 55 || defensiveRegime(regime.regime()), 25,
                "현금 방어가 필요한 거시 국면", "거시 국면상 현금 방어 필요 제한");
        b.add(one(d, "CREDIT_STRESS_FLAG"), 20, "크레딧 스트레스 발동", "크레딧 스트레스 미발동", "크레딧 스트레스 데이터 없음");
        b.add(one(d, "OVERHEATED"), 15, "시장 과열 발동", "시장 과열 미발동", "과열 데이터 없음");
        b.add(greaterOrEqual(raw, "VIXCLS", 25), 15, "VIX 25 이상", "VIX 25 미만", "VIX 데이터 없음");
        b.add(any(one(d, "FISCAL_STRESS"), one(d, "STAGFLATION_WARNING"), one(d, "BOND_VIGILANTE_WARNING")), 15,
                "재정·물가·장기금리 구조 위험", "구조 위험 미발동", "재정·물가·장기금리 데이터 없음");
        b.add(lessOrEqual(d, "LIQUIDITY_DIRECTION", -1), 10, "유동성 수축", "유동성 중립·확장", "유동성 방향 데이터 없음");
        return b.build();
    }

    private static CoreAssetSignal leverage(Map<String, Double> raw, Map<String, Double> d,
                                             MacroRegimeAssessment regime, LocalDate date) {
        var b = builder("LEVERAGE", date);
        b.add(regime.score() >= 60, 20, "거시 점수 60 이상", "거시 점수 60 미만");
        b.add(positive(d, "NASDAQ_ABOVE_200DMA"), 18, "NASDAQ 200일선 상단", "NASDAQ 200일선 하단", "NASDAQ 200일선 데이터 없음");
        b.add(notOne(d, "OVERHEATED"), 16, "과열 미발동", "과열 발동", "과열 데이터 없음");
        b.add(notOne(d, "CREDIT_STRESS_FLAG"), 16, "크레딧 스트레스 미발동", "크레딧 스트레스 발동", "크레딧 스트레스 데이터 없음");
        b.add(between(raw.get("VIXCLS"), 14, 25), 12,
                "VIX 14~25 정상 변동성", "VIX가 레버리지 허용 범위 밖", "VIX 데이터 없음");
        b.add(between(d.get("NASDAQ_RSI_14"), 45, 70), 10, "NASDAQ RSI 추세 구간", "NASDAQ RSI 추세 구간 이탈", "NASDAQ RSI 데이터 없음");
        b.add(greater(d, "NET_LIQUIDITY_IMPULSE_4W_BN", 0), 8,
                "순유동성 4주 확장", "순유동성 4주 수축·정체", "순유동성 충격 데이터 없음");
        b.requireData(d.get("LIQUIDITY_PLUMBING_SIGNAL"), 10, "레버리지용 단기 유동성 데이터 없음");
        b.requireData(d.get("LIQUIDITY_DIRECTION"), 10, "레버리지용 유동성 방향 데이터 없음");
        b.requireData(d.get("NASDAQ_DISPARITY"), 10, "레버리지용 NASDAQ 이격 데이터 없음");
        b.requireData(d.get("TAIL_RISK_LEVEL"), 10, "레버리지용 꼬리위험 데이터 없음");
        b.requireData(d.get("LIQUIDITY_TRANSMISSION_COVERAGE"), 10, "레버리지용 전달 스트레스 커버리지 없음");
        var signal = b.build();
        if (missing(d, "LIQUIDITY_PLUMBING_SIGNAL", "LIQUIDITY_DIRECTION",
                "NASDAQ_DISPARITY", "TAIL_RISK_LEVEL", "LIQUIDITY_TRANSMISSION_COVERAGE")) {
            signal = cap(signal, CoreSignalAction.HOLD,
                    "레버리지 위험 게이트 데이터가 하나라도 없으면 확대를 허용하지 않습니다.");
        }
        if (less(d, "LIQUIDITY_PLUMBING_SIGNAL", 0) == Boolean.TRUE
                || less(d, "LIQUIDITY_DIRECTION", 0) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.HOLD, "단기 유동성 흡수 국면에서는 레버리지를 허용하지 않습니다.");
        }
        if (greater(d, "NASDAQ_DISPARITY", 8) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.HOLD, "NASDAQ 200일선 이격 8% 초과 구간에서는 레버리지 추격을 제한합니다.");
        }
        if (greaterOrEqual(d, "TAIL_RISK_LEVEL", 1) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.HOLD, "꼬리위험 경고가 켜져 레버리지 확대를 제한합니다.");
        }
        if (greaterOrEqual(d, "LIQUIDITY_TRANSMISSION_STRESS_SCORE", 2) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.HOLD,
                    "신용·변동성·자금시장 전달 스트레스가 높아 유동성 확대만으로 레버리지를 허용하지 않습니다.");
        }
        if (less(d, "LIQUIDITY_TRANSMISSION_COVERAGE", 67) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.HOLD,
                    "유동성 전달 스트레스 데이터가 2/3 미만이라 레버리지 확대를 허용하지 않습니다.");
        }
        var tier = signal.action() == CoreSignalAction.BUY || signal.action() == CoreSignalAction.STRONG_BUY
                ? signal.weightedScore() >= 82 ? "HARD" : signal.weightedScore() >= 72 ? "MEDIUM"
                : signal.weightedScore() >= 62 ? "SOFT" : null
                : null;
        return withTier(signal, tier);
    }

    private static CoreAssetSignal kospi(Map<String, Double> raw, Map<String, Double> d,
                                          MacroRegimeAssessment regime, LocalDate date) {
        var b = builder("KOSPI", date);
        b.add(greaterOrEqual(d, "KRW_FX_LEVEL", 0), 20, "원화 환율 레드존 미진입", "USDKRW 레드존", "환율 구간 데이터 없음");
        b.add(any(positive(d, "KOSPI_ABOVE_200DMA"), lessOrEqual(d, "KOSPI_DISPARITY", -10)), 18,
                "KOSPI 추세 유지 또는 눌림", "KOSPI 추세·눌림 미확인", "KOSPI 추세·이격 데이터 없음");
        b.add(regime.score() >= 50, 14, "글로벌 거시 점수 50 이상", "글로벌 거시 점수 50 미만");
        b.add(lessOrEqual(raw, "DXY", 105), 14, "달러 부담 제한", "달러 강세 부담", "DXY 데이터 없음");
        b.add(greater(d, "SECTOR_SOXX", -5), 12, "반도체 섹터 급락 아님", "반도체 섹터 급락", "반도체 상대강도 데이터 없음");
        b.add(notOne(d, "CREDIT_STRESS_FLAG"), 12, "크레딧 스트레스 미발동", "크레딧 스트레스 발동", "크레딧 스트레스 데이터 없음");
        b.add(notOne(d, "KOSPI_OVERHEATED"), 10, "KOSPI 과열 미발동", "KOSPI 과열 발동", "KOSPI 과열 데이터 없음");
        var foreign20 = d.get("KOSPI_FOREIGN_NET_20D");
        var foreignTrend = d.get("KOSPI_FOREIGN_TREND");
        var foreignSellStreak = d.get("KOSPI_FOREIGN_SELL_STREAK");
        b.add(any(greaterOrEqual(foreign20, 0), greater(foreignTrend, 0), less(foreignSellStreak, 5)),
                14, "외국인 수급 이탈 미확인", "외국인 20D 순매도·추세 약화·5일 매도 동시 확인", "외국인 수급 데이터 없음");
        b.requireData(d.get("KOSPI_STRUCTURE_SCORE"), 15, "KOSPI 가격구조 점수 없음");
        b.requireData(d.get("KOSPI_FIB_LAST_DEFENSE_BROKEN"), 10, "KOSPI 피보 구조 데이터 없음");
        var signal = b.build();
        if (missing(d, "KOSPI_STRUCTURE_SCORE", "KOSPI_FIB_LAST_DEFENSE_BROKEN")) {
            signal = cap(signal, CoreSignalAction.BUY,
                    "KOSPI 가격구조 게이트 데이터가 없어 적극 매수로 올리지 않습니다.");
        }
        if (greater(d, "KOSPI_DISPARITY", 10) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.BUY, "KOSPI 200일선 이격 10% 초과로 추격 매수 상한을 제한합니다.");
        }
        if (less(d, "KOSPI_STRUCTURE_SCORE", 40) == Boolean.TRUE
                || one(d, "KOSPI_FIB_LAST_DEFENSE_BROKEN") == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.HOLD, "KOSPI 가격 구조 훼손이 남아 회복 확인이 우선입니다.");
        }
        return signal;
    }

    private static CoreAssetSignal emerging(Map<String, Double> raw, Map<String, Double> d,
                                             MacroRegimeAssessment regime, LocalDate date) {
        var b = builder("EMERGING", date);
        b.add(less(raw, "DXY", 105), 20, "DXY 105 미만", "DXY 105 이상", "DXY 데이터 없음");
        b.add(greater(d, "GLOBAL_M2_PROXY", 0), 18, "미국 M2 프록시 양수", "미국 M2 프록시 비우호", "미국 M2 데이터 없음");
        b.add(regime.score() >= 50, 16, "거시 점수 50 이상", "거시 점수 50 미만");
        b.add(notOne(d, "CREDIT_STRESS_FLAG"), 16, "크레딧 스트레스 미발동", "크레딧 스트레스 발동", "크레딧 스트레스 데이터 없음");
        b.add(greater(d, "SECTOR_XLB", -5), 12, "소재 경기민감 급락 아님", "소재 경기민감 급락", "소재 상대강도 데이터 없음");
        b.add(less(raw, "USDKRW", 1550), 10, "아시아 FX 스트레스 제한", "아시아 FX 스트레스 확대", "USDKRW 데이터 없음");
        b.add(greater(d, "COPPER_GOLD_RATIO_TREND", -0.02), 8, "구리/금 급락 아님", "구리/금 급락", "구리/금 추세 데이터 없음");
        b.requireData(d.get("DOLLAR_LIQUIDITY_SPILLOVER_SIGNAL"), 10, "달러 유동성 전이 데이터 없음");
        var signal = cap(b.build(), CoreSignalAction.BUY, "신흥국 자체 가격·펀드플로 축이 없어 거시 우호 신호만으로 적극 매수하지 않습니다.");
        if (less(d, "DOLLAR_LIQUIDITY_SPILLOVER_SIGNAL", 0) == Boolean.TRUE) {
            signal = cap(signal, CoreSignalAction.HOLD, "달러 유동성의 한국·신흥국 전이가 자금 회수 압력이라 진입을 보류합니다.");
        }
        return signal;
    }

    private static boolean signalFriendly(MacroRegimeAssessment assessment) {
        return assessment.regime() != MacroRegime.RECESSION_RISK
                && assessment.regime() != MacroRegime.BOND_VIGILANTE
                && assessment.regime() != MacroRegime.STAGFLATION_BOND_VIGILANTE;
    }

    private static boolean defensiveRegime(MacroRegime regime) {
        return switch (regime) {
            case CAUTION, CORRECTION, RECESSION_RISK, STAGFLATION,
                    BOND_VIGILANTE, STAGFLATION_BOND_VIGILANTE -> true;
            case RISK_ON, NEUTRAL, PANIC_BUT_OK -> false;
        };
    }

    private static SignalBuilder builder(String asset, LocalDate date) {
        return new SignalBuilder(asset, date);
    }

    private static Boolean one(Map<String, Double> values, String key) { return equal(values.get(key), 1); }
    private static Boolean notOne(Map<String, Double> values, String key) {
        var value = one(values, key);
        return value == null ? null : !value;
    }
    private static Boolean positive(Map<String, Double> values, String key) { return greater(values, key, 0); }
    private static Boolean greater(Map<String, Double> values, String key, double threshold) {
        return greater(values.get(key), threshold);
    }
    private static Boolean greater(Double value, double threshold) {
        return value == null ? null : value > threshold;
    }
    private static Boolean greaterOrEqual(Map<String, Double> values, String key, double threshold) {
        return greaterOrEqual(values.get(key), threshold);
    }
    private static Boolean greaterOrEqual(Double value, double threshold) {
        return value == null ? null : value >= threshold;
    }
    private static Boolean less(Map<String, Double> values, String key, double threshold) {
        return less(values.get(key), threshold);
    }
    private static Boolean less(Double value, double threshold) {
        return value == null ? null : value < threshold;
    }
    private static Boolean lessOrEqual(Map<String, Double> values, String key, double threshold) {
        var value = values.get(key);
        return value == null ? null : value <= threshold;
    }
    private static Boolean between(Double value, double lower, double upper) {
        return value == null ? null : value >= lower && value <= upper;
    }
    private static Boolean equal(Double value, double expected) {
        return value == null ? null : Double.compare(value, expected) == 0;
    }
    private static boolean missing(Map<String, Double> values, String... keys) {
        for (var key : keys) if (values.get(key) == null) return true;
        return false;
    }
    private static Boolean any(Boolean... values) {
        var missing = false;
        for (var value : values) {
            if (Boolean.TRUE.equals(value)) return true;
            if (value == null) missing = true;
        }
        return missing ? null : false;
    }
    private static Boolean all(Boolean... values) {
        var missing = false;
        for (var value : values) {
            if (Boolean.FALSE.equals(value)) return false;
            if (value == null) missing = true;
        }
        return missing ? null : true;
    }

    private static CoreAssetSignal cap(
            CoreAssetSignal signal,
            CoreSignalAction maximum,
            String reason
    ) {
        var unmet = new ArrayList<>(signal.unmetReasons());
        var warning = "⚠ 액션 상한: " + reason;
        if (!unmet.contains(warning)) unmet.add(warning);
        var cappedAction = rank(signal.action()) > rank(maximum) ? maximum : signal.action();
        return new CoreAssetSignal(
                signal.asset(), cappedAction, signal.conditionsMet(), signal.conditionsTotal(),
                signal.conditionsAvailable(), signal.weightedScore(), signal.weightedMaxScore(),
                signal.dataCoveragePct(), signal.reasons(), unmet, signal.missingReasons(),
                signal.date(), signal.leverageTier());
    }

    private static CoreAssetSignal withTier(CoreAssetSignal signal, String tier) {
        return new CoreAssetSignal(
                signal.asset(), signal.action(), signal.conditionsMet(), signal.conditionsTotal(),
                signal.conditionsAvailable(), signal.weightedScore(), signal.weightedMaxScore(),
                signal.dataCoveragePct(), signal.reasons(), signal.unmetReasons(), signal.missingReasons(),
                signal.date(), tier);
    }

    private static CoreAssetSignal neutralize(CoreAssetSignal signal, String reason) {
        if (signal.action() == CoreSignalAction.HOLD) return signal;
        var unmet = new ArrayList<>(signal.unmetReasons());
        unmet.add("⚠ 액션 중립화: " + reason);
        return new CoreAssetSignal(
                signal.asset(), CoreSignalAction.HOLD, signal.conditionsMet(), signal.conditionsTotal(),
                signal.conditionsAvailable(), signal.weightedScore(), signal.weightedMaxScore(),
                signal.dataCoveragePct(), signal.reasons(), unmet, signal.missingReasons(),
                signal.date(), null);
    }

    private static int rank(CoreSignalAction action) {
        return switch (action) {
            case SELL -> 0;
            case REDUCE -> 1;
            case HOLD -> 2;
            case BUY -> 3;
            case STRONG_BUY -> 4;
        };
    }

    private static final class SignalBuilder {
        private final String asset;
        private final LocalDate date;
        private final List<String> reasons = new ArrayList<>();
        private final List<String> unmet = new ArrayList<>();
        private final List<String> missing = new ArrayList<>();
        private int met;
        private int total;
        private int available;
        private int score;
        private int max;
        private int availableWeight;
        private int coverageMax;

        private SignalBuilder(String asset, LocalDate date) { this.asset = asset; this.date = date; }

        private void add(Boolean condition, int weight, String reason, String unmetReason) {
            add(condition, weight, reason, unmetReason, "필수 입력 데이터 없음");
        }

        private void add(Boolean condition, int weight, String reason, String unmetReason, String missingReason) {
            total++;
            max += weight;
            coverageMax += weight;
            if (condition == null) {
                missing.add("? " + missingReason);
                return;
            }
            available++;
            availableWeight += weight;
            if (condition) { met++; score += weight; reasons.add("✓ " + reason); }
            else unmet.add("✗ " + unmetReason);
        }

        private void requireData(Double value, int weight, String missingReason) {
            if (weight <= 0) throw new IllegalArgumentException("evidence weight must be positive");
            coverageMax += weight;
            if (value == null) missing.add("? " + missingReason);
            else availableWeight += weight;
        }

        private CoreAssetSignal build() {
            var normalized = max == 0 ? 0 : (int) Math.round(score * 100d / max);
            var coverage = coverageMax == 0 ? 0 : (int) Math.round(availableWeight * 100d / coverageMax);
            var action = normalized >= 80 ? CoreSignalAction.STRONG_BUY
                    : normalized >= 65 ? CoreSignalAction.BUY
                    : normalized >= 45 ? CoreSignalAction.HOLD
                    : normalized >= 25 ? CoreSignalAction.REDUCE : CoreSignalAction.SELL;
            var signal = new CoreAssetSignal(asset, action, met, total, available,
                    normalized, 100, coverage, reasons, unmet, missing, date, null);
            if (coverage < 70) {
                return neutralize(signal,
                        "필수 데이터 충족률이 70% 미만이라 매수·매도 단정을 보류합니다.");
            }
            if (coverage < 85) {
                return cap(signal, CoreSignalAction.BUY,
                        "필수 데이터 충족률이 85% 미만이라 적극 매수로 올리지 않습니다.");
            }
            return signal;
        }
    }
}
