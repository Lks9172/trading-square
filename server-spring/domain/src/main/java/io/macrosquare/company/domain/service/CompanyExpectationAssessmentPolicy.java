package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyExpectationAssessment;
import io.macrosquare.company.domain.model.CompanyExpectationAssessment.State;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Scores whether forward earnings expectations are improving or deteriorating.
 * Target-price upside is intentionally not part of the score.
 */
public final class CompanyExpectationAssessmentPolicy {

    public CompanyExpectationAssessment evaluate(
            CompanyMarketExpectations expectations,
            Integer crowdingScore
    ) {
        Objects.requireNonNull(expectations, "expectations");
        var seven = finite(expectations.estimateRevision7d());
        var thirty = finite(expectations.estimateRevision30d());
        var ninety = finite(expectations.estimateRevision90d());
        var analyst = finite(expectations.analystScoreRevision30d());
        if (seven == null && thirty == null && ninety == null && analyst == null) {
            return new CompanyExpectationAssessment(
                    50,
                    State.UNAVAILABLE,
                    "직접 수집한 EPS 추정치 변화가 없어 기대 방향을 중립으로 둡니다.",
                    List.of(),
                    List.of("목표가 상승여력 변화는 EPS 추정치 변화로 대체하지 않습니다.")
            );
        }

        var score = 52.0;
        if (thirty != null) score += clamp(thirty * 1.8, -18, 18);
        if (seven != null) score += clamp(seven * 1.2, -10, 10);
        if (ninety != null) score += clamp(ninety * 0.8, -12, 12);
        if (analyst != null) score += clamp(analyst * 8, -12, 12);
        if (crowdingScore != null) {
            if (crowdingScore <= 55) score += 6;
            else if (crowdingScore >= 70) score -= 10;
        }
        var rounded = clampScore(score);
        var state = rounded >= 68 ? State.IMPROVING
                : rounded >= 48 ? State.STABLE
                : rounded >= 32 ? State.WEAKENING
                : State.DETERIORATING;

        var reasons = new ArrayList<String>();
        var cautions = new ArrayList<String>();
        addRevision(reasons, cautions, "7일", seven);
        addRevision(reasons, cautions, "30일", thirty);
        addRevision(reasons, cautions, "90일", ninety);
        if (crowdingScore != null && crowdingScore >= 70) {
            cautions.add("과열도 " + crowdingScore + "/100으로 기대 개선이 이미 가격에 반영됐을 수 있습니다.");
        }

        var summary = switch (state) {
            case IMPROVING -> "전방 EPS 추정치가 개선돼 실적 기대가 우호적입니다.";
            case STABLE -> "전방 EPS 추정치는 급격히 훼손되지 않은 중립 범위입니다.";
            case WEAKENING -> "전방 EPS 추정치가 약해져 추가 하향 여부를 확인해야 합니다.";
            case DETERIORATING -> "전방 EPS 추정치 하향이 커 실적 바닥을 확신하기 어렵습니다.";
            case UNAVAILABLE -> throw new IllegalStateException("handled before scoring");
        };
        return new CompanyExpectationAssessment(rounded, state, summary, reasons, cautions);
    }

    private static void addRevision(
            ArrayList<String> reasons,
            ArrayList<String> cautions,
            String window,
            Double value
    ) {
        if (value == null) return;
        var sentence = window + " EPS 추정치 " + signed(value) + "%";
        if (value >= 3) reasons.add(sentence + "로 의미 있게 상향됐습니다.");
        else if (value <= -3) cautions.add(sentence + "로 의미 있게 하향됐습니다.");
        else if ("30일".equals(window)) reasons.add(sentence + "로 급격한 훼손은 아닙니다.");
    }

    private static Double finite(Double value) {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampScore(double value) {
        return Math.max(0, Math.min(100, (int) Math.round(value)));
    }
}
