package io.macrosquare.company.domain.bottom;

import java.math.BigDecimal;
import java.util.ArrayList;

public final class DeepBottomPolicy {

    public DeepBottomSignal evaluate(DeepBottomEvidence evidence) {
        var recentVolumeRatio = evidence.recentVolumeRatio();
        var score = roundScore(
                18
                        + drawdownContribution(evidence.drawdown120dPct())
                        + volumeContribution(recentVolumeRatio)
                        + contractionContribution(evidence.contractionRatio())
                        + recentDropContribution(evidence.recentDrop3dPct())
                        + movingAverageGapContribution(evidence.ma20GapPct())
                        + (evidence.ma20Below50() ? 10 : -12)
                        + recencyContribution(evidence.daysSinceAbsorption())
                        + reboundContribution(evidence.reboundSinceAbsorptionPct())
                        + failureRiskContribution(evidence.failureRiskScore())
        );

        var timely = evidence.daysSinceAbsorption() != null && evidence.daysSinceAbsorption() <= 25;
        var notAlreadyExtended = evidence.reboundSinceAbsorptionPct() != null
                && evidence.reboundSinceAbsorptionPct() <= 25;
        // A high drawdown score alone is not a confirmed bottom. The user's
        // bottom thesis explicitly requires the sell-off/absorption candle to
        // carry more volume than the preceding two-to-three sessions.
        var volumeConfirmed = recentVolumeRatio != null && recentVolumeRatio >= 1.1;
        // BottomPriceContext only produces a contraction ratio when both the
        // absorption candle and the comparison decline are actual down days.
        // Requiring it here prevents a high-volume up candle from being
        // mislabeled as the user's sell-off absorption signal.
        var selloffDayConfirmed = evidence.contractionRatio() != null;
        var state = score >= 78 && timely && notAlreadyExtended && volumeConfirmed && selloffDayConfirmed
                ? DeepBottomState.CONVICTION
                : score >= 62
                ? DeepBottomState.CANDIDATE
                : DeepBottomState.UNMET;
        var actionBias = switch (state) {
            case CONVICTION -> BottomActionBias.SCALE_IN_BUY;
            case CANDIDATE -> BottomActionBias.OBSERVE_BUY;
            case UNMET -> BottomActionBias.WAIT;
        };

        var reasons = new ArrayList<String>();
        if (recentVolumeRatio != null && recentVolumeRatio >= 1.1) {
            reasons.add("직전 3개 거래일 최대 대비 거래량 " + compact(recentVolumeRatio) + "배로 투매 흡수 흔적");
        }
        if (evidence.contractionRatio() != null && evidence.contractionRatio() <= 0.8) {
            reasons.add("낙폭이 직전 하락의 " + Math.round(evidence.contractionRatio() * 100) + "% 수준으로 둔화");
        }
        if (evidence.recentDrop3dPct() != null && evidence.recentDrop3dPct() <= -5) {
            reasons.add("직전 3일 누적 하락 " + compact(evidence.recentDrop3dPct()) + "%로 급락 구간 통과");
        }
        if (evidence.ma20GapPct() != null && evidence.ma20GapPct() <= -8) {
            reasons.add("20일선 대비 " + compact(evidence.ma20GapPct()) + "% 이격으로 과매도 구간");
        }
        if (evidence.drawdown120dPct() != null && evidence.drawdown120dPct() <= -15) {
            reasons.add("120일 고점 대비 " + compact(evidence.drawdown120dPct()) + "% 하락");
        }

        var cautions = new ArrayList<String>();
        if (recentVolumeRatio == null) {
            cautions.add("직전 3개 거래일과 비교할 거래량 근거가 부족합니다.");
        }
        if (recentVolumeRatio != null && recentVolumeRatio < 1.1) {
            cautions.add("직전 3개 거래일 최대 거래량 대비 우위가 약합니다.");
        }
        if (evidence.contractionRatio() == null) {
            cautions.add("하락일 투매 흡수 조건이 확인되지 않았습니다.");
        }
        if (evidence.contractionRatio() != null && evidence.contractionRatio() > 0.8) {
            cautions.add("낙폭 축소가 충분하지 않아 흡수 신호가 약합니다.");
        }
        if (evidence.ma20GapPct() != null && evidence.ma20GapPct() > -8) {
            cautions.add("20일선 이격이 작아 강한 투매성 바닥으로 보기 어렵습니다.");
        }
        if (evidence.daysSinceAbsorption() != null && evidence.daysSinceAbsorption() > 25) {
            cautions.add("신호 발생 후 시간이 지나 초기 바닥 초입 매력은 줄었습니다.");
        }
        if (evidence.reboundSinceAbsorptionPct() != null && evidence.reboundSinceAbsorptionPct() > 25) {
            cautions.add("신호 이후 이미 많이 반등해 초기 진입 구간은 일부 지나갔습니다.");
        }
        if (evidence.failureRiskScore() != null && evidence.failureRiskScore() >= 55) {
            cautions.add("실패 위험 점수가 높아 확신형 신호라도 보수적 비중이 필요합니다.");
        }

        var summary = switch (state) {
            case CONVICTION -> "미래 반등을 쓰지 않고도 급락·상대 거래량·과매도 조건이 강하게 겹친 확신형 바닥 신호입니다.";
            case CANDIDATE -> "당시 데이터만 봐도 강한 바닥 후보 조건이 일부 충족됐지만, 아직 확신형으로 부르기엔 한두 조건이 부족합니다.";
            case UNMET -> "현재 구간은 확신형 바닥 신호로 보기 어렵습니다. 일반 바닥 후보 정도로만 해석하는 편이 안전합니다.";
        };

        return new DeepBottomSignal(
                score,
                state,
                actionBias,
                evidence.signalDate(),
                evidence.daysSinceAbsorption(),
                summary,
                recentVolumeRatio,
                evidence.contractionRatio(),
                evidence.drawdown120dPct(),
                evidence.ma20GapPct(),
                evidence.recentDrop3dPct(),
                reasons.stream().limit(4).toList(),
                cautions.stream().limit(4).toList()
        );
    }

    private static int drawdownContribution(Double value) {
        if (value == null) return 0;
        if (value <= -25) return 18;
        if (value <= -20) return 14;
        if (value <= -15) return 10;
        if (value <= -10) return 4;
        return -6;
    }

    private static int volumeContribution(Double value) {
        if (value == null) return 0;
        if (value >= 1.25) return 18;
        if (value >= 1.1) return 14;
        if (value >= 1) return 8;
        return -8;
    }

    private static int contractionContribution(Double value) {
        if (value == null) return 0;
        if (value <= 0.6) return 18;
        if (value <= 0.8) return 14;
        if (value <= 1) return 8;
        return -10;
    }

    private static int recentDropContribution(Double value) {
        if (value == null) return 0;
        if (value <= -10) return 18;
        if (value <= -8) return 15;
        if (value <= -5) return 10;
        if (value <= -3) return 4;
        return -6;
    }

    private static int movingAverageGapContribution(Double value) {
        if (value == null) return 0;
        if (value <= -10) return 18;
        if (value <= -8) return 14;
        if (value <= -6) return 10;
        if (value <= -2) return 4;
        return -8;
    }

    private static int recencyContribution(Integer value) {
        if (value == null) return 0;
        if (value > 40) return -18;
        if (value > 25) return -10;
        if (value > 15) return -4;
        return 4;
    }

    private static int reboundContribution(Double value) {
        if (value == null) return 0;
        if (value > 40) return -18;
        if (value > 25) return -12;
        if (value > 15) return -6;
        if (value >= 0) return 4;
        return -4;
    }

    private static double failureRiskContribution(Integer value) {
        return value == null ? 0 : clamp((45 - value) * 0.35, -16, 12);
    }

    private static int roundScore(double value) {
        return (int) Math.round(clamp(value, 0, 100));
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static String compact(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
