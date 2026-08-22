package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFinancialQualityAssessment;
import io.macrosquare.company.domain.model.CompanyFinancialQualityAssessment.Risk;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyScore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/** Calculates cash-conversion evidence only from the current fundamentals snapshot. */
public final class CompanyFinancialQualityPolicy {

    public CompanyFinancialQualityAssessment evaluate(
            CompanyFundamentalsSnapshot value,
            CompanyScore companyScore
    ) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(companyScore, "companyScore");
        var ocfToIncome = safeRatio(value.operatingCashFlowTtm(), value.netIncomeTtm());
        var cash = 50.0;
        if (ocfToIncome != null) {
            cash += ocfToIncome >= 1.1 ? 20 : ocfToIncome >= 0.9 ? 12 : ocfToIncome >= 0.7 ? 0 : -18;
        }
        if (value.freeCashFlowMargin() != null) {
            cash += value.freeCashFlowMargin() >= 20 ? 18
                    : value.freeCashFlowMargin() >= 10 ? 10
                    : value.freeCashFlowMargin() >= 0 ? 2 : -18;
        }
        if (value.accrualRatio() != null) {
            cash += value.accrualRatio() <= 0 ? 12
                    : value.accrualRatio() <= 5 ? 6
                    : value.accrualRatio() <= 10 ? 0
                    : value.accrualRatio() <= 20 ? -12 : -22;
        }
        if (value.currentRatio() != null) {
            cash += value.currentRatio() >= 1.5 ? 6 : value.currentRatio() < 1 ? -10 : 0;
        }
        if (value.stockCompToRevenue() != null) {
            cash += value.stockCompToRevenue() <= 5 ? 4 : value.stockCompToRevenue() > 15 ? -12 : 0;
        }
        var cashScore = score(cash);
        var earningsScore = score(
                cashScore * 0.45
                        + companyScore.quality().value() * 0.30
                        + companyScore.balanceSheet().value() * 0.15
                        + dilutionScore(value.shareDilution3yCagr()) * 0.10
        );
        var risk = value.accrualRatio() == null ? Risk.UNAVAILABLE
                : value.accrualRatio() <= 5 ? Risk.LOW
                : value.accrualRatio() <= 12 ? Risk.MODERATE : Risk.HIGH;
        var liquidity = value.currentRatio() == null ? "자료부족"
                : value.currentRatio() >= 1.5 ? "양호"
                : value.currentRatio() >= 1 ? "보통" : "주의";

        var reasons = new ArrayList<String>();
        if (ocfToIncome != null) reasons.add("영업현금흐름/순이익 " + format2(ocfToIncome) + "배");
        if (value.freeCashFlowMargin() != null) reasons.add("FCF 마진 " + format1(value.freeCashFlowMargin()) + "%");
        if (value.accrualRatio() != null) reasons.add("발생액 비율 " + format1(value.accrualRatio()) + "%");
        if (value.stockCompToRevenue() != null) reasons.add("주식보상/매출 " + format1(value.stockCompToRevenue()) + "%");
        if (reasons.isEmpty()) reasons.add("현금흐름 세부 데이터가 제한적입니다.");

        var summary = earningsScore >= 70
                ? "현금창출과 회계이익의 질이 전반적으로 양호합니다."
                : earningsScore >= 50
                ? "현금창출력은 중립 범위이며 발생액·희석을 함께 확인해야 합니다."
                : "회계이익 대비 현금화 또는 희석 부담이 커 보수적 점검이 필요합니다.";
        return new CompanyFinancialQualityAssessment(
                cashScore, earningsScore, risk, rounded(ocfToIncome), liquidity, summary, reasons);
    }

    private static int dilutionScore(Double value) {
        if (value == null) return 50;
        if (value <= 0) return 90;
        if (value <= 2) return 72;
        if (value <= 5) return 48;
        return 25;
    }

    private static Double safeRatio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator <= 0) return null;
        var result = numerator / denominator;
        return Double.isFinite(result) ? result : null;
    }

    private static Double rounded(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static int score(double value) {
        return Math.max(0, Math.min(100, (int) Math.round(value)));
    }

    private static String format1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String format2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
