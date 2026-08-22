package io.macrosquare.company.domain.investment;

import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyInvestmentAction;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyOpportunityType;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyRiskLevel;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.DataQualityAssessment;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.DataQualityLevel;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.EntryStrategy;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.ForwardHorizon;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.ForwardOutlook;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.OutlookMethod;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.RiskAssessment;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.ScaleInEligibility;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.ScaleInEligibilityState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.BottomConviction;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.EvidenceStrength;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FibonacciSwingDirection;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FibonacciZoneState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FundamentalsReadiness;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.GuidanceDirection;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.HistoricalValidation;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.MarketBias;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.NarrativeStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.NarrativeTrend;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.MovingAverageState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceLocationState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceRecoveryStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceReversalStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceTrendState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ReversalState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.RiskBand;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.SectorRotationState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.SectorStance;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.TechnicalFlowState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ThesisState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.TimingEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ValuationRangePosition;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ValuationRelativePosition;
import io.macrosquare.company.domain.investment.InvestmentDimension.DimensionState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Transparent decision stack that keeps company quality, valuation, catalyst,
 * sector leadership, entry timing, and risk separate until the final gate.
 *
 * <p>The policy intentionally avoids interpreting a score as a probability.
 * Forward odds are historical only when a point-in-time walk-forward sample is
 * present; otherwise they are explicitly marked as a low-confidence heuristic.</p>
 */
public final class CompanyInvestmentDecisionPolicy {

    private static final int MAX_CURRENT_QUOTE_AGE_DAYS = 7;
    private static final int MAX_STRONG_FUNDAMENTALS_AGE_DAYS = 200;
    private static final int MAX_ACTIONABLE_FUNDAMENTALS_AGE_DAYS = 400;

    public static final String VERSION = "investment-decision-stack-v8-current-filing-readiness";

    public CompanyInvestmentDecision evaluate(CompanyInvestmentEvidence evidence) {
        var quality = quality(evidence);
        var valuation = valuation(evidence);
        var catalyst = catalyst(evidence);
        var sector = sector(evidence);
        var timing = timing(evidence);
        var risk = risk(evidence);
        var dataQuality = dataQuality(evidence, quality, valuation, catalyst, sector, timing);
        var scaleInEligibility = scaleInEligibility(
                evidence, quality, catalyst, risk, dataQuality);

        var merit = rounded(
                quality.score() * 0.30
                        + valuation.score() * 0.25
                        + catalyst.score() * 0.25
                        + sector.score() * 0.20
        );
        var entry = rounded(
                timing.score() * 0.35
                        + valuation.score() * 0.25
                        + catalyst.score() * 0.20
                        + sector.score() * 0.20
        );
        var decisionScore = clampScore(rounded(
                merit * 0.55
                        + entry * 0.45
                        - Math.max(0, risk.score() - 45) * 0.35
        ));
        var opportunity = opportunityType(
                quality, valuation, catalyst, sector, timing, risk, dataQuality);
        var action = action(
                merit, entry, decisionScore, quality, valuation, catalyst, sector,
                timing, risk, dataQuality, scaleInEligibility, opportunity, evidence);

        var whyNow = positiveEvidence(quality, valuation, catalyst, sector, timing);
        var whyWait = cautionEvidence(
                valuation, catalyst, sector, timing, risk, dataQuality, scaleInEligibility);
        var thesisBreaks = thesisBreaks(evidence);
        var outlooks = forwardOutlooks(evidence, merit, entry, risk.score(), dataQuality.confidence());
        var summary = summary(
                action, opportunity, quality, valuation, catalyst, sector, timing,
                risk, scaleInEligibility);
        var entryStrategy = entryStrategy(action, evidence, scaleInEligibility);

        return new CompanyInvestmentDecision(
                VERSION,
                merit,
                entry,
                decisionScore,
                action,
                opportunity,
                quality,
                valuation,
                catalyst,
                sector,
                timing,
                risk,
                dataQuality,
                scaleInEligibility,
                entryStrategy,
                outlooks,
                summary,
                whyNow,
                whyWait,
                thesisBreaks,
                "기업 건강도·가격 매력도·기대 변화·섹터 순풍을 투자 매력도로, "
                        + "가격/거래량 반전·지지/저항 구간·다우 스윙·회귀 채널·주봉 피보 합치를 진입 적합도로 분리합니다. "
                        + "분할매수는 기업 품질·현금흐름·재무안정성·실적 가설이 살아 있는 회복 가능 자산에만 허용합니다. "
                        + "RSI 과매도는 지지·구조 전환·수급이 합쳐질 때만 보조 근거로 사용합니다. "
                        + "위험 게이트와 데이터 신뢰도가 최종 액션의 상한을 제한합니다."
        );
    }

    private static InvestmentDimension quality(CompanyInvestmentEvidence evidence) {
        var score = evidence.scores();
        var value = evidence.fundamentals();
        var inputs = new ArrayList<WeightedInput>();
        add(inputs, score.qualityScore(), 24, "기존 수익성·품질 점수");
        add(inputs, score.balanceSheetScore(), 16, "재무안정성 점수");
        add(inputs, score.growthScore(), 8, "성장 지속성 점수");
        add(inputs, ratioScore(value.roic(), -5, 0, 8, 15, 25), 12, "ROIC");
        add(inputs, ratioScore(value.freeCashFlowMargin(), -10, 0, 8, 16, 28), 9, "FCF 마진");
        add(inputs, value.cashConversionScore(), 7, "현금전환");
        add(inputs, value.earningsQualityScore(), 7, "이익의 질");
        add(inputs, inverseScore(value.shareDilution3yCagr(), 0, 2, 5, 10), 5, "장기 희석");
        add(inputs, inverseScore(value.stockCompToRevenue(), 3, 8, 15, 30), 4, "주식보상");
        add(inputs, accrualScore(value.accrualRatio()), 3, "발생액");
        add(inputs, value.bottleneckScore(), 3, "병목 지위");
        add(inputs, strengthScore(value.pricingPower()), 2, "가격결정력");

        var weighted = weighted(inputs);
        var reasons = new ArrayList<String>();
        var cautions = new ArrayList<String>();
        if (value.roic() != null) {
            addByThreshold(reasons, cautions, ratioScore(value.roic(), -5, 0, 8, 15, 25),
                    "ROIC %s%%로 자본효율이 우수합니다.".formatted(format1(value.roic())),
                    "ROIC %s%%로 자본효율이 낮습니다.".formatted(format1(value.roic())));
        }
        if (value.freeCashFlowMargin() != null) {
            addByThreshold(reasons, cautions, ratioScore(value.freeCashFlowMargin(), -10, 0, 8, 16, 28),
                    "FCF 마진 %s%%로 현금창출력이 좋습니다.".formatted(format1(value.freeCashFlowMargin())),
                    "FCF 마진 %s%%로 현금창출력이 약합니다.".formatted(format1(value.freeCashFlowMargin())));
        }
        if (value.shareDilution3yCagr() != null && value.shareDilution3yCagr() > 4) {
            cautions.add("주식 수 3년 CAGR +" + format1(value.shareDilution3yCagr()) + "%로 희석 부담이 큽니다.");
        }
        if (value.stockCompToRevenue() != null && value.stockCompToRevenue() > 12) {
            cautions.add("주식보상/매출 " + format1(value.stockCompToRevenue()) + "%로 주주가치 희석을 점검해야 합니다.");
        }
        if (value.bottleneckScore() != null && value.bottleneckScore() >= 70
                && value.backlogSignal() == EvidenceStrength.STRONG) {
            reasons.add("병목 지위와 수주잔고 증거가 함께 확인됩니다.");
        }
        if (reasons.isEmpty() && score.qualityScore() != null && score.qualityScore() >= 65) {
            reasons.add("수익성·재무 품질의 종합 점수가 양호합니다.");
        }
        if (cautions.isEmpty() && score.qualityScore() != null && score.qualityScore() < 50) {
            cautions.add("현재 수익성·품질 지표만으로 구조적 우위를 확신하기 어렵습니다.");
        }
        return dimension("quality", "기업 건강도", weighted, reasons, cautions);
    }

    private static InvestmentDimension valuation(CompanyInvestmentEvidence evidence) {
        var score = evidence.scores();
        var value = evidence.valuation();
        var inputs = new ArrayList<WeightedInput>();
        add(inputs, score.valuationScore(), 38, "기존 밸류 점수");
        add(inputs, evFcfScore(value.evToFreeCashFlow()), 20, "EV/FCF");
        add(inputs, evSalesScore(value.evToSales()), 12, "EV/Sales");
        add(inputs, peerPremiumScore(value.premiumPctVsPeerAverage()), 10, "peer 평균 대비");
        add(inputs, peerPremiumScore(value.premiumPctVsPeerMedian()), 7, "peer 중앙값 대비");
        add(inputs, rangeScore(value.internalRange()), 8, "자체 밸류 밴드");
        add(inputs, relativeScore(value.peerPosition()), 5, "peer 상대 밸류");

        var weighted = weighted(inputs);
        var reasons = new ArrayList<String>();
        var cautions = new ArrayList<String>();
        if (value.evToFreeCashFlow() != null) {
            var multiple = value.evToFreeCashFlow();
            if (multiple <= 20) reasons.add("EV/FCF " + format1(multiple) + "배로 현금흐름 대비 가격 부담이 낮습니다.");
            if (multiple >= 45) cautions.add("EV/FCF " + format1(multiple) + "배로 현금흐름 대비 가격 부담이 큽니다.");
        }
        if (value.premiumPctVsPeerMedian() != null) {
            if (value.premiumPctVsPeerMedian() <= -10) {
                reasons.add("동종기업 중앙값 대비 " + format1(Math.abs(value.premiumPctVsPeerMedian())) + "% 할인입니다.");
            } else if (value.premiumPctVsPeerMedian() >= 25) {
                cautions.add("동종기업 중앙값 대비 " + format1(value.premiumPctVsPeerMedian()) + "% 프리미엄입니다.");
            }
        }
        if (value.internalRange() == ValuationRangePosition.UNDERVALUED) {
            reasons.add("자체 역사적 밸류 범위에서 저평가권입니다.");
        } else if (value.internalRange() == ValuationRangePosition.OVERVALUED) {
            cautions.add("자체 역사적 밸류 범위에서 고평가권입니다.");
        }
        if (value.multipleCompressionRisk() == RiskBand.HIGH) {
            cautions.add("금리·기대 변화에 따른 멀티플 압축 위험이 높습니다.");
        }
        if (weighted.confidence() < 45) {
            cautions.add("역사 밴드·동종기업 멀티플 증거가 부족해 가격 판단 신뢰도가 낮습니다.");
        }
        return dimension("valuation", "가격 매력도", weighted, reasons, cautions);
    }

    private static InvestmentDimension catalyst(CompanyInvestmentEvidence evidence) {
        var value = evidence.catalyst();
        var fundamental = evidence.fundamentals();
        var inputs = new ArrayList<WeightedInput>();
        add(inputs, revisionScore(value.estimateRevision30d()), 24, "30일 EPS 추정치");
        add(inputs, revisionScore(value.estimateRevision90d()), 14, "90일 EPS 추정치");
        add(inputs, revisionScore(value.estimateRevision7d()), 10, "7일 EPS 추정치");
        add(inputs, analystRevisionScore(value.analystScoreRevision30d()), 5, "애널리스트 의견 변화");
        add(inputs, guidanceScore(value.guidanceDirection()), 18, "가이던스");
        add(inputs, value.earningsBottomScore(), 9, "실적 바닥");
        add(inputs, growthScore(fundamental.revenueGrowthYoY()), 7, "매출 성장");
        add(inputs, trendScore(fundamental.operatingMarginTrend()), 5, "마진 추세");
        add(inputs, narrativeTrendScore(value.narrativeTrend(), value.narrativeStage()), 4, "내러티브 방향");
        add(inputs, boundedUpsideScore(value.estimateUpsidePct()), 2, "목표가 업사이드");
        add(inputs, fundamental.bottleneckScore(), 2, "병목 증거");

        var weighted = weighted(inputs);
        var reasons = new ArrayList<String>();
        var cautions = new ArrayList<String>();
        if (value.estimateRevision30d() != null) {
            if (value.estimateRevision30d() >= 3) {
                reasons.add("30일 EPS 추정치가 +" + format1(value.estimateRevision30d()) + "% 상향됐습니다.");
            } else if (value.estimateRevision30d() <= -3) {
                cautions.add("30일 EPS 추정치가 " + format1(value.estimateRevision30d()) + "% 하향됐습니다.");
            }
        }
        switch (value.guidanceDirection()) {
            case RAISED -> reasons.add("회사 가이던스가 상향돼 실적 기대를 지지합니다.");
            case AFFIRMED -> reasons.add("가이던스가 유지돼 핵심 실적 가설은 살아 있습니다.");
            case LOWERED -> cautions.add("가이던스가 하향돼 가격이 싸 보여도 추가 실적 하향 위험이 있습니다.");
            case MIXED -> cautions.add("가이던스 항목별 방향이 엇갈려 촉매 확신이 제한됩니다.");
            case UNKNOWN -> cautions.add("최근 구조화된 가이던스 방향을 확인하지 못했습니다.");
        }
        if (value.narrativeStage() == NarrativeStage.OVERHEATED) {
            cautions.add("내러티브가 과열 단계라 좋은 뉴스가 이미 가격에 반영됐을 수 있습니다.");
        } else if (value.narrativeStage() == NarrativeStage.EARLY
                && value.narrativeTrend() == NarrativeTrend.HEATING) {
            reasons.add("내러티브가 초기 확산 단계에서 강화되고 있습니다.");
        }
        return dimension("catalyst", "기대 변화·촉매", weighted, reasons, cautions);
    }

    private static InvestmentDimension sector(CompanyInvestmentEvidence evidence) {
        var value = evidence.sector();
        var inputs = new ArrayList<WeightedInput>();
        add(inputs, value.rotationScore(), 22, "섹터 순환");
        add(inputs, value.earningsRevisionScore(), 18, "섹터 이익 수정");
        add(inputs, value.relativeStrengthScore(), 15, "상대강도");
        add(inputs, value.macroFitScore(), 12, "거시 정합");
        add(inputs, value.fundamentalScore(), 10, "섹터 펀더멘털");
        add(inputs, value.flowScore(), 8, "자금 흐름");
        add(inputs, value.buyScore(), 6, "섹터 B 점수");
        add(inputs, value.qualityScore(), 4, "섹터 품질");
        add(inputs, inverseDirectScore(value.crowdingScore()), 3, "과열 완화");
        add(inputs, marketBiasScore(value.marketBias()), 2, "상위 자산 방향");

        var weighted = weighted(inputs);
        var reasons = new ArrayList<String>();
        var cautions = new ArrayList<String>();
        if (value.rotationState() == SectorRotationState.LEADING) {
            reasons.add("해당 섹터의 현재 거시·상대강도 순위가 상위권입니다.");
        } else if (value.rotationState() == SectorRotationState.IMPROVING) {
            reasons.add("해당 섹터는 다음 주도 후보로 개선 중"
                    + leadershipSuffix(value.expectedLeadershipWindow()) + ".");
        } else if (value.rotationState() == SectorRotationState.WEAKENING) {
            cautions.add("섹터 리더십이 약화 단계로 전환되고 있습니다.");
        } else if (value.rotationState() == SectorRotationState.LAGGING) {
            cautions.add("섹터 상대강도와 순환 우선순위가 아직 낮습니다.");
        }
        if (value.earningsRevisionScore() != null && value.earningsRevisionScore() >= 70) {
            reasons.add("섹터 이익 추정치 확산도가 강합니다.");
        } else if (value.earningsRevisionScore() != null && value.earningsRevisionScore() < 45) {
            cautions.add("섹터 이익 추정치가 주가 모멘텀을 뒷받침하지 못합니다.");
        }
        if (value.crowdingScore() != null && value.crowdingScore() >= 70) {
            cautions.add("섹터 과열 " + value.crowdingScore() + "/100으로 추격 위험이 큽니다.");
        }
        if (value.stance() == SectorStance.AVOIDED || value.marketBias() == MarketBias.SELL) {
            cautions.add("상위 시장·섹터 게이트가 신규 공격 매수를 지지하지 않습니다.");
        }
        if (weighted.confidence() < 40) {
            cautions.add("섹터 순환·수급 데이터 연결이 부족해 중립값 의존도가 높습니다.");
        }
        return dimension("sector", "섹터 순풍", weighted, reasons, cautions);
    }

    private static InvestmentDimension timing(CompanyInvestmentEvidence evidence) {
        var value = evidence.timing();
        var inputs = new ArrayList<WeightedInput>();
        add(inputs, value.reversalScore(), 20, "반전 확인");
        add(inputs, value.priceStructureScore(), 18, "지지·다우·채널 구조");
        add(inputs, value.confirmedBottomScore(), 15, "확신형 바닥");
        add(inputs, value.technicalConfirmationScore(), 12, "VWAP/OBV 수급");
        add(inputs, value.volumeConfirmationScore(), 11, "거래량 확인");
        add(inputs, value.bottomScore(), 7, "바닥 구조");
        add(inputs, value.priceBottomScore(), 5, "가격 리셋");
        add(inputs, value.correctionScore(), 4, "조정 가능성");
        add(inputs, inverseDirectScore(value.failureRiskScore()), 4, "바닥 실패 위험");
        add(inputs, value.shortTermScore(), 2, "단기 프레임");
        add(inputs, value.swingTermScore(), 2, "스윙 프레임");
        add(inputs, fibonacciTimingScore(value), 4, "주요 파동 피보 합치");

        var weighted = weighted(inputs);
        var reasons = new ArrayList<String>();
        var cautions = new ArrayList<String>();
        if (value.reversalState() == ReversalState.STRONG) {
            reasons.add("가격·거래량 반전 확인이 강하게 켜졌습니다.");
        } else if (value.reversalState() == ReversalState.ON) {
            reasons.add("반전 확인 신호가 켜져 분할 진입 근거가 생겼습니다.");
        } else if (value.reversalState() == ReversalState.EARLY) {
            cautions.add("반전은 초기 단계라 후속 거래량 확인이 필요합니다.");
        } else if (value.reversalState() == ReversalState.OFF) {
            cautions.add("반전 확인 신호가 아직 꺼져 있습니다.");
        }
        if (value.bottomConviction() == BottomConviction.CONVICTION) {
            reasons.add("확신형 바닥 후보 조건이 충족됐습니다.");
        } else if (value.bottomConviction() == BottomConviction.CANDIDATE) {
            cautions.add("바닥은 후보 단계이며 확신 단계는 아닙니다.");
        }
        if (value.volumeConfirmationScore() != null && value.volumeConfirmationScore() >= 70) {
            reasons.add("하락 매도세를 흡수하는 거래량 확인이 강합니다.");
        } else if (value.volumeConfirmationScore() != null && value.volumeConfirmationScore() < 45) {
            cautions.add("바닥을 확증할 거래량이 충분하지 않습니다.");
        }
        if (value.technicalFlowState() == TechnicalFlowState.DISTRIBUTION) {
            cautions.add("VWAP/OBV 기준 분산 우위라 반등 추격을 피해야 합니다.");
        }
        switch (value.priceRecoveryStage()) {
            case RETEST_HELD -> reasons.add("직전 고점 돌파 뒤 높아진 저점 지지까지 확인됐습니다.");
            case STRUCTURE_BREAK -> reasons.add("하락 구조의 직전 고점을 돌파해 1차 추세 전환이 확인됐습니다.");
            case REBOUND -> reasons.add("최근 저점에서 반등이 진행 중이지만 고점 돌파 재확인이 남았습니다.");
            case BASE_BUILDING -> reasons.add("지지/채널 하단에서 바닥 다지기가 진행 중입니다.");
            default -> {
            }
        }
        if (value.stopHuntReclaim()) {
            reasons.add("지지 이탈 뒤 거래량을 동반해 구간을 회복한 스톱헌트 재진입이 확인됩니다.");
        }
        if (value.volumeBreakout()) {
            reasons.add("가격 돌파에 평소보다 강한 거래량이 동반됐습니다.");
        }
        if (value.oversoldConfluence()) {
            reasons.add("RSI 과매도와 지지·구조·수급이 동시에 맞물렸습니다.");
        } else if (value.rsi14() != null && value.rsi14() <= 30) {
            cautions.add("RSI는 과매도지만 다른 확인 조건이 부족해 단독 매수 근거로 쓰지 않습니다.");
        }
        if (value.fibonacciSwingDirection() == FibonacciSwingDirection.UP_SWING
                && value.fibonacciConfluenceScore() != null
                && value.fibonacciConfluenceScore() >= 60
                && (value.fibonacciZoneState() == FibonacciZoneState.MODERATE_RETRACEMENT
                || value.fibonacciZoneState() == FibonacciZoneState.DEEP_RETRACEMENT
                || value.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE)) {
            reasons.add("상승 주요 파동의 피보 구간이 주봉·지지/저항·채널 근거와 겹칩니다.");
        }
        if (value.fibonacciSwingDirection() == FibonacciSwingDirection.DOWN_SWING) {
            cautions.add("현재 피보 기준은 하락 파동의 반등 저항이므로 분할매수 지지선으로 해석하지 않습니다.");
        }
        if (value.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE_BROKEN) {
            cautions.add("주요 상승 파동의 0.786 마지막 방어선이 훼손돼 추가 매수보다 구조 회복이 우선입니다.");
        }
        switch (value.priceReversalStage()) {
            case MOMENTUM_WEAKENING -> cautions.add("상승 추세 훼손 1단계로 모멘텀 약화가 나타났습니다.");
            case STRUCTURAL_CRACK -> cautions.add("상승 추세 훼손 2단계인 낮아진 고점/구조 균열이 확인됐습니다.");
            case PRIOR_LOW_BROKEN -> cautions.add("상승 추세 훼손 3단계인 이전 저점 이탈이 확인됐습니다.");
            default -> {
            }
        }
        if (value.priceLocationState() == PriceLocationState.UPPER_CHANNEL
                || value.priceLocationState() == PriceLocationState.RESISTANCE_ZONE) {
            cautions.add("채널 상단/저항 구간이라 추격보다 돌파 또는 눌림 확인이 우선입니다.");
        } else if (value.priceLocationState() == PriceLocationState.BREAKDOWN) {
            cautions.add("지지·채널 하단을 이탈해 회복 전까지 바닥으로 단정할 수 없습니다.");
        }
        return dimension("timing", "진입 타이밍", weighted, reasons, cautions);
    }

    private static RiskAssessment risk(CompanyInvestmentEvidence evidence) {
        var fundamental = evidence.fundamentals();
        var valuation = evidence.valuation();
        var catalyst = evidence.catalyst();
        var sector = evidence.sector();
        var timing = evidence.timing();
        var reasons = new ArrayList<String>();
        var score = 14;

        if (timing.thesisState() == ThesisState.BREAK_RISK) {
            score += 38;
            reasons.add("핵심 투자 논리가 훼손 경계입니다.");
        } else if (timing.thesisState() == ThesisState.WEAKENED) {
            score += 18;
            reasons.add("핵심 투자 논리가 일부 약화됐습니다.");
        }
        if (timing.trendBreakRiskScore() != null) {
            score += Math.max(0, timing.trendBreakRiskScore() - 50) / 3;
            if (timing.trendBreakRiskScore() >= 65) reasons.add("추세 훼손 위험 점수가 높습니다.");
        }
        if (timing.priceReversalStage() == PriceReversalStage.PRIOR_LOW_BROKEN) {
            score += 30;
            reasons.add("가격 구조 3단계인 이전 저점 이탈이 발생했습니다.");
        } else if (timing.priceReversalStage() == PriceReversalStage.STRUCTURAL_CRACK) {
            score += 16;
            reasons.add("가격 구조 2단계인 낮아진 고점/구조 균열이 확인됐습니다.");
        } else if (timing.priceReversalStage() == PriceReversalStage.MOMENTUM_WEAKENING) {
            score += 7;
            reasons.add("가격 구조 1단계인 모멘텀 약화가 나타났습니다.");
        }
        if (timing.priceTrendState() == PriceTrendState.DOWNTREND) {
            score += 10;
            reasons.add("고점과 저점이 함께 낮아지는 하락 구조입니다.");
        }
        if (timing.priceLocationState() == PriceLocationState.BREAKDOWN) {
            score += 16;
            reasons.add("지지 구간 또는 회귀 채널 하단을 이탈했습니다.");
        }
        if (timing.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE_BROKEN) {
            score += 12;
            reasons.add("주요 상승 파동의 0.786 마지막 방어선이 이탈했습니다.");
        } else if (timing.fibonacciSwingDirection() == FibonacciSwingDirection.DOWN_SWING) {
            score += 4;
            reasons.add("현재 피보 구조는 하락 파동의 반등 저항을 가리킵니다.");
        }
        if (catalyst.guidanceDirection() == GuidanceDirection.LOWERED) {
            score += 18;
            reasons.add("가이던스 하향이 확인됐습니다.");
        }
        if (catalyst.estimateRevision30d() != null && catalyst.estimateRevision30d() <= -4) {
            score += 14;
            reasons.add("최근 30일 실적 기대가 빠르게 하향됐습니다.");
        }
        if (sector.crowdingScore() != null && sector.crowdingScore() >= 70) {
            score += 12;
            reasons.add("섹터 과열과 군중화 부담이 큽니다.");
        } else if (evidence.scores().crowdingScore() != null && evidence.scores().crowdingScore() >= 70) {
            score += 10;
            reasons.add("종목 과열도가 높아 추격 위험이 큽니다.");
        }
        if (valuation.multipleCompressionRisk() == RiskBand.HIGH) {
            score += 10;
            reasons.add("멀티플 압축 위험이 높습니다.");
        }
        if (catalyst.narrativeStage() == NarrativeStage.OVERHEATED) {
            score += 10;
            reasons.add("내러티브가 과열 단계입니다.");
        }
        if (fundamental.netDebtToRevenue() != null && fundamental.netDebtToRevenue() > 1) {
            score += 12;
            reasons.add("순부채 부담이 매출 대비 높습니다.");
        }
        if (fundamental.freeCashFlowMargin() != null && fundamental.freeCashFlowMargin() < 0) {
            score += 10;
            reasons.add("잉여현금흐름 마진이 음수입니다.");
        }
        if (fundamental.shareDilution3yCagr() != null && fundamental.shareDilution3yCagr() > 5) {
            score += 8;
            reasons.add("장기 주식 희석이 빠릅니다.");
        }
        if (sector.marketBias() == MarketBias.SELL) {
            score += 12;
            reasons.add("상위 자산 신호가 매도/회피입니다.");
        } else if (sector.marketBias() == MarketBias.REDUCE) {
            score += 7;
            reasons.add("상위 자산 신호가 비중 축소입니다.");
        }
        if (timing.failureRiskScore() != null && timing.failureRiskScore() >= 65) {
            score += 8;
            reasons.add("바닥 신호 실패 위험이 높습니다.");
        }
        if (timing.quoteAgeDays() != null && timing.quoteAgeDays() > 7) {
            score += 5;
            reasons.add("가격 데이터가 오래돼 현재 진입 판단 오차가 커질 수 있습니다.");
        }
        if (timing.fundamentalsAgeDays() != null
                && timing.fundamentalsAgeDays() > MAX_STRONG_FUNDAMENTALS_AGE_DAYS) {
            score += timing.fundamentalsAgeDays() > MAX_ACTIONABLE_FUNDAMENTALS_AGE_DAYS ? 12 : 6;
            reasons.add("재무 기준일이 오래돼 현재 기업 체력과 실적 가설을 다시 확인해야 합니다.");
        }

        score = clampScore(score);
        var level = score >= 76 ? CompanyRiskLevel.CRITICAL
                : score >= 58 ? CompanyRiskLevel.HIGH
                : score >= 34 ? CompanyRiskLevel.MODERATE
                : CompanyRiskLevel.LOW;
        if (reasons.isEmpty()) reasons.add("현재 확인된 핵심 위험 게이트는 제한적입니다.");
        var summary = switch (level) {
            case LOW -> "핵심 위험 게이트가 낮아 점수대로 판단할 수 있습니다.";
            case MODERATE -> "일부 위험이 있어 분할 진입과 후속 확인이 필요합니다.";
            case HIGH -> "위험이 높아 신규 공격 매수보다 확인 또는 축소가 우선입니다.";
            case CRITICAL -> "투자 논리 훼손 가능성이 커 신규 진입을 중단해야 합니다.";
        };
        return new RiskAssessment(score, level, summary, reasons.stream().limit(5).toList());
    }

    private static DataQualityAssessment dataQuality(
            CompanyInvestmentEvidence evidence,
            InvestmentDimension... dimensions
    ) {
        var coverage = evidence.evidenceCoveragePct();
        var dimensionConfidence = rounded(
                java.util.Arrays.stream(dimensions)
                        .mapToInt(InvestmentDimension::confidence)
                        .average()
                        .orElse(0)
        );
        var warningPenalty = Math.min(25, evidence.dataWarnings().size() * 4);
        var freshnessPenalty = freshnessPenalty(evidence);
        var confidence = clampScore(rounded(
                coverage * 0.55 + dimensionConfidence * 0.45 - warningPenalty - freshnessPenalty));
        var level = confidence >= 72 ? DataQualityLevel.HIGH
                : confidence >= 48 ? DataQualityLevel.MODERATE
                : DataQualityLevel.LOW;
        var summary = switch (level) {
            case HIGH -> "핵심 판단축의 데이터 커버리지가 충분합니다.";
            case MODERATE -> "판단은 가능하지만 일부 축은 보조값 또는 누락 데이터에 민감합니다.";
            case LOW -> "데이터 공백이 커서 최종 액션을 보수적으로 제한합니다.";
        };
        return new DataQualityAssessment(
                coverage,
                confidence,
                level,
                summary,
                evidence.dataWarnings().stream().limit(6).toList()
        );
    }

    private static int freshnessPenalty(CompanyInvestmentEvidence evidence) {
        var timing = evidence.timing();
        var penalty = 0;
        if (timing.quoteAgeDays() == null) {
            penalty += 30;
        } else {
            if (timing.quoteAgeDays() > 14) penalty += 30;
            else if (timing.quoteAgeDays() > MAX_CURRENT_QUOTE_AGE_DAYS) penalty += 15;
        }
        if (timing.fundamentalsAgeDays() == null) {
            penalty += 30;
        } else {
            if (timing.fundamentalsAgeDays() > MAX_ACTIONABLE_FUNDAMENTALS_AGE_DAYS) penalty += 30;
            else if (timing.fundamentalsAgeDays() > MAX_STRONG_FUNDAMENTALS_AGE_DAYS) penalty += 12;
        }
        if (evidence.fundamentalsReadiness() != FundamentalsReadiness.CURRENT) {
            penalty += 30;
        }
        return Math.min(45, penalty);
    }

    private static ScaleInEligibility scaleInEligibility(
            CompanyInvestmentEvidence evidence,
            InvestmentDimension quality,
            InvestmentDimension catalyst,
            RiskAssessment risk,
            DataQualityAssessment dataQuality
    ) {
        var fundamental = evidence.fundamentals();
        var timing = evidence.timing();
        var scores = evidence.scores();
        var reasons = new LinkedHashSet<String>();
        var blockers = new LinkedHashSet<String>();
        var score = rounded(
                quality.score() * 0.45
                        + catalyst.score() * 0.20
                        + (100 - risk.score()) * 0.25
                        + dataQuality.confidence() * 0.10
        );

        if (quality.score() >= 65) reasons.add("기업 건강도가 분할매수 가능한 범위입니다.");
        if (scores.balanceSheetScore() != null && scores.balanceSheetScore() >= 60) {
            reasons.add("재무안정성 점수가 급락 후 회복 시간을 버틸 여력을 지지합니다.");
        }
        if (fundamental.freeCashFlowMargin() != null && fundamental.freeCashFlowMargin() >= 0) {
            reasons.add("잉여현금흐름이 양수라 외부자금 의존 없이 회복할 여지가 있습니다.");
        }
        if (fundamental.roic() != null && fundamental.roic() >= 8) {
            reasons.add("ROIC가 자본비용 충격을 흡수할 수 있는 범위입니다.");
        }
        if (evidence.catalyst().guidanceDirection() == GuidanceDirection.RAISED
                || evidence.catalyst().guidanceDirection() == GuidanceDirection.AFFIRMED) {
            reasons.add("최근 가이던스가 유지/상향돼 실적 가설이 살아 있습니다.");
        }
        if (fundamental.bottleneckScore() != null && fundamental.bottleneckScore() >= 65) {
            reasons.add("병목·해자 증거가 가격 하락 뒤 회복 가능성을 보강합니다.");
        }

        if (timing.thesisState() == ThesisState.BREAK_RISK) {
            blockers.add("핵심 투자 논리가 훼손 경계라 평균단가를 낮추면 안 됩니다.");
            score -= 35;
        }
        if (timing.quoteAgeDays() == null) {
            blockers.add("현재 가격 기준일을 확인할 수 없어 신규 분할매수를 판정할 수 없습니다.");
            score -= 25;
        } else if (timing.quoteAgeDays() > MAX_CURRENT_QUOTE_AGE_DAYS) {
            blockers.add("가격 데이터가 7일을 넘어 현재 진입 가격으로 사용할 수 없습니다.");
            score -= 20;
        }
        if (timing.fundamentalsAgeDays() == null) {
            blockers.add("재무 기준일을 확인할 수 없어 현재 회복 가능 자산인지 판정할 수 없습니다.");
            score -= 30;
        } else if (timing.fundamentalsAgeDays() > MAX_ACTIONABLE_FUNDAMENTALS_AGE_DAYS) {
            blockers.add("재무 데이터가 400일을 넘어 회복 가능 자산인지 현재 기준으로 판정할 수 없습니다.");
            score -= 30;
        } else if (timing.fundamentalsAgeDays() > MAX_STRONG_FUNDAMENTALS_AGE_DAYS) {
            score -= 10;
        }
        if (evidence.fundamentalsReadiness() != FundamentalsReadiness.CURRENT) {
            blockers.add("재무 계산이 최신 확인 공시를 아직 반영하지 않아 신규 분할매수를 판정할 수 없습니다.");
            score -= 30;
        }
        if (timing.priceReversalStage() == PriceReversalStage.PRIOR_LOW_BROKEN
                && !timing.stopHuntReclaim()) {
            blockers.add("이전 저점 이탈 후 회복이 없어 실패 자산 물타기 위험이 큽니다.");
            score -= 25;
        }
        if (risk.level() == CompanyRiskLevel.CRITICAL) {
            blockers.add("위험 게이트가 CRITICAL입니다.");
            score -= 30;
        }
        if (quality.score() < 40) {
            blockers.add("기업 건강도가 낮아 가격 하락을 일시적 조정으로 볼 근거가 없습니다.");
            score -= 20;
        }
        if (scores.balanceSheetScore() != null && scores.balanceSheetScore() < 35) {
            blockers.add("재무안정성이 낮아 회복 전 자금조달·희석 위험이 큽니다.");
            score -= 18;
        }
        var negativeFcf = fundamental.freeCashFlowMargin() != null
                && fundamental.freeCashFlowMargin() < 0;
        var earningsCut = evidence.catalyst().guidanceDirection() == GuidanceDirection.LOWERED
                || (evidence.catalyst().estimateRevision30d() != null
                && evidence.catalyst().estimateRevision30d() <= -4);
        if (negativeFcf && earningsCut) {
            blockers.add("음의 FCF와 실적 기대 하향이 겹쳐 가치함정 위험이 큽니다.");
            score -= 22;
        } else {
            if (negativeFcf) score -= 10;
            if (earningsCut) score -= 12;
        }
        if (timing.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE_BROKEN
                && timing.priceTrendState() == PriceTrendState.DOWNTREND
                && !timing.stopHuntReclaim()) {
            blockers.add("상승 주요 파동 0.786과 하락 구조가 함께 훼손됐습니다.");
            score -= 18;
        } else if (timing.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE_BROKEN) {
            score -= 8;
        }
        if (evidence.catalyst().narrativeStage() == NarrativeStage.OVERHEATED
                || (scores.crowdingScore() != null && scores.crowdingScore() >= 75)) {
            score -= 8;
        }

        score = clampScore(score);
        ScaleInEligibilityState state;
        int concentrationCap;
        String summary;
        if (dataQuality.confidence() < 35) {
            state = ScaleInEligibilityState.UNAVAILABLE;
            concentrationCap = 0;
            blockers.add("회복 가능성을 판정할 재무·실적 데이터가 부족합니다.");
            summary = "데이터 부족으로 분할매수 적격성을 판정하지 않습니다.";
        } else if (!blockers.isEmpty()) {
            state = ScaleInEligibilityState.INELIGIBLE;
            concentrationCap = 0;
            summary = "가격이 싸 보여도 회복 가능 자산 요건을 충족하지 않아 물타기를 금지합니다.";
        } else if (score >= 68 && quality.score() >= 60 && risk.level() != CompanyRiskLevel.HIGH) {
            state = ScaleInEligibilityState.ELIGIBLE;
            var crowded = scores.crowdingScore() != null && scores.crowdingScore() >= 70;
            concentrationCap = quality.score() >= 80 && !crowded ? 12 : crowded ? 7 : 10;
            summary = "기업·현금흐름·실적 가설이 살아 있어 가격 구조를 확인하며 분할매수할 수 있습니다.";
        } else {
            state = ScaleInEligibilityState.CONDITIONAL;
            concentrationCap = 5;
            summary = "회복 가능성은 남아 있지만 약한 축이 있어 소액 1차와 후속 확인만 허용합니다.";
        }
        return new ScaleInEligibility(
                score,
                state,
                concentrationCap,
                summary,
                reasons.stream().limit(6).toList(),
                blockers.stream().limit(6).toList()
        );
    }

    private static CompanyInvestmentAction action(
            int merit,
            int entry,
            int decisionScore,
            InvestmentDimension quality,
            InvestmentDimension valuation,
            InvestmentDimension catalyst,
            InvestmentDimension sector,
            InvestmentDimension timing,
            RiskAssessment risk,
            DataQualityAssessment dataQuality,
            ScaleInEligibility scaleInEligibility,
            CompanyOpportunityType opportunity,
            CompanyInvestmentEvidence evidence
    ) {
        if (risk.level() == CompanyRiskLevel.CRITICAL
                || (evidence.timing().thesisState() == ThesisState.BREAK_RISK && merit < 65)) {
            return CompanyInvestmentAction.SELL;
        }
        if (evidence.timing().priceReversalStage() == PriceReversalStage.PRIOR_LOW_BROKEN
                && !evidence.timing().stopHuntReclaim()) {
            return merit >= 68 && quality.score() >= 62
                    ? CompanyInvestmentAction.REDUCE
                    : CompanyInvestmentAction.SELL;
        }
        if ((evidence.timing().priceReversalStage() == PriceReversalStage.STRUCTURAL_CRACK
                || evidence.timing().priceLocationState() == PriceLocationState.BREAKDOWN)
                && evidence.timing().priceRecoveryStage() != PriceRecoveryStage.STRUCTURE_BREAK
                && evidence.timing().priceRecoveryStage() != PriceRecoveryStage.RETEST_HELD) {
            return risk.level() == CompanyRiskLevel.HIGH
                    ? CompanyInvestmentAction.REDUCE
                    : CompanyInvestmentAction.HOLD;
        }
        if ((risk.level() == CompanyRiskLevel.HIGH
                && (evidence.timing().thesisState() == ThesisState.BREAK_RISK
                || quality.score() < 50
                || catalyst.score() < 40))
                || (valuation.score() < 32 && quality.score() < 65
                && evidence.scores().crowdingScore() != null
                && evidence.scores().crowdingScore() >= 65)) {
            return CompanyInvestmentAction.REDUCE;
        }
        if (risk.level() == CompanyRiskLevel.HIGH) return CompanyInvestmentAction.HOLD;
        // Entry timing is a present-tense decision. Old prices cannot authorize
        // a new buy, and financials older than a full reporting cycle cannot
        // prove that the business is still recoverable. Negative structure and
        // thesis-break exits above remain allowed to fail safe.
        if (evidence.timing().quoteAgeDays() == null
                || evidence.timing().quoteAgeDays() > MAX_CURRENT_QUOTE_AGE_DAYS) {
            return CompanyInvestmentAction.HOLD;
        }
        if (evidence.timing().fundamentalsAgeDays() == null
                || evidence.timing().fundamentalsAgeDays() > MAX_ACTIONABLE_FUNDAMENTALS_AGE_DAYS) {
            return CompanyInvestmentAction.HOLD;
        }
        // Calendar age alone is insufficient: a quarterly statement can look
        // recent while still lagging the latest known 10-Q/10-K. Such a row is
        // display-only until the normalized TTM catches up to that filing.
        if (evidence.fundamentalsReadiness() != FundamentalsReadiness.CURRENT) {
            return CompanyInvestmentAction.HOLD;
        }
        // A current quote alone is not a current entry signal. Missing or
        // unavailable price-structure/reversal evidence must not let strong
        // fundamentals and a legacy score synthesize a BUY.
        if (!hasCurrentEntryEvidence(evidence.timing())) return CompanyInvestmentAction.HOLD;
        if (dataQuality.level() == DataQualityLevel.LOW) return CompanyInvestmentAction.HOLD;
        if (opportunity == CompanyOpportunityType.VALUE_TRAP_RISK) return CompanyInvestmentAction.HOLD;
        if (scaleInEligibility.state() == ScaleInEligibilityState.INELIGIBLE
                || scaleInEligibility.state() == ScaleInEligibilityState.UNAVAILABLE) {
            return CompanyInvestmentAction.HOLD;
        }
        if (merit >= 77 && entry >= 70 && decisionScore >= 72
                && quality.score() >= 65 && catalyst.score() >= 64
                && sector.score() >= 58 && timing.score() >= 64
                && evidence.timing().priceLocationState() != PriceLocationState.UPPER_CHANNEL
                && evidence.timing().priceLocationState() != PriceLocationState.RESISTANCE_ZONE
                && evidence.timing().priceLocationState() != PriceLocationState.BREAKDOWN
                && risk.level() == CompanyRiskLevel.LOW
                && evidence.timing().fundamentalsAgeDays() <= MAX_STRONG_FUNDAMENTALS_AGE_DAYS
                && dataQuality.confidence() >= 58) {
            return CompanyInvestmentAction.STRONG_BUY;
        }
        if (merit >= 67 && entry >= 57 && decisionScore >= 61
                && quality.score() >= 56 && catalyst.score() >= 52
                && evidence.timing().priceLocationState() != PriceLocationState.BREAKDOWN
                && risk.score() < 55 && dataQuality.confidence() >= 45) {
            return CompanyInvestmentAction.BUY;
        }
        if (decisionScore < 42 || (quality.score() < 42 && catalyst.score() < 45)) {
            return CompanyInvestmentAction.REDUCE;
        }
        return CompanyInvestmentAction.HOLD;
    }

    private static boolean hasCurrentEntryEvidence(TimingEvidence timing) {
        var structureAvailable = timing.priceStructureScore() != null
                && timing.priceTrendState() != PriceTrendState.UNKNOWN
                && timing.priceTrendState() != PriceTrendState.UNAVAILABLE
                && timing.priceLocationState() != PriceLocationState.UNKNOWN
                && timing.priceLocationState() != PriceLocationState.UNAVAILABLE;
        var reversalAvailable = timing.reversalScore() != null
                && timing.reversalState() != ReversalState.UNKNOWN;
        var volumeAvailable = timing.volumeConfirmationScore() != null
                || (timing.technicalConfirmationScore() != null
                && timing.technicalFlowState() != TechnicalFlowState.UNKNOWN
                && timing.technicalFlowState() != TechnicalFlowState.UNAVAILABLE);
        return structureAvailable && reversalAvailable && volumeAvailable;
    }

    private static CompanyOpportunityType opportunityType(
            InvestmentDimension quality,
            InvestmentDimension valuation,
            InvestmentDimension catalyst,
            InvestmentDimension sector,
            InvestmentDimension timing,
            RiskAssessment risk,
            DataQualityAssessment dataQuality
    ) {
        if (dataQuality.level() == DataQualityLevel.LOW) {
            return CompanyOpportunityType.INSUFFICIENT_EVIDENCE;
        }
        if (valuation.score() >= 65 && catalyst.score() < 42 && risk.score() >= 48) {
            return CompanyOpportunityType.VALUE_TRAP_RISK;
        }
        if (quality.score() >= 70 && valuation.score() >= 58) {
            return CompanyOpportunityType.QUALITY_AT_REASONABLE_PRICE;
        }
        if (quality.score() >= 70 && valuation.score() < 45) {
            return CompanyOpportunityType.QUALITY_BUT_EXPENSIVE;
        }
        if (catalyst.score() >= 68 && sector.score() >= 60 && timing.score() < 70) {
            return CompanyOpportunityType.EARLY_CATALYST;
        }
        if (valuation.score() >= 72 && timing.score() >= 60 && quality.score() < 65) {
            return CompanyOpportunityType.DEEP_VALUE_TURNAROUND;
        }
        if (timing.score() >= 72 && (quality.score() < 58 || risk.level() != CompanyRiskLevel.LOW)) {
            return CompanyOpportunityType.MOMENTUM_WITH_RISK;
        }
        return CompanyOpportunityType.BALANCED_WATCH;
    }

    private static EntryStrategy entryStrategy(
            CompanyInvestmentAction action,
            CompanyInvestmentEvidence evidence,
            ScaleInEligibility scaleInEligibility
    ) {
        var timing = evidence.timing();
        var buyAction = action == CompanyInvestmentAction.STRONG_BUY
                || action == CompanyInvestmentAction.BUY;
        var initial = switch (action) {
            case STRONG_BUY -> 35;
            case BUY -> 25;
            case HOLD, REDUCE, SELL -> 0;
        };
        if (buyAction) {
            var favorableLocation = timing.priceLocationState() == PriceLocationState.SUPPORT_ZONE
                    || timing.priceLocationState() == PriceLocationState.LOWER_CHANNEL;
            if (timing.priceTrendState() == PriceTrendState.UPTREND
                    && favorableLocation
                    && timing.priceReversalStage() == PriceReversalStage.INTACT) {
                initial = action == CompanyInvestmentAction.STRONG_BUY ? 40 : 35;
            } else if (timing.priceTrendState() == PriceTrendState.DOWNTREND && favorableLocation) {
                initial = action == CompanyInvestmentAction.STRONG_BUY ? 25 : 20;
            } else if (timing.priceTrendState() == PriceTrendState.RANGE && favorableLocation) {
                initial = action == CompanyInvestmentAction.STRONG_BUY ? 30 : 25;
            } else if (timing.priceLocationState() == PriceLocationState.BREAKOUT
                    && timing.volumeBreakout()) {
                initial = action == CompanyInvestmentAction.STRONG_BUY ? 30 : 25;
            } else if (timing.priceLocationState() == PriceLocationState.UPPER_CHANNEL
                    || timing.priceLocationState() == PriceLocationState.RESISTANCE_ZONE) {
                initial = 10;
            }
        }
        if (scaleInEligibility.state() == ScaleInEligibilityState.CONDITIONAL) {
            initial = Math.min(initial, 20);
        } else if (scaleInEligibility.state() == ScaleInEligibilityState.INELIGIBLE
                || scaleInEligibility.state() == ScaleInEligibilityState.UNAVAILABLE) {
            initial = 0;
        }
        if (timing.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE_BROKEN) {
            initial = 0;
        } else if (timing.fibonacciSwingDirection() == FibonacciSwingDirection.DOWN_SWING) {
            initial = Math.min(initial, 10);
        } else if (timing.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE) {
            initial = Math.min(initial, 20);
        } else if (buyAction
                && timing.fibonacciSwingDirection() == FibonacciSwingDirection.UP_SWING
                && timing.fibonacciConfluenceScore() != null
                && timing.fibonacciConfluenceScore() >= 60
                && (timing.fibonacciZoneState() == FibonacciZoneState.MODERATE_RETRACEMENT
                || timing.fibonacciZoneState() == FibonacciZoneState.DEEP_RETRACEMENT)) {
            initial = Math.min(action == CompanyInvestmentAction.STRONG_BUY ? 40 : 35, initial + 5);
        }

        var priceZoneLabel = switch (timing.priceLocationState()) {
            case SUPPORT_ZONE -> "지지 구간";
            case LOWER_CHANNEL -> "채널 하단";
            case BREAKOUT -> "거래량 돌파";
            case MID_CHANNEL -> "채널 중단";
            case RESISTANCE_ZONE -> "저항 구간";
            case UPPER_CHANNEL -> "채널 상단";
            case BREAKDOWN -> "지지 이탈";
            case UNAVAILABLE, UNKNOWN -> "구조 확인 대기";
        };
        var fibonacciZoneLabel = switch (timing.fibonacciZoneState()) {
            case SHALLOW_RETRACEMENT -> "피보 얕은 조정";
            case MODERATE_RETRACEMENT -> "피보 0.5 전후";
            case DEEP_RETRACEMENT -> "피보 0.618 전후";
            case LAST_DEFENSE -> "피보 0.786 방어";
            case LAST_DEFENSE_BROKEN -> "피보 0.786 이탈";
            case EXTENSION, UNAVAILABLE, UNKNOWN -> "";
        };
        var zoneLabel = fibonacciZoneLabel.isBlank()
                ? priceZoneLabel : priceZoneLabel + " · " + fibonacciZoneLabel;
        var addConditions = new ArrayList<String>();
        if (buyAction) {
            addConditions.add("지지 구간 또는 높아진 저점을 재확인한 뒤 2차 진입");
            addConditions.add("직전 스윙 고점을 거래량과 함께 돌파하면 추가 진입");
            addConditions.add("돌파 가격이 새 지지로 바뀌고 실적·가이던스가 유지되면 잔여 진입");
            if (timing.fibonacciSwingDirection() == FibonacciSwingDirection.UP_SWING) {
                addConditions.add("0.382·0.5·0.618 중 주봉/지지 구간과 겹치는 가격에서 반등을 확인한 뒤만 추가");
            }
        } else {
            addConditions.add("낮아진 고점을 회복하고 직전 스윙 고점을 돌파할 때 재평가");
            addConditions.add("VWAP/OBV 분산이 멈추고 지지 구간을 회복할 때 재평가");
        }
        var reduceConditions = new ArrayList<String>();
        reduceConditions.add("낮아진 고점이 확인되는 추세 훼손 2단계면 비중 확대 중단/축소");
        reduceConditions.add("직전 저점이 깨지는 추세 훼손 3단계면 기존 상승 가설 청산");
        reduceConditions.add("지지 이탈 뒤 거래량 분산이 지속되면 물타기 금지");
        reduceConditions.add("주요 상승 파동의 0.786 방어선 이탈 시 추가 매수 중단");
        var summary = initial > 0
                ? zoneLabel + " 기준 목표 비중의 " + initial
                + "%만 1차 진입하고, 가격 구조 확인에 " + (100 - initial) + "%를 남깁니다. "
                + "전체 포트폴리오 상한은 " + scaleInEligibility.portfolioConcentrationCapPct() + "%입니다."
                : zoneLabel + "에서는 신규 진입보다 회복 가능성·가격 구조 재확인이 우선입니다.";
        return new EntryStrategy(
                initial,
                100 - initial,
                zoneLabel,
                summary,
                addConditions,
                reduceConditions
        );
    }

    private static List<ForwardOutlook> forwardOutlooks(
            CompanyInvestmentEvidence evidence,
            int merit,
            int entry,
            int risk,
            int dataConfidence
    ) {
        var validations = evidence.historicalValidations().stream()
                .sorted(Comparator.comparingInt(HistoricalValidation::forwardTradingDays))
                .toList();
        if (validations.size() >= 3) {
            return List.of(
                    validatedOutlook(ForwardHorizon.ONE_MONTH, validations.get(0), dataConfidence),
                    validatedOutlook(ForwardHorizon.THREE_MONTHS, validations.get(1), dataConfidence),
                    validatedOutlook(ForwardHorizon.SIX_MONTHS, validations.get(validations.size() - 1), dataConfidence)
            );
        }
        return List.of(
                heuristicOutlook(ForwardHorizon.ONE_MONTH, 21, merit, entry, risk, dataConfidence, 0.65),
                heuristicOutlook(ForwardHorizon.THREE_MONTHS, 63, merit, entry, risk, dataConfidence, 0.85),
                heuristicOutlook(ForwardHorizon.SIX_MONTHS, 126, merit, entry, risk, dataConfidence, 1.00)
        );
    }

    private static ForwardOutlook validatedOutlook(
            ForwardHorizon horizon,
            HistoricalValidation value,
            int dataConfidence
    ) {
        var sampleConfidence = clampScore(rounded(20 + Math.sqrt(value.signalCount()) * 9));
        var confidence = clampScore(rounded(sampleConfidence * 0.65 + dataConfidence * 0.35));
        return new ForwardOutlook(
                horizon,
                value.forwardTradingDays(),
                value.positiveHitRatePct(),
                value.targetReturnPct(),
                value.targetHitRatePct(),
                value.averageReturnPct(),
                value.averageMaxDrawdownPct(),
                value.signalCount(),
                confidence,
                OutlookMethod.WALK_FORWARD,
                value.signalCount() >= 20
                        ? "과거 동일 정책의 시점 기준 표본이며 미래 수익을 보장하지 않습니다."
                        : "과거 표본이 적어 적중률 오차가 큽니다."
        );
    }

    private static ForwardOutlook heuristicOutlook(
            ForwardHorizon horizon,
            int days,
            int merit,
            int entry,
            int risk,
            int dataConfidence,
            double horizonWeight
    ) {
        var likelihood = clamp(
                30 + entry * 0.34 + merit * 0.20 * horizonWeight - risk * 0.16,
                20,
                82
        );
        return new ForwardOutlook(
                horizon,
                days,
                round1(likelihood),
                null,
                null,
                null,
                null,
                0,
                Math.min(39, rounded(dataConfidence * 0.45)),
                OutlookMethod.SCORE_HEURISTIC,
                "통계 적중률이 아닌 현재 점수 기반 시나리오입니다. 워크포워드 표본 축적 전에는 확률로 해석하면 안 됩니다."
        );
    }

    private static List<String> positiveEvidence(InvestmentDimension... dimensions) {
        var result = new LinkedHashSet<String>();
        java.util.Arrays.stream(dimensions)
                .sorted(Comparator.comparingInt(InvestmentDimension::score).reversed())
                .forEach(value -> value.reasons().stream().limit(1).forEach(result::add));
        if (result.isEmpty()) result.add("현재는 강한 즉시 매수 근거보다 관찰 근거가 우세합니다.");
        return result.stream().limit(5).toList();
    }

    private static List<String> cautionEvidence(
            InvestmentDimension valuation,
            InvestmentDimension catalyst,
            InvestmentDimension sector,
            InvestmentDimension timing,
            RiskAssessment risk,
            DataQualityAssessment dataQuality,
            ScaleInEligibility scaleInEligibility
    ) {
        var result = new LinkedHashSet<String>();
        List.of(timing, catalyst, valuation, sector)
                .forEach(value -> value.cautions().stream().limit(2).forEach(result::add));
        if (risk.level() == CompanyRiskLevel.HIGH || risk.level() == CompanyRiskLevel.CRITICAL) {
            risk.reasons().stream().limit(2).forEach(result::add);
        }
        dataQuality.warnings().stream().limit(2).forEach(result::add);
        scaleInEligibility.blockers().stream().limit(2).forEach(result::add);
        if (result.isEmpty()) result.add("후속 실적과 거래량 확인 전까지 분할 진입 원칙을 유지해야 합니다.");
        return result.stream().limit(6).toList();
    }

    private static List<String> thesisBreaks(CompanyInvestmentEvidence evidence) {
        var result = new ArrayList<String>();
        result.add("가이던스 하향과 30일 EPS 추정치 급락이 동시에 나타나면 실적 가설을 재검토합니다.");
        result.add("ROIC·마진·현금흐름이 2개 분기 이상 동반 악화되면 기업 건강도 가설이 훼손됩니다.");
        result.add("섹터가 WEAKENING으로 전환되고 상대강도·자금 흐름이 함께 꺾이면 비중을 재평가합니다.");
        result.add("지지 구간 이탈 후 거래량 분산 우위가 지속되면 바닥·반전 가설을 폐기합니다.");
        if (evidence.fundamentals().bottleneckScore() != null) {
            result.add("수주잔고·리드타임·가격결정력 중 2개 이상 약화되면 병목 프리미엄을 제거합니다.");
        }
        return result;
    }

    private static String summary(
            CompanyInvestmentAction action,
            CompanyOpportunityType opportunity,
            InvestmentDimension quality,
            InvestmentDimension valuation,
            InvestmentDimension catalyst,
            InvestmentDimension sector,
            InvestmentDimension timing,
            RiskAssessment risk,
            ScaleInEligibility scaleInEligibility
    ) {
        var actionText = switch (action) {
            case STRONG_BUY -> "여러 판단축이 동시에 우호적이어서 분할 적극 매수 조건입니다.";
            case BUY -> "투자 매력은 충분하지만 한 번에 사기보다 1차 분할매수가 적절합니다.";
            case HOLD -> "좋은 요소가 있어도 가격·촉매·반전 중 일부 확인이 부족해 대기/관찰이 적절합니다.";
            case REDUCE -> "현재 기대수익보다 밸류·실적·추세 위험이 커 신규 진입을 피하고 비중 축소를 검토해야 합니다.";
            case SELL -> "핵심 위험 게이트가 작동해 투자 논리가 회복될 때까지 매도/회피가 우선입니다.";
        };
        var opportunityText = switch (opportunity) {
            case QUALITY_AT_REASONABLE_PRICE -> "좋은 회사와 합리적 가격의 조합입니다.";
            case EARLY_CATALYST -> "실적·섹터 촉매가 먼저 개선되는 초기 후보입니다.";
            case DEEP_VALUE_TURNAROUND -> "저평가와 반전이 결합된 턴어라운드 후보입니다.";
            case QUALITY_BUT_EXPENSIVE -> "기업은 건강하지만 가격이 앞선 상태입니다.";
            case VALUE_TRAP_RISK -> "싸 보이지만 실적 하향이 남은 가치함정 위험이 있습니다.";
            case MOMENTUM_WITH_RISK -> "가격 반전은 강하지만 기업·위험 근거가 덜 따라온 상태입니다.";
            case BALANCED_WATCH -> "장단점이 혼재된 선별 관찰 후보입니다.";
            case INSUFFICIENT_EVIDENCE -> "핵심 데이터 공백 때문에 확신 액션을 제한합니다.";
        };
        var weakest = List.of(quality, valuation, catalyst, sector, timing).stream()
                .min(Comparator.comparingInt(InvestmentDimension::score))
                .orElse(timing);
        var eligibilityText = switch (scaleInEligibility.state()) {
            case ELIGIBLE -> "분할매수 적격";
            case CONDITIONAL -> "조건부 분할매수";
            case INELIGIBLE -> "분할매수 부적격";
            case UNAVAILABLE -> "분할매수 판정 불가";
        };
        return actionText + " " + opportunityText + " " + eligibilityText
                + "(" + scaleInEligibility.score() + "/100)이며, 가장 약한 축은 "
                + weakest.label() + " " + weakest.score() + "/100이며 위험은 "
                + risk.level().name() + "입니다.";
    }

    private static InvestmentDimension dimension(
            String key,
            String label,
            WeightedScore score,
            List<String> reasons,
            List<String> cautions
    ) {
        var state = state(score.score());
        var subject = topicSubject(label);
        var summary = switch (state) {
            case STRONG -> subject + " 강하게 우호적입니다.";
            case POSITIVE -> subject + " 우호적인 편입니다.";
            case NEUTRAL -> subject + " 추가 확인이 필요합니다.";
            case WEAK -> subject + " 현재 투자 판단의 약점입니다.";
        };
        return new InvestmentDimension(
                key,
                label,
                score.score(),
                score.confidence(),
                state,
                summary,
                reasons.stream().distinct().limit(4).toList(),
                cautions.stream().distinct().limit(4).toList()
        );
    }

    private static DimensionState state(int score) {
        return score >= 75 ? DimensionState.STRONG
                : score >= 62 ? DimensionState.POSITIVE
                : score >= 45 ? DimensionState.NEUTRAL
                : DimensionState.WEAK;
    }

    private static String topicSubject(String label) {
        return switch (label) {
            case "기업 건강도", "가격 매력도", "기대 변화·촉매" -> label + "는";
            case "섹터 순풍", "진입 타이밍" -> label + "은";
            default -> label;
        };
    }

    private static void add(
            List<WeightedInput> inputs,
            Integer value,
            double weight,
            String label
    ) {
        if (value != null) inputs.add(new WeightedInput(clampScore(value), weight, label));
    }

    private static WeightedScore weighted(List<WeightedInput> inputs) {
        if (inputs.isEmpty()) return new WeightedScore(50, 0);
        var availableWeight = inputs.stream().mapToDouble(WeightedInput::weight).sum();
        var weightedValue = inputs.stream().mapToDouble(value -> value.score() * value.weight()).sum();
        var score = clampScore(rounded(weightedValue / availableWeight));
        var confidence = clampScore(rounded(Math.min(1, availableWeight / 100.0) * 100));
        return new WeightedScore(score, confidence);
    }

    private static Integer ratioScore(Double value, double veryWeak, double weak, double fair, double good, double strong) {
        if (value == null) return null;
        if (value <= veryWeak) return 12;
        if (value <= weak) return 28;
        if (value <= fair) return 48;
        if (value <= good) return 68;
        if (value <= strong) return 84;
        return 94;
    }

    private static Integer growthScore(Double value) {
        if (value == null) return null;
        if (value < -15) return 12;
        if (value < 0) return 30;
        if (value < 8) return 52;
        if (value < 18) return 68;
        if (value < 35) return 82;
        return 92;
    }

    private static Integer inverseScore(Double value, double excellent, double good, double caution, double poor) {
        if (value == null) return null;
        if (value <= excellent) return 88;
        if (value <= good) return 72;
        if (value <= caution) return 50;
        if (value <= poor) return 30;
        return 12;
    }

    private static Integer accrualScore(Double value) {
        if (value == null) return null;
        if (value <= 0) return 84;
        if (value <= 3) return 70;
        if (value <= 8) return 48;
        return 22;
    }

    private static Integer strengthScore(EvidenceStrength value) {
        return switch (value) {
            case STRONG -> 85;
            case MODERATE -> 62;
            case WEAK -> 35;
            case UNKNOWN -> null;
        };
    }

    private static Integer evFcfScore(Double value) {
        if (value == null || value <= 0) return null;
        if (value <= 15) return 88;
        if (value <= 22) return 75;
        if (value <= 32) return 60;
        if (value <= 45) return 42;
        if (value <= 65) return 25;
        return 12;
    }

    private static Integer evSalesScore(Double value) {
        if (value == null || value < 0) return null;
        if (value <= 2) return 88;
        if (value <= 4) return 74;
        if (value <= 7) return 58;
        if (value <= 12) return 38;
        return 18;
    }

    private static Integer peerPremiumScore(Double value) {
        if (value == null) return null;
        if (value <= -25) return 90;
        if (value <= -10) return 78;
        if (value <= 10) return 62;
        if (value <= 30) return 44;
        if (value <= 60) return 28;
        return 14;
    }

    private static Integer rangeScore(ValuationRangePosition value) {
        return switch (value) {
            case UNDERVALUED -> 85;
            case FAIR -> 58;
            case OVERVALUED -> 28;
            case UNKNOWN -> null;
        };
    }

    private static Integer relativeScore(ValuationRelativePosition value) {
        return switch (value) {
            case DISCOUNT -> 82;
            case NEUTRAL -> 58;
            case PREMIUM -> 32;
            case UNKNOWN -> null;
        };
    }

    private static Integer revisionScore(Double value) {
        if (value == null) return null;
        return clampScore(rounded(50 + clamp(value, -12, 12) * 3.4));
    }

    /** Lower analyst score is generally the more bullish convention in the source feed. */
    private static Integer analystRevisionScore(Double value) {
        if (value == null) return null;
        return clampScore(rounded(50 - clamp(value, -0.5, 0.5) * 70));
    }

    private static Integer guidanceScore(GuidanceDirection value) {
        return switch (value) {
            case RAISED -> 88;
            case AFFIRMED -> 66;
            case MIXED -> 48;
            case LOWERED -> 20;
            case UNKNOWN -> null;
        };
    }

    private static Integer trendScore(Double value) {
        if (value == null) return null;
        return clampScore(rounded(50 + clamp(value, -10, 10) * 4));
    }

    private static Integer narrativeTrendScore(NarrativeTrend trend, NarrativeStage stage) {
        if (trend == NarrativeTrend.UNKNOWN && stage == NarrativeStage.UNKNOWN) return null;
        var score = switch (trend) {
            case HEATING -> 72;
            case STABLE -> 58;
            case COOLING -> 38;
            case UNKNOWN -> 50;
        };
        if (stage == NarrativeStage.EARLY) score += 8;
        if (stage == NarrativeStage.OVERHEATED) score -= 22;
        return clampScore(score);
    }

    private static Integer boundedUpsideScore(Double value) {
        if (value == null) return null;
        return clampScore(rounded(48 + clamp(value, -20, 35) * 0.7));
    }

    private static Integer fibonacciTimingScore(CompanyInvestmentEvidence.TimingEvidence value) {
        if (value.fibonacciConfluenceScore() == null
                || value.fibonacciSwingDirection() == FibonacciSwingDirection.UNKNOWN
                || value.fibonacciSwingDirection() == FibonacciSwingDirection.UNAVAILABLE) {
            return null;
        }
        if (value.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE_BROKEN) return 10;
        if (value.fibonacciSwingDirection() == FibonacciSwingDirection.DOWN_SWING) {
            return Math.min(45, value.fibonacciConfluenceScore());
        }
        var base = value.fibonacciConfluenceScore();
        if (value.fibonacciZoneState() == FibonacciZoneState.MODERATE_RETRACEMENT
                || value.fibonacciZoneState() == FibonacciZoneState.DEEP_RETRACEMENT) {
            base += 10;
        } else if (value.fibonacciZoneState() == FibonacciZoneState.LAST_DEFENSE) {
            base -= 10;
        }
        return clampScore(base);
    }

    private static Integer inverseDirectScore(Integer value) {
        return value == null ? null : 100 - value;
    }

    private static Integer marketBiasScore(MarketBias value) {
        return switch (value) {
            case STRONG_BUY -> 88;
            case BUY -> 74;
            case HOLD -> 54;
            case REDUCE -> 34;
            case SELL -> 18;
            case UNKNOWN -> null;
        };
    }

    private static String leadershipSuffix(String value) {
        return switch (value) {
            case "1_3m", "1~3개월" -> "이며 예상 창은 1~3개월";
            case "3_6m", "3~6개월" -> "이며 예상 창은 3~6개월";
            case "6m_plus", "6개월+" -> "이지만 6개월 이상이 필요할 수 있음";
            default -> "";
        };
    }

    private static void addByThreshold(
            List<String> reasons,
            List<String> cautions,
            Integer score,
            String positive,
            String negative
    ) {
        if (score == null) return;
        if (score >= 72) reasons.add(positive);
        if (score < 42) cautions.add(negative);
    }

    private static int rounded(double value) {
        return (int) Math.round(value);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record WeightedInput(int score, double weight, String label) {
    }

    private record WeightedScore(int score, int confidence) {
    }
}
