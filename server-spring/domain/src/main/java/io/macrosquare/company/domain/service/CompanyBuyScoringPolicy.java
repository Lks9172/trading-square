package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyBuyLabel;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyFinancials;
import io.macrosquare.company.domain.model.CompanyMarketExpectations;
import io.macrosquare.company.domain.model.CompanyScore;

import java.util.ArrayList;
import java.util.Locale;

public final class CompanyBuyScoringPolicy {

    public CompanyBuyScore evaluate(
            CompanyFinancials financials,
            CompanyScore companyScore,
            CompanyMarketExpectations expectations
    ) {
        var normalizedGrowth = bounded(financials.revenueGrowthYoY(), -20, 80);
        var normalizedMargin = bounded(financials.operatingMargin(), -10, 45);
        var normalizedEvToSales = bounded(financials.evToSales(), 0, 20);
        var normalizedUpside = bounded(expectations.estimateUpsidePct(), -20, 40);
        var normalizedRevision = bounded(expectations.estimateRevision30d(), -20, 20);
        var normalizedAnalystRevision = bounded(expectations.analystScoreRevision30d(), -2, 2);

        var appealDrivers = new ArrayList<Double>();
        appealDrivers.add((double) companyScore.totalScore());
        appealDrivers.add((double) companyScore.quality().value());
        appealDrivers.add((double) companyScore.growth().value());
        appealDrivers.add((double) companyScore.balanceSheet().value());
        if (normalizedUpside != null) {
            appealDrivers.add(clamp(50 + normalizedUpside * 0.9, 25, 82));
        }
        if (normalizedMargin != null) {
            appealDrivers.add(clamp(45 + normalizedMargin * 0.7, 20, 85));
        }
        var appealBase = appealDrivers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        // A positive revision is incremental evidence, not another value to
        // average into the level scores. Averaging a modest positive revision
        // below an already-strong company score could otherwise reduce appeal.
        if (normalizedRevision != null) {
            appealBase += clamp(normalizedRevision * 0.8, -12, 8);
        }
        var appealScore = roundScore(appealBase);

        var crowdingBase = 20.0;
        if (normalizedEvToSales != null) {
            crowdingBase += clamp((normalizedEvToSales - 4.5) * 3.8, 0, 26);
        }
        if (normalizedAnalystRevision != null) {
            crowdingBase += clamp(normalizedAnalystRevision * 10, 0, 10);
        }
        if (normalizedGrowth != null) {
            crowdingBase += clamp((normalizedGrowth - 22) * 0.45, 0, 10);
        }
        if (normalizedMargin != null) {
            crowdingBase += clamp((normalizedMargin - 22) * 0.35, 0, 8);
        }
        var crowdingScore = roundScore(crowdingBase);

        var buyScore = roundScore(appealScore * 0.72 + (100 - crowdingScore) * 0.28);
        var label = buyScore >= 72
                ? CompanyBuyLabel.FAVORABLE
                : buyScore >= 56
                ? CompanyBuyLabel.SELECTIVE
                : CompanyBuyLabel.CHASE_RISK;

        var reasons = new ArrayList<String>();
        if (companyScore.totalScore() >= 72) {
            reasons.add("기초체력 점수 %d/100으로 상위권".formatted(companyScore.totalScore()));
        }
        if (normalizedGrowth != null && normalizedGrowth >= 15) {
            reasons.add("매출 성장 %s%%로 성장 모멘텀 유지".formatted(format1(normalizedGrowth)));
        }
        if (normalizedMargin != null && normalizedMargin >= 18) {
            reasons.add("영업이익률 %s%%로 수익성 우위".formatted(format1(normalizedMargin)));
        }
        if (normalizedUpside != null && normalizedUpside >= 8) {
            reasons.add("애널리스트 업사이드 %s%%".formatted(format1(normalizedUpside)));
        }
        if (normalizedRevision != null && normalizedRevision >= 3) {
            reasons.add("30일 EPS 추정치 %s%% 상향".formatted(format1(normalizedRevision)));
        } else if (normalizedRevision != null && normalizedRevision <= -3) {
            reasons.add("30일 EPS 추정치 %s%% 하향".formatted(format1(normalizedRevision)));
        }
        if (normalizedEvToSales != null && normalizedEvToSales >= 10) {
            reasons.add("EV/Sales %sx로 밸류 부담 존재".formatted(format1(normalizedEvToSales)));
        }
        if (crowdingScore >= 64) {
            reasons.add("과열도 %d/100 — 좋은 기업이어도 추격은 신중".formatted(crowdingScore));
        }
        if (reasons.isEmpty()) {
            reasons.add("기초체력은 무난하지만 현재는 선택적 접근 구간");
        }

        return new CompanyBuyScore(
                appealScore,
                crowdingScore,
                buyScore,
                label,
                reasons.subList(0, Math.min(4, reasons.size()))
        );
    }

    private static Double bounded(Double value, double min, double max) {
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return clamp(value, min, max);
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static int roundScore(double value) {
        return Math.max(0, Math.min(100, (int) Math.round(value)));
    }

    private static String format1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
