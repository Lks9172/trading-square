package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyFinancials;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.ScoreBreakdown;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CompanyScoringPolicy {

    public CompanyScore evaluate(CompanyFinancials financials) {
        var growth = computeGrowth(financials);
        var quality = computeQuality(financials);
        var valuation = computeValuation(financials);
        var balanceSheet = computeBalanceSheet(financials);
        var totalScore = averageScore(List.of(
                growth.value(),
                quality.value(),
                valuation.value(),
                balanceSheet.value()
        ));

        var reasons = new ArrayList<String>();
        addFirstReason(reasons, growth);
        addFirstReason(reasons, quality);
        addFirstReason(reasons, valuation);
        addFirstReason(reasons, balanceSheet);

        return new CompanyScore(
                financials.ticker(),
                totalScore,
                growth,
                quality,
                valuation,
                balanceSheet,
                reasons
        );
    }

    private ScoreBreakdown computeGrowth(CompanyFinancials financials) {
        var scores = new ArrayList<Integer>();
        var reasons = new ArrayList<String>();
        var growth = financials.revenueGrowthYoY();

        if (growth != null) {
            if (growth >= 20) {
                scores.add(90);
                reasons.add("매출 YoY %s%% 고성장".formatted(format1(growth)));
            } else if (growth >= 10) {
                scores.add(75);
                reasons.add("매출 YoY %s%% 양호".formatted(format1(growth)));
            } else if (growth >= 0) {
                scores.add(55);
                reasons.add("매출 YoY %s%% 완만 성장".formatted(format1(growth)));
            } else {
                scores.add(25);
                reasons.add("매출 YoY %s%% 역성장".formatted(format1(growth)));
            }
        }

        return new ScoreBreakdown(averageScore(scores), reasons);
    }

    private ScoreBreakdown computeQuality(CompanyFinancials financials) {
        var scores = new ArrayList<Integer>();
        var reasons = new ArrayList<String>();

        var operatingMargin = financials.operatingMargin();
        if (operatingMargin != null) {
            if (operatingMargin >= 25) {
                scores.add(90);
                reasons.add("영업이익률 %s%% 우수".formatted(format1(operatingMargin)));
            } else if (operatingMargin >= 15) {
                scores.add(75);
                reasons.add("영업이익률 %s%% 양호".formatted(format1(operatingMargin)));
            } else if (operatingMargin >= 5) {
                scores.add(55);
                reasons.add("영업이익률 %s%% 보통".formatted(format1(operatingMargin)));
            } else {
                scores.add(25);
                reasons.add("영업이익률 %s%% 낮음".formatted(format1(operatingMargin)));
            }
        }

        var freeCashFlowMargin = financials.freeCashFlowMargin();
        if (freeCashFlowMargin != null) {
            if (freeCashFlowMargin >= 20) {
                scores.add(88);
                reasons.add("FCF 마진 %s%% 우수".formatted(format1(freeCashFlowMargin)));
            } else if (freeCashFlowMargin >= 10) {
                scores.add(72);
                reasons.add("FCF 마진 %s%% 양호".formatted(format1(freeCashFlowMargin)));
            } else if (freeCashFlowMargin >= 0) {
                scores.add(55);
                reasons.add("FCF 마진 %s%% 보통".formatted(format1(freeCashFlowMargin)));
            } else {
                scores.add(20);
                reasons.add("FCF 마진 %s%% 음수".formatted(format1(freeCashFlowMargin)));
            }
        }

        var roe = financials.roe();
        if (roe != null) {
            if (roe >= 20) {
                scores.add(88);
                reasons.add("ROE %s%% 우수".formatted(format1(roe)));
            } else if (roe >= 12) {
                scores.add(72);
                reasons.add("ROE %s%% 양호".formatted(format1(roe)));
            } else if (roe >= 5) {
                scores.add(52);
                reasons.add("ROE %s%% 보통".formatted(format1(roe)));
            } else {
                scores.add(25);
                reasons.add("ROE %s%% 낮음".formatted(format1(roe)));
            }
        }

        var marginTrend = financials.operatingMarginTrend();
        if (marginTrend != null) {
            if (marginTrend >= 3) {
                scores.add(82);
                reasons.add("마진 추세 +%s%%p 개선".formatted(format1(marginTrend)));
            } else if (marginTrend <= -3) {
                scores.add(28);
                reasons.add("마진 추세 %s%%p 악화".formatted(format1(marginTrend)));
            }
        }

        var roic = financials.roic();
        if (roic != null) {
            if (roic >= 20) {
                scores.add(92);
                reasons.add("ROIC %s%%로 자본효율 우수".formatted(format1(roic)));
            } else if (roic >= 12) {
                scores.add(78);
                reasons.add("ROIC %s%%로 자본비용 상회 가능".formatted(format1(roic)));
            } else if (roic >= 7) {
                scores.add(60);
                reasons.add("ROIC %s%% 보통".formatted(format1(roic)));
            } else if (roic >= 0) {
                scores.add(40);
                reasons.add("ROIC %s%%로 자본효율 낮음".formatted(format1(roic)));
            } else {
                scores.add(20);
                reasons.add("ROIC %s%% 음수".formatted(format1(roic)));
            }
        }

        var accrualRatio = financials.accrualRatio();
        if (accrualRatio != null) {
            if (accrualRatio <= -5) {
                scores.add(90);
                reasons.add("발생액 비율 %s%%로 현금이익 우수".formatted(format1(accrualRatio)));
            } else if (accrualRatio <= 3) {
                scores.add(76);
                reasons.add("발생액 비율 %s%%로 이익 품질 양호".formatted(format1(accrualRatio)));
            } else if (accrualRatio <= 8) {
                scores.add(50);
                reasons.add("발생액 비율 %s%% 점검 필요".formatted(format1(accrualRatio)));
            } else {
                scores.add(24);
                reasons.add("발생액 비율 %s%%로 회계이익 대비 현금 부족".formatted(format1(accrualRatio)));
            }
        }

        return new ScoreBreakdown(averageScore(scores), reasons);
    }

    private ScoreBreakdown computeValuation(CompanyFinancials financials) {
        var scores = new ArrayList<Integer>();
        var reasons = new ArrayList<String>();

        var evToSales = financials.evToSales();
        if (evToSales != null) {
            if (evToSales <= 3) {
                scores.add(85);
                reasons.add("EV/Sales %sx 저평가 구간".formatted(format1(evToSales)));
            } else if (evToSales <= 6) {
                scores.add(70);
                reasons.add("EV/Sales %sx 수용 가능".formatted(format1(evToSales)));
            } else if (evToSales <= 10) {
                scores.add(45);
                reasons.add("EV/Sales %sx 고평가 부담".formatted(format1(evToSales)));
            } else {
                scores.add(20);
                reasons.add("EV/Sales %sx 과열 가능성".formatted(format1(evToSales)));
            }
        }

        var evToFcf = financials.evToFcf();
        if (evToFcf != null) {
            if (evToFcf <= 20) {
                scores.add(85);
                reasons.add("EV/FCF %sx 매력적".formatted(format1(evToFcf)));
            } else if (evToFcf <= 35) {
                scores.add(65);
                reasons.add("EV/FCF %sx 보통".formatted(format1(evToFcf)));
            } else if (evToFcf <= 50) {
                scores.add(40);
                reasons.add("EV/FCF %sx 부담".formatted(format1(evToFcf)));
            } else {
                scores.add(20);
                reasons.add("EV/FCF %sx 고평가".formatted(format1(evToFcf)));
            }
        }

        return new ScoreBreakdown(averageScore(scores), reasons);
    }

    private ScoreBreakdown computeBalanceSheet(CompanyFinancials financials) {
        var scores = new ArrayList<Integer>();
        var reasons = new ArrayList<String>();

        var netDebtToRevenue = financials.netDebtToRevenue();
        if (netDebtToRevenue != null) {
            if (netDebtToRevenue <= 0) {
                scores.add(90);
                reasons.add("순현금 또는 무차입 구조");
            } else if (netDebtToRevenue <= 0.5) {
                scores.add(75);
                reasons.add("순부채/매출 %sx 관리 가능".formatted(format2(netDebtToRevenue)));
            } else if (netDebtToRevenue <= 1) {
                scores.add(50);
                reasons.add("순부채/매출 %sx 중립".formatted(format2(netDebtToRevenue)));
            } else {
                scores.add(25);
                reasons.add("순부채/매출 %sx 부담".formatted(format2(netDebtToRevenue)));
            }
        }

        if (financials.cash() != null && financials.debt() != null) {
            if (financials.cash() >= financials.debt()) {
                scores.add(85);
            } else if (financials.cash() >= financials.debt() * 0.5) {
                scores.add(60);
            } else {
                scores.add(30);
            }
        }

        var shareDilution = financials.shareDilutionYoY();
        if (shareDilution != null) {
            if (shareDilution <= 0) {
                scores.add(82);
                reasons.add("주식수 YoY %s%%로 희석 제한적".formatted(format1(shareDilution)));
            } else if (shareDilution <= 2) {
                scores.add(65);
                reasons.add("주식수 YoY +%s%% 관리 가능".formatted(format1(shareDilution)));
            } else {
                scores.add(30);
                reasons.add("주식수 YoY +%s%% 희석 부담".formatted(format1(shareDilution)));
            }
        }

        var stockComp = financials.stockCompToRevenue();
        if (stockComp != null) {
            if (stockComp <= 3) {
                scores.add(80);
            } else if (stockComp <= 8) {
                scores.add(60);
            } else {
                scores.add(30);
                reasons.add("주식보상/매출 %s%% 부담".formatted(format1(stockComp)));
            }
        }

        var longTermDilution = financials.shareDilution3yCagr();
        if (longTermDilution != null) {
            if (longTermDilution <= 0) {
                scores.add(86);
                reasons.add("주식수 3년 CAGR %s%%로 장기 희석 없음".formatted(format1(longTermDilution)));
            } else if (longTermDilution <= 2) {
                scores.add(68);
                reasons.add("주식수 3년 CAGR +%s%% 관리 가능".formatted(format1(longTermDilution)));
            } else if (longTermDilution <= 5) {
                scores.add(45);
                reasons.add("주식수 3년 CAGR +%s%% 희석 주의".formatted(format1(longTermDilution)));
            } else {
                scores.add(22);
                reasons.add("주식수 3년 CAGR +%s%% 장기 희석 부담".formatted(format1(longTermDilution)));
            }
        }

        return new ScoreBreakdown(averageScore(scores), reasons);
    }

    private static void addFirstReason(List<String> target, ScoreBreakdown breakdown) {
        if (!breakdown.reasons().isEmpty()) {
            target.add(breakdown.reasons().getFirst());
        }
    }

    private static int averageScore(List<Integer> values) {
        if (values.isEmpty()) {
            return 0;
        }
        return clampScore((int) Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0)));
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static String format1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String format2(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
