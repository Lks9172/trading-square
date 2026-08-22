package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.BooleanValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyInvestmentAction;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.CompanyOpportunityType;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.DataQualityAssessment;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.EntryStrategy;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.ForwardHorizon;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.ForwardOutlook;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.RiskAssessment;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecision.ScaleInEligibility;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecisionPolicy;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.BottomConviction;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.CatalystEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.EvidenceStrength;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FibonacciSwingDirection;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FibonacciZoneState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FundamentalEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.FundamentalsReadiness;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.GuidanceDirection;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.HistoricalValidation;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.MarketBias;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.MovingAverageState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.NarrativeStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.NarrativeTrend;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceLocationState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceRecoveryStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceReversalStage;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.PriceTrendState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ReversalState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.RiskBand;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ScoreEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.SectorEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.SectorRotationState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.SectorStance;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.TechnicalFlowState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ThesisState;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.TimingEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ValuationEvidence;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ValuationRangePosition;
import io.macrosquare.company.domain.investment.CompanyInvestmentEvidence.ValuationRelativePosition;
import io.macrosquare.company.domain.investment.InvestmentDimension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Application anti-corruption layer that translates the evolving company read
 * projection into stable domain evidence and projects the resulting decision
 * back without exposing generic document values to the domain.
 */
final class CompanyInvestmentDecisionComposer {

    private static final int EXPECTED_EVIDENCE_COUNT = 96;

    private final CompanyInvestmentDecisionPolicy policy;

    CompanyInvestmentDecisionComposer(CompanyInvestmentDecisionPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    Research compose(Research source, LocalDate today) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(today, "today");
        var evidence = evidence(source, today);
        var decision = policy.evaluate(evidence);
        var verdicts = verdicts(source, decision);
        var positionSizing = positionSizing(source.positionSizing(), decision);
        var executionBridge = executionBridge(source.executionBridge(), decision.action());
        var correctionAssessment = correctionAssessment(source, decision);
        var thesisMonitor = thesisMonitor(source, decision);
        return new Research(
                source.profile(), source.quote(), source.financials(), source.score(), source.buyScore(),
                source.filings(), source.irMaterials(), source.highlights(), source.peerGroup(),
                source.bottleneck(), source.narrative(), source.capitalFlow(), source.cashFlowQuality(),
                source.multipleInsight(), source.guidanceInsight(), source.timeframeView(),
                correctionAssessment, thesisMonitor, source.reversalConfirmation(),
                source.sectorContext(), verdicts, source.bottomSignal(), positionSizing,
                executionBridge, source.peers()
        );
    }

    /**
     * Compatibility projection for the existing correction panel. The
     * authoritative calculation is the typed domain decision above; legacy
     * correction scores and prose are never fed back into that decision.
     */
    private static StructuredValue correctionAssessment(
            Research source,
            CompanyInvestmentDecision decision
    ) {
        var correctionScore = clampScore(
                (100 - decision.risk().score()) * 0.35
                        + decision.quality().score() * 0.25
                        + decision.catalyst().score() * 0.25
                        + decision.timing().score() * 0.15);
        var trendBreakRiskScore = clampScore(
                decision.risk().score() * 0.50
                        + (100 - decision.catalyst().score()) * 0.30
                        + (100 - decision.quality().score()) * 0.20);
        var verdict = correctionScore >= 65 && trendBreakRiskScore < 45
                ? "조정 우세"
                : trendBreakRiskScore >= 60 ? "추세전환 경계" : "혼합";
        var actionBias = "조정 우세".equals(verdict) ? "눌림 매수 검토"
                : "추세전환 경계".equals(verdict) ? "방어 우선" : "확인 후 접근";
        var summary = "조정 우세".equals(verdict)
                ? "기업 건강도·실적 기대·가격 구조를 함께 보면 단순 조정 가능성이 상대적으로 우세합니다. 확정 판단은 아닙니다."
                : "추세전환 경계".equals(verdict)
                ? "실적 기대 또는 기업 건강도 훼손 위험이 커 추가 매수보다 가설 재검증이 우선입니다."
                : "조정과 추세 훼손 근거가 섞여 있어 후속 실적·가이던스·가격 구조 확인이 필요합니다.";

        var reasons = new LinkedHashSet<String>();
        reasons.addAll(decision.quality().reasons());
        reasons.addAll(decision.catalyst().reasons());
        reasons.addAll(decision.timing().reasons());
        var revision30d = decimal(object(source.financials()), "estimateRevision30d");
        if (revision30d != null && Math.abs(revision30d) < 3) {
            reasons.add("30일 EPS 추정치 " + signedPercent(revision30d)
                    + "로 현재는 급격한 기대 훼손 범위가 아닙니다.");
        }
        if (reasons.isEmpty()) reasons.add("현재 우호 근거가 제한적이어서 중립으로 해석합니다.");

        var risks = new LinkedHashSet<String>();
        risks.addAll(decision.quality().cautions());
        risks.addAll(decision.catalyst().cautions());
        risks.addAll(decision.timing().cautions());
        risks.addAll(decision.risk().reasons());
        if (risks.isEmpty()) risks.add("뚜렷한 훼손 신호는 적지만 후속 확인 전 확정 신호로 보지 않습니다.");

        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("correctionScore", number(correctionScore));
        result.put("trendBreakRiskScore", number(trendBreakRiskScore));
        result.put("verdict", text(verdict));
        result.put("actionBias", text(actionBias));
        result.put("summary", text(summary));
        result.put("reasons", texts(reasons.stream().limit(4).toList()));
        result.put("risks", texts(risks.stream().limit(4).toList()));
        result.put("authoritativeSource", text(CompanyInvestmentDecisionPolicy.VERSION));
        result.put("methodology", text("도메인 투자판단의 기업 건강도·EPS 촉매·타이밍·위험을 재투영한 상대 점수이며 확률이 아닙니다."));
        return new ObjectValue(result);
    }

    private static StructuredValue thesisMonitor(
            Research source,
            CompanyInvestmentDecision decision
    ) {
        var status = decision.risk().score() >= 65 || decision.catalyst().score() < 40
                ? "훼손 경계"
                : decision.quality().score() < 55 || decision.catalyst().score() < 55
                || decision.sector().score() < 45 ? "일부 약화" : "유지";
        var summary = "유지".equals(status)
                ? "기업 건강도와 실적 기대 가설이 현재 데이터에서 유지됩니다. 가격 확인과 분할 기준은 별도입니다."
                : "일부 약화".equals(status)
                ? "핵심 논리는 남아 있지만 기업·EPS 기대·섹터 중 적어도 한 축이 약해졌습니다."
                : "매수 가설 훼손 위험이 높아 추가 진입보다 실적과 가격 구조 재확인이 우선입니다.";
        var reasons = new LinkedHashSet<String>();
        reasons.addAll(decision.quality().reasons());
        reasons.addAll(decision.catalyst().reasons());
        reasons.addAll(decision.sector().reasons());
        var financials = object(source.financials());
        var revision30d = decimal(financials, "estimateRevision30d");
        if (revision30d != null && Math.abs(revision30d) < 3) {
            reasons.add("30일 EPS 추정치 " + signedPercent(revision30d) + "로 큰 방향 변화는 아직 없습니다.");
        }
        if (reasons.isEmpty()) reasons.add("가설을 지지할 직접 근거가 제한적이어서 보수적 확인이 필요합니다.");

        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("status", text(status));
        result.put("summary", text(summary));
        result.put("reasons", texts(reasons.stream().limit(4).toList()));
        result.put("breakConditions", texts(decision.thesisBreaks()));
        result.put("authoritativeSource", text(CompanyInvestmentDecisionPolicy.VERSION));
        return new ObjectValue(result);
    }

    private static CompanyInvestmentEvidence evidence(Research source, LocalDate today) {
        var profile = object(source.profile());
        var financials = object(source.financials());
        var score = object(source.score());
        var buy = object(source.buyScore());
        var cashFlow = object(source.cashFlowQuality());
        var multiple = object(source.multipleInsight());
        var bottleneck = object(source.bottleneck());
        var narrative = object(source.narrative());
        var guidance = object(source.guidanceInsight());
        var sector = object(source.sectorContext());
        var bottom = object(source.bottomSignal());
        var confirmed = nestedObject(bottom, "confirmedBottom");
        var technical = nestedObject(bottom, "technicalConfirmation");
        var priceStructure = nestedObject(bottom, "priceStructure");
        var fibonacci = nestedObject(priceStructure, "fibonacci");
        var reversal = object(source.reversalConfirmation());
        var timeframe = object(source.timeframeView());
        var execution = object(source.executionBridge());

        var scoreEvidence = new ScoreEvidence(
                integer(score, "totalScore"),
                nestedInteger(score, "growth", "value"),
                nestedInteger(score, "quality", "value"),
                nestedInteger(score, "valuation", "value"),
                nestedInteger(score, "balanceSheet", "value"),
                integer(buy, "appealScore"),
                integer(buy, "crowdingScore"),
                integer(buy, "buyScore")
        );
        var fundamentalEvidence = new FundamentalEvidence(
                decimal(financials, "revenueGrowthYoY"),
                decimal(financials, "operatingMargin"),
                decimal(financials, "operatingMarginTrend"),
                decimal(financials, "freeCashFlowMargin"),
                decimal(financials, "roic"),
                decimal(financials, "roe"),
                decimal(financials, "netDebtToRevenue"),
                decimal(financials, "currentRatio"),
                decimal(financials, "shareDilutionYoY"),
                decimal(financials, "shareDilution3yCagr"),
                decimal(financials, "stockCompToRevenue"),
                decimal(financials, "accrualRatio"),
                integer(cashFlow, "cashConversionScore"),
                integer(cashFlow, "earningsQualityScore"),
                integer(bottleneck, "score"),
                integer(bottleneck, "switchingCost"),
                evidenceStrength(text(bottleneck, "pricingPower")),
                evidenceStrength(text(bottleneck, "leadTimeSignal")),
                evidenceStrength(text(bottleneck, "backlogSignal"))
        );
        var valuationEvidence = new ValuationEvidence(
                decimal(financials, "evToSales"),
                decimal(financials, "evToFcf"),
                decimal(multiple, "premiumPctVsPeer"),
                decimal(multiple, "premiumPctVsPeerMedian"),
                valuationRange(text(multiple, "valuationVsInternalRange")),
                relativePosition(text(multiple, "valuationVsPeer")),
                riskBand(text(multiple, "multipleCompressionRisk")),
                riskBand(text(multiple, "rateSensitivity")),
                riskBand(text(multiple, "narrativePremium"))
        );
        var guidanceDirection = guidanceDirection(guidance, source.filings());
        var catalystEvidence = new CatalystEvidence(
                decimal(financials, "estimateUpsidePct"),
                decimal(financials, "estimateRevision7d"),
                decimal(financials, "estimateRevision30d"),
                decimal(financials, "estimateRevision90d"),
                decimal(financials, "analystScoreRevision7d"),
                decimal(financials, "analystScoreRevision30d"),
                decimal(financials, "analystScoreRevision90d"),
                guidanceDirection,
                integer(bottom, "earningsBottomScore"),
                narrativeStage(text(narrative, "stage")),
                narrativeTrend(text(narrative, "trend"))
        );
        var sectorEvidence = new SectorEvidence(
                integer(sector, "buyScore"),
                integer(sector, "qualityScore"),
                integer(sector, "appealScore"),
                integer(sector, "crowdingScore"),
                integer(sector, "valuationScore"),
                integer(sector, "earningsRevisionScore"),
                integer(sector, "rotationScore"),
                integer(sector, "macroFitScore"),
                integer(sector, "relativeStrengthScore"),
                integer(sector, "fundamentalScore"),
                integer(sector, "flowScore"),
                sectorStance(text(sector, "stance")),
                sectorRotationState(text(sector, "rotationState")),
                marketBias(text(execution, "action")),
                defaultText(text(sector, "expectedLeadershipWindow"))
        );
        var quoteAge = ageDays(text(object(source.quote()), "date"), today);
        var fundamentalsAge = ageDays(text(financials, "asOf"), today);
        var timingEvidence = new TimingEvidence(
                integer(bottom, "score"),
                integer(bottom, "priceBottomScore"),
                integer(bottom, "volumeConfirmationScore"),
                integer(bottom, "failureRiskScore"),
                integer(confirmed, "score"),
                bottomConviction(text(confirmed, "state")),
                integer(reversal, "score"),
                reversalState(text(reversal, "status")),
                integer(technical, "score"),
                technicalState(text(technical, "state")),
                integer(priceStructure, "score"),
                priceTrendState(text(priceStructure, "trendState")),
                priceReversalStage(text(priceStructure, "bearishReversalStage")),
                priceRecoveryStage(text(priceStructure, "recoveryStage")),
                priceLocationState(text(priceStructure, "priceLocation")),
                movingAverageState(text(priceStructure, "movingAverageState")),
                decimal(priceStructure, "rsi14"),
                bool(priceStructure, "oversoldConfluence"),
                bool(priceStructure, "stopHuntReclaim"),
                bool(priceStructure, "volumeBreakout"),
                fibonacciSwingDirection(text(fibonacci, "swingDirection")),
                fibonacciZoneState(text(fibonacci, "zoneState")),
                decimal(fibonacci, "nearestRatio"),
                integer(fibonacci, "confluenceScore"),
                bool(fibonacci, "weeklyConfluence"),
                bool(fibonacci, "supportResistanceConfluence"),
                null,
                null,
                nestedInteger(timeframe, "shortTerm", "score"),
                nestedInteger(timeframe, "swingTerm", "score"),
                nestedInteger(timeframe, "longTerm", "score"),
                ThesisState.UNKNOWN,
                quoteAge,
                fundamentalsAge
        );
        var validations = historicalValidations(timeframe);
        var warnings = warnings(
                financials, valuationEvidence, sectorEvidence, guidanceDirection,
                timingEvidence, quoteAge, fundamentalsAge);
        var available = availableEvidenceCount(
                scoreEvidence, fundamentalEvidence, valuationEvidence,
                catalystEvidence, sectorEvidence, timingEvidence);

        return new CompanyInvestmentEvidence(
                defaultText(text(profile, "ticker"), "UNKNOWN"),
                scoreEvidence,
                fundamentalEvidence,
                valuationEvidence,
                catalystEvidence,
                sectorEvidence,
                timingEvidence,
                fundamentalsReadiness(text(financials, "fundamentalsStatus")),
                validations,
                Math.min(available, EXPECTED_EVIDENCE_COUNT),
                EXPECTED_EVIDENCE_COUNT,
                warnings
        );
    }

    private static StructuredValue verdicts(Research source, CompanyInvestmentDecision decision) {
        var result = fields(object(source.verdicts()));
        result.put("investmentDecision", investmentDecision(source, decision));
        patchLegacyVerdict(
                result, "businessQuality", decision.quality().score(),
                decision.quality().summary());
        patchLegacyVerdict(
                result, "valuation", decision.valuation().score(),
                decision.valuation().summary());
        patchLegacyVerdict(
                result, "timing", decision.timing().score(),
                decision.timing().summary());
        patchLegacyVerdict(
                result, "finalAction", decision.decisionScore(),
                decision.summary());
        var oneLiners = fields(nestedObject(result, "oneLiners"));
        oneLiners.put("business", text(decision.quality().summary()));
        oneLiners.put("valuation", text(decision.valuation().summary()));
        oneLiners.put("timing", text(decision.timing().summary()));
        oneLiners.put("action", text(actionLabel(decision.action()) + " — " + decision.summary()));
        result.put("oneLiners", new ObjectValue(oneLiners));
        return new ObjectValue(result);
    }

    private static ObjectValue investmentDecision(
            Research source,
            CompanyInvestmentDecision decision
    ) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("version", text(decision.version()));
        result.put("action", text(action(decision.action())));
        result.put("actionLabel", text(actionLabel(decision.action())));
        result.put("decisionScore", number(decision.decisionScore()));
        result.put("investmentMeritScore", number(decision.investmentMeritScore()));
        result.put("entryReadinessScore", number(decision.entryReadinessScore()));
        result.put("opportunityType", text(decision.opportunityType().name()));
        result.put("opportunityLabel", text(opportunityLabel(decision.opportunityType())));
        result.put("summary", text(decision.summary()));

        var dimensions = new LinkedHashMap<String, StructuredValue>();
        dimensions.put("quality", dimension(decision.quality()));
        dimensions.put("valuation", dimension(decision.valuation()));
        dimensions.put("catalyst", dimension(decision.catalyst()));
        dimensions.put("sector", dimension(decision.sector()));
        dimensions.put("timing", dimension(decision.timing()));
        result.put("dimensions", new ObjectValue(dimensions));
        result.put("risk", risk(decision.risk()));
        result.put("dataQuality", dataQuality(decision.dataQuality()));
        result.put("scaleInEligibility", scaleInEligibility(decision.scaleInEligibility()));
        result.put("entryStrategy", entryStrategy(decision.entryStrategy()));
        result.put("forwardOutlooks", new ArrayValue(
                decision.forwardOutlooks().stream()
                        .map(CompanyInvestmentDecisionComposer::forwardOutlook)
                        .map(StructuredValue.class::cast)
                        .toList()
        ));
        result.put("whyNow", texts(decision.whyNow()));
        result.put("whyWait", texts(decision.whyWait()));
        result.put("thesisBreaks", texts(decision.thesisBreaks()));
        result.put("methodology", text(decision.methodology()));
        result.put("probabilityNotice", text(
                "WALK_FORWARD만 과거 시점 기준 적중률입니다. SCORE_HEURISTIC은 확률이 아닌 현재 조건의 상대적 가능성입니다."));
        return new ObjectValue(result);
    }

    private static StructuredValue positionSizing(
            StructuredValue source,
            CompanyInvestmentDecision decision
    ) {
        var prior = object(source);
        var result = fields(prior);
        if (!prior.fields().isEmpty()) {
            result.put("legacyPolicySnapshot", legacyPolicySnapshot(prior));
        }
        var initial = decision.entryStrategy().initialEntryPctOfTarget();
        result.put("action", text(action(decision.action())));
        result.put("initialEntryPctOfTarget", number(initial));
        result.put("reservePctOfTarget", number(decision.entryStrategy().reservePctOfTarget()));
        result.put("zoneLabel", text(decision.entryStrategy().zoneLabel()));
        result.put("summary", text(decision.entryStrategy().summary()));
        result.put("scaleInEligibility", text(decision.scaleInEligibility().state().name()));
        result.put("scaleInEligibilityScore", number(decision.scaleInEligibility().score()));
        result.put("portfolioConcentrationCapPct", number(
                decision.scaleInEligibility().portfolioConcentrationCapPct()));
        result.put("addOnPlan", texts(decision.entryStrategy().addConditions()));
        result.put("reducePlan", texts(decision.entryStrategy().reduceConditions()));
        var reasons = new LinkedHashSet<String>();
        reasons.add("투자 매력도 " + decision.investmentMeritScore() + "/100");
        reasons.add("진입 적합도 " + decision.entryReadinessScore() + "/100");
        reasons.add("위험 " + decision.risk().level().name() + " " + decision.risk().score() + "/100");
        reasons.add("분할매수 적격성 " + decision.scaleInEligibility().state().name()
                + " " + decision.scaleInEligibility().score() + "/100");
        reasons.add("데이터 신뢰도 " + decision.dataQuality().confidence() + "/100");
        result.put("reasons", texts(reasons.stream().limit(8).toList()));
        if (!decision.thesisBreaks().isEmpty()) {
            result.put("stopScenario", text(decision.thesisBreaks().getFirst()));
        }
        return new ObjectValue(result);
    }

    private static ObjectValue entryStrategy(EntryStrategy value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("initialEntryPctOfTarget", number(value.initialEntryPctOfTarget()));
        result.put("reservePctOfTarget", number(value.reservePctOfTarget()));
        result.put("zoneLabel", text(value.zoneLabel()));
        result.put("summary", text(value.summary()));
        result.put("addConditions", texts(value.addConditions()));
        result.put("reduceConditions", texts(value.reduceConditions()));
        return new ObjectValue(result);
    }

    private static ObjectValue scaleInEligibility(ScaleInEligibility value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("score", number(value.score()));
        result.put("state", text(value.state().name()));
        result.put("stateLabel", text(switch (value.state()) {
            case ELIGIBLE -> "분할매수 적격";
            case CONDITIONAL -> "조건부";
            case INELIGIBLE -> "부적격";
            case UNAVAILABLE -> "판정 불가";
        }));
        result.put("portfolioConcentrationCapPct", number(value.portfolioConcentrationCapPct()));
        result.put("summary", text(value.summary()));
        result.put("reasons", texts(value.reasons()));
        result.put("blockers", texts(value.blockers()));
        return new ObjectValue(result);
    }

    private static ObjectValue legacyPolicySnapshot(ObjectValue prior) {
        var snapshot = new LinkedHashMap<String, StructuredValue>();
        for (var key : List.of(
                "action", "targetPositionPct", "initialEntryPctOfTarget",
                "reservePctOfTarget", "summary", "addOnPlan", "reasons")) {
            var value = prior.fields().get(key);
            if (value != null) snapshot.put(key, value);
        }
        return new ObjectValue(snapshot);
    }

    private static StructuredValue executionBridge(
            StructuredValue source,
            CompanyInvestmentAction companyAction
    ) {
        var result = fields(object(source));
        var companyActionText = action(companyAction);
        result.put("companyAction", text(companyActionText));
        result.put("companyActionLabel", text(actionLabel(companyAction)));
        var assetAction = text(result, "action");
        if (assetAction != null) {
            var difference = Math.abs(actionRank(planAction(assetAction)) - actionRank(companyActionText));
            result.put("alignment", text(difference == 0 ? "aligned" : difference == 1 ? "mixed" : "conflicted"));
        }
        return new ObjectValue(result);
    }

    private static ObjectValue dimension(InvestmentDimension value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("key", text(value.key()));
        result.put("label", text(value.label()));
        result.put("score", number(value.score()));
        result.put("confidence", number(value.confidence()));
        result.put("state", text(value.state().name()));
        result.put("stateLabel", text(switch (value.state()) {
            case STRONG -> "강함";
            case POSITIVE -> "우호";
            case NEUTRAL -> "중립";
            case WEAK -> "약함";
        }));
        result.put("summary", text(value.summary()));
        result.put("reasons", texts(value.reasons()));
        result.put("cautions", texts(value.cautions()));
        return new ObjectValue(result);
    }

    private static ObjectValue risk(RiskAssessment value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("score", number(value.score()));
        result.put("level", text(value.level().name()));
        result.put("levelLabel", text(switch (value.level()) {
            case LOW -> "낮음";
            case MODERATE -> "보통";
            case HIGH -> "높음";
            case CRITICAL -> "매우 높음";
        }));
        result.put("summary", text(value.summary()));
        result.put("reasons", texts(value.reasons()));
        return new ObjectValue(result);
    }

    private static ObjectValue dataQuality(DataQualityAssessment value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("coveragePct", number(value.coveragePct()));
        result.put("confidence", number(value.confidence()));
        result.put("level", text(value.level().name()));
        result.put("levelLabel", text(switch (value.level()) {
            case HIGH -> "높음";
            case MODERATE -> "보통";
            case LOW -> "낮음";
        }));
        result.put("summary", text(value.summary()));
        result.put("warnings", texts(value.warnings()));
        return new ObjectValue(result);
    }

    private static ObjectValue forwardOutlook(ForwardOutlook value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("horizon", text(value.horizon().name()));
        result.put("horizonLabel", text(horizonLabel(value.horizon())));
        result.put("forwardTradingDays", number(value.forwardTradingDays()));
        result.put("positiveReturnLikelihoodPct", number(value.positiveReturnLikelihoodPct()));
        result.put("targetReturnPct", number(value.targetReturnPct()));
        result.put("targetHitLikelihoodPct", number(value.targetHitLikelihoodPct()));
        result.put("averageReturnPct", number(value.averageReturnPct()));
        result.put("averageMaxDrawdownPct", number(value.averageMaxDrawdownPct()));
        result.put("sampleCount", number(value.sampleCount()));
        result.put("confidence", number(value.confidence()));
        result.put("method", text(value.method().name()));
        result.put("methodLabel", text(value.method() == CompanyInvestmentDecision.OutlookMethod.WALK_FORWARD
                ? "워크포워드" : "점수 시나리오"));
        result.put("caution", text(value.caution()));
        return new ObjectValue(result);
    }

    private static void patchLegacyVerdict(
            LinkedHashMap<String, StructuredValue> parent,
            String key,
            int score,
            String summary
    ) {
        var result = fields(nestedObject(parent, key));
        result.put("score", number(score));
        result.put("label", text(score >= 72 ? "우호" : score >= 60 ? "양호" : score >= 45 ? "중립" : "주의"));
        result.put("summary", text(summary));
        parent.put(key, new ObjectValue(result));
    }

    private static List<HistoricalValidation> historicalValidations(ObjectValue timeframe) {
        var validation = nestedObject(timeframe, "validation");
        var horizons = array(validation, "horizons");
        if (horizons == null) return List.of();
        var result = new ArrayList<HistoricalValidation>();
        for (var value : horizons.values()) {
            if (!(value instanceof ObjectValue item)) continue;
            var days = integer(item, "forwardTradingDays");
            var signals = integer(item, "signalCount");
            if (days == null || days < 1 || signals == null || signals < 0) continue;
            result.add(new HistoricalValidation(
                    defaultText(text(item, "horizon")),
                    days,
                    signals,
                    decimal(item, "positiveHitRatePct"),
                    decimal(item, "targetReturnPct"),
                    decimal(item, "targetHitRatePct"),
                    decimal(item, "averageReturnPct"),
                    decimal(item, "averageMaxDrawdownPct")
            ));
        }
        return List.copyOf(result);
    }

    private static List<String> warnings(
            ObjectValue financials,
            ValuationEvidence valuation,
            SectorEvidence sector,
            GuidanceDirection guidance,
            TimingEvidence timing,
            Integer quoteAge,
            Integer fundamentalsAge
    ) {
        var result = new LinkedHashSet<String>();
        if (valuation.evToSales() == null && valuation.evToFreeCashFlow() == null
                && valuation.internalRange() == ValuationRangePosition.UNKNOWN) {
            result.add("핵심 밸류 멀티플과 역사 밴드가 없어 가격 판단 신뢰도가 낮습니다.");
        }
        if (sector.rotationScore() == null && sector.buyScore() == null) {
            result.add("회사와 표준 섹터 점수 연결이 아직 없습니다.");
        }
        if (guidance == GuidanceDirection.UNKNOWN) {
            result.add("최근 구조화된 가이던스 방향을 확인하지 못했습니다.");
        }
        if (timing.technicalConfirmationScore() == null) {
            result.add("VWAP/OBV 수급 확인 데이터가 없습니다.");
        }
        if (timing.priceStructureScore() == null) {
            result.add("지지·저항 구간과 다우 가격 구조 데이터가 없습니다.");
        } else if (timing.fibonacciConfluenceScore() == null) {
            result.add("최근 명확한 주요 파동을 식별하지 못해 피보나치 합치도를 제외했습니다.");
        }
        if (quoteAge == null) {
            result.add("현재 가격 기준일을 확인하지 못했습니다.");
        } else if (quoteAge > 7) {
            result.add("가격 데이터가 " + quoteAge + "일 경과했습니다.");
        }
        if (fundamentalsAge == null) {
            result.add("재무 기준일을 확인하지 못했습니다.");
        } else if (fundamentalsAge > 150) {
            result.add("재무 기준일이 " + fundamentalsAge + "일 경과했습니다.");
        }
        var revenue = decimal(financials, "revenueTtm");
        var operatingIncome = decimal(financials, "operatingIncomeTtm");
        var freeCashFlow = decimal(financials, "freeCashFlowTtm");
        if (revenue != null && revenue > 0 && operatingIncome != null && operatingIncome > revenue * 1.2) {
            result.add("영업이익 TTM이 매출 TTM을 초과해 XBRL 기간 정합성 검증이 필요합니다.");
        }
        if (revenue != null && revenue > 0 && freeCashFlow != null && freeCashFlow > revenue * 1.2) {
            result.add("FCF TTM이 매출 TTM을 크게 초과해 XBRL 기간 정합성 검증이 필요합니다.");
        }
        return List.copyOf(result);
    }

    private static int availableEvidenceCount(
            ScoreEvidence score,
            FundamentalEvidence fundamental,
            ValuationEvidence valuation,
            CatalystEvidence catalyst,
            SectorEvidence sector,
            TimingEvidence timing
    ) {
        var count = 0;
        count += count(
                score.companyScore(), score.growthScore(), score.qualityScore(), score.valuationScore(),
                score.balanceSheetScore(), score.appealScore(), score.crowdingScore(), score.legacyBuyScore());
        count += count(
                fundamental.revenueGrowthYoY(), fundamental.operatingMargin(),
                fundamental.operatingMarginTrend(), fundamental.freeCashFlowMargin(),
                fundamental.roic(), fundamental.roe(), fundamental.netDebtToRevenue(),
                fundamental.currentRatio(), fundamental.shareDilutionYoY(),
                fundamental.shareDilution3yCagr(), fundamental.stockCompToRevenue(),
                fundamental.accrualRatio(), fundamental.cashConversionScore(),
                fundamental.earningsQualityScore(), fundamental.bottleneckScore(),
                fundamental.switchingCost());
        count += present(fundamental.pricingPower(), EvidenceStrength.UNKNOWN);
        count += present(fundamental.leadTimeSignal(), EvidenceStrength.UNKNOWN);
        count += present(fundamental.backlogSignal(), EvidenceStrength.UNKNOWN);
        count += count(
                valuation.evToSales(), valuation.evToFreeCashFlow(),
                valuation.premiumPctVsPeerAverage(), valuation.premiumPctVsPeerMedian());
        count += present(valuation.internalRange(), ValuationRangePosition.UNKNOWN);
        count += present(valuation.peerPosition(), ValuationRelativePosition.UNKNOWN);
        count += present(valuation.multipleCompressionRisk(), RiskBand.UNKNOWN);
        count += present(valuation.rateSensitivity(), RiskBand.UNKNOWN);
        count += present(valuation.narrativePremium(), RiskBand.UNKNOWN);
        count += count(
                catalyst.estimateUpsidePct(), catalyst.estimateRevision7d(),
                catalyst.estimateRevision30d(), catalyst.estimateRevision90d(),
                catalyst.analystScoreRevision7d(), catalyst.analystScoreRevision30d(),
                catalyst.analystScoreRevision90d(), catalyst.earningsBottomScore());
        count += present(catalyst.guidanceDirection(), GuidanceDirection.UNKNOWN);
        count += present(catalyst.narrativeStage(), NarrativeStage.UNKNOWN);
        count += present(catalyst.narrativeTrend(), NarrativeTrend.UNKNOWN);
        count += count(
                sector.buyScore(), sector.qualityScore(), sector.appealScore(), sector.crowdingScore(),
                sector.valuationScore(), sector.earningsRevisionScore(), sector.rotationScore(),
                sector.macroFitScore(), sector.relativeStrengthScore(), sector.fundamentalScore(),
                sector.flowScore());
        count += present(sector.stance(), SectorStance.UNKNOWN);
        count += present(sector.rotationState(), SectorRotationState.UNKNOWN);
        count += present(sector.marketBias(), MarketBias.UNKNOWN);
        count += sector.expectedLeadershipWindow().isBlank() ? 0 : 1;
        count += count(
                timing.bottomScore(), timing.priceBottomScore(), timing.volumeConfirmationScore(),
                timing.failureRiskScore(), timing.confirmedBottomScore(), timing.reversalScore(),
                timing.technicalConfirmationScore(), timing.priceStructureScore(), timing.rsi14(),
                timing.fibonacciNearestRatio(), timing.fibonacciConfluenceScore(),
                timing.correctionScore(),
                timing.trendBreakRiskScore(), timing.shortTermScore(), timing.swingTermScore(),
                timing.longTermScore(), timing.quoteAgeDays(), timing.fundamentalsAgeDays());
        count += present(timing.bottomConviction(), BottomConviction.UNKNOWN);
        count += present(timing.reversalState(), ReversalState.UNKNOWN);
        count += present(timing.technicalFlowState(), TechnicalFlowState.UNKNOWN);
        count += present(timing.priceTrendState(), PriceTrendState.UNKNOWN);
        count += present(timing.priceReversalStage(), PriceReversalStage.UNKNOWN);
        count += present(timing.priceRecoveryStage(), PriceRecoveryStage.UNKNOWN);
        count += present(timing.priceLocationState(), PriceLocationState.UNKNOWN);
        count += present(timing.movingAverageState(), MovingAverageState.UNKNOWN);
        count += present(timing.fibonacciSwingDirection(), FibonacciSwingDirection.UNKNOWN);
        count += present(timing.fibonacciZoneState(), FibonacciZoneState.UNKNOWN);
        if (timing.priceStructureScore() != null) count += 3;
        if (timing.fibonacciConfluenceScore() != null) {
            count += 2;
        }
        count += present(timing.thesisState(), ThesisState.UNKNOWN);
        return count;
    }

    private static GuidanceDirection guidanceDirection(ObjectValue guidance, ArrayValue filings) {
        var direct = guidanceDirection(text(guidance, "stance"));
        if (direct != GuidanceDirection.UNKNOWN) return direct;
        for (var value : filings.values()) {
            if (!(value instanceof ObjectValue filing)) continue;
            var summary = nestedObject(filing, "guidanceSummary");
            var candidate = guidanceDirection(text(summary, "stance"));
            if (candidate != GuidanceDirection.UNKNOWN) return candidate;
        }
        return GuidanceDirection.UNKNOWN;
    }

    private static GuidanceDirection guidanceDirection(String value) {
        return switch (normalized(value)) {
            case "RAISED" -> GuidanceDirection.RAISED;
            case "AFFIRMED" -> GuidanceDirection.AFFIRMED;
            case "MIXED" -> GuidanceDirection.MIXED;
            case "LOWERED" -> GuidanceDirection.LOWERED;
            default -> GuidanceDirection.UNKNOWN;
        };
    }

    private static EvidenceStrength evidenceStrength(String value) {
        return switch (normalized(value)) {
            case "높음", "강함", "STRONG", "HIGH" -> EvidenceStrength.STRONG;
            case "보통", "MODERATE", "MEDIUM" -> EvidenceStrength.MODERATE;
            case "낮음", "약함", "WEAK", "LOW" -> EvidenceStrength.WEAK;
            default -> EvidenceStrength.UNKNOWN;
        };
    }

    private static ValuationRangePosition valuationRange(String value) {
        return switch (normalized(value)) {
            case "저평가권", "UNDERVALUED" -> ValuationRangePosition.UNDERVALUED;
            case "중립권", "FAIR", "NEUTRAL" -> ValuationRangePosition.FAIR;
            case "고평가권", "OVERVALUED" -> ValuationRangePosition.OVERVALUED;
            default -> ValuationRangePosition.UNKNOWN;
        };
    }

    private static ValuationRelativePosition relativePosition(String value) {
        return switch (normalized(value)) {
            case "할인", "DISCOUNT" -> ValuationRelativePosition.DISCOUNT;
            case "중립", "NEUTRAL" -> ValuationRelativePosition.NEUTRAL;
            case "프리미엄", "PREMIUM" -> ValuationRelativePosition.PREMIUM;
            default -> ValuationRelativePosition.UNKNOWN;
        };
    }

    private static RiskBand riskBand(String value) {
        return switch (normalized(value)) {
            case "낮음", "LOW" -> RiskBand.LOW;
            case "보통", "MODERATE", "MEDIUM" -> RiskBand.MODERATE;
            case "높음", "HIGH" -> RiskBand.HIGH;
            default -> RiskBand.UNKNOWN;
        };
    }

    private static NarrativeStage narrativeStage(String value) {
        return switch (normalized(value)) {
            case "EARLY" -> NarrativeStage.EARLY;
            case "MID" -> NarrativeStage.MID;
            case "OVERHEATED" -> NarrativeStage.OVERHEATED;
            default -> NarrativeStage.UNKNOWN;
        };
    }

    private static NarrativeTrend narrativeTrend(String value) {
        return switch (normalized(value)) {
            case "HEATING" -> NarrativeTrend.HEATING;
            case "STABLE" -> NarrativeTrend.STABLE;
            case "COOLING" -> NarrativeTrend.COOLING;
            default -> NarrativeTrend.UNKNOWN;
        };
    }

    private static SectorStance sectorStance(String value) {
        return switch (normalized(value)) {
            case "FAVORED" -> SectorStance.FAVORED;
            case "AVOIDED" -> SectorStance.AVOIDED;
            case "NEUTRAL" -> SectorStance.NEUTRAL;
            default -> SectorStance.UNKNOWN;
        };
    }

    private static SectorRotationState sectorRotationState(String value) {
        return switch (normalized(value)) {
            case "LEADING" -> SectorRotationState.LEADING;
            case "IMPROVING" -> SectorRotationState.IMPROVING;
            case "WEAKENING" -> SectorRotationState.WEAKENING;
            case "LAGGING" -> SectorRotationState.LAGGING;
            default -> SectorRotationState.UNKNOWN;
        };
    }

    private static MarketBias marketBias(String value) {
        var normalized = normalized(value);
        if (normalized.contains("STRONG") && normalized.contains("BUY")) return MarketBias.STRONG_BUY;
        if (normalized.contains("BUY") || normalized.contains("SCALE_IN") || normalized.contains("ADD")) {
            return MarketBias.BUY;
        }
        if (normalized.contains("TAKE_PROFIT") || normalized.contains("REDUCE")) return MarketBias.REDUCE;
        if (normalized.contains("SELL") || normalized.contains("AVOID")) return MarketBias.SELL;
        if (normalized.contains("HOLD") || normalized.contains("WAIT")) return MarketBias.HOLD;
        return MarketBias.UNKNOWN;
    }

    private static BottomConviction bottomConviction(String value) {
        return switch (normalized(value)) {
            case "확신", "CONVICTION" -> BottomConviction.CONVICTION;
            case "후보", "CANDIDATE" -> BottomConviction.CANDIDATE;
            case "미충족", "UNMET" -> BottomConviction.UNMET;
            default -> BottomConviction.UNKNOWN;
        };
    }

    private static ReversalState reversalState(String value) {
        return switch (normalized(value)) {
            case "STRONG" -> ReversalState.STRONG;
            case "ON" -> ReversalState.ON;
            case "EARLY" -> ReversalState.EARLY;
            case "OFF" -> ReversalState.OFF;
            default -> ReversalState.UNKNOWN;
        };
    }

    private static TechnicalFlowState technicalState(String value) {
        return switch (normalized(value)) {
            case "매집 우위", "ACCUMULATION" -> TechnicalFlowState.ACCUMULATION;
            case "중립", "NEUTRAL" -> TechnicalFlowState.NEUTRAL;
            case "분산 우위", "DISTRIBUTION" -> TechnicalFlowState.DISTRIBUTION;
            case "데이터 부족", "UNAVAILABLE" -> TechnicalFlowState.UNAVAILABLE;
            default -> TechnicalFlowState.UNKNOWN;
        };
    }

    private static PriceTrendState priceTrendState(String value) {
        return enumValue(value, PriceTrendState.class, PriceTrendState.UNKNOWN);
    }

    private static PriceReversalStage priceReversalStage(String value) {
        return enumValue(value, PriceReversalStage.class, PriceReversalStage.UNKNOWN);
    }

    private static PriceRecoveryStage priceRecoveryStage(String value) {
        return enumValue(value, PriceRecoveryStage.class, PriceRecoveryStage.UNKNOWN);
    }

    private static PriceLocationState priceLocationState(String value) {
        return enumValue(value, PriceLocationState.class, PriceLocationState.UNKNOWN);
    }

    private static MovingAverageState movingAverageState(String value) {
        return enumValue(value, MovingAverageState.class, MovingAverageState.UNKNOWN);
    }

    private static FibonacciSwingDirection fibonacciSwingDirection(String value) {
        return enumValue(value, FibonacciSwingDirection.class, FibonacciSwingDirection.UNKNOWN);
    }

    private static FibonacciZoneState fibonacciZoneState(String value) {
        return enumValue(value, FibonacciZoneState.class, FibonacciZoneState.UNKNOWN);
    }

    private static <T extends Enum<T>> T enumValue(String value, Class<T> type, T fallback) {
        var normalized = normalized(value);
        if (normalized.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static ThesisState thesisState(String value) {
        return switch (normalized(value)) {
            case "유지", "INTACT" -> ThesisState.INTACT;
            case "일부 약화", "WEAKENED" -> ThesisState.WEAKENED;
            case "훼손 경계", "BREAK_RISK" -> ThesisState.BREAK_RISK;
            default -> ThesisState.UNKNOWN;
        };
    }

    private static FundamentalsReadiness fundamentalsReadiness(String value) {
        return enumValue(value, FundamentalsReadiness.class, FundamentalsReadiness.UNKNOWN);
    }

    private static Integer ageDays(String value, LocalDate today) {
        if (value == null || value.isBlank()) return null;
        try {
            var date = LocalDate.parse(value);
            // A one-day lead can occur around UTC/local market boundaries.
            // Anything further in the future is invalid source evidence and
            // must fail closed rather than be treated as perfectly fresh.
            if (date.isAfter(today.plusDays(1))) return null;
            if (date.isAfter(today)) return 0;
            var days = java.time.temporal.ChronoUnit.DAYS.between(date, today);
            return days > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) days;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String action(CompanyInvestmentAction value) {
        return switch (value) {
            case STRONG_BUY -> "STRONG BUY";
            case BUY -> "BUY";
            case HOLD -> "HOLD";
            case REDUCE -> "REDUCE";
            case SELL -> "SELL";
        };
    }

    private static String actionLabel(CompanyInvestmentAction value) {
        return switch (value) {
            case STRONG_BUY -> "적극 분할매수";
            case BUY -> "1차 분할매수";
            case HOLD -> "대기/관찰";
            case REDUCE -> "비중 축소";
            case SELL -> "매도/회피";
        };
    }

    private static String opportunityLabel(CompanyOpportunityType value) {
        return switch (value) {
            case QUALITY_AT_REASONABLE_PRICE -> "좋은 회사 + 합리적 가격";
            case EARLY_CATALYST -> "초기 촉매 개선";
            case DEEP_VALUE_TURNAROUND -> "저평가 턴어라운드";
            case QUALITY_BUT_EXPENSIVE -> "좋지만 비쌈";
            case VALUE_TRAP_RISK -> "가치함정 위험";
            case MOMENTUM_WITH_RISK -> "반전 우선·위험 동반";
            case BALANCED_WATCH -> "선별 관찰";
            case INSUFFICIENT_EVIDENCE -> "데이터 부족";
        };
    }

    private static String horizonLabel(ForwardHorizon value) {
        return switch (value) {
            case ONE_MONTH -> "약 1개월";
            case THREE_MONTHS -> "약 3개월";
            case SIX_MONTHS -> "약 6개월";
        };
    }

    private static List<String> decisionPlan(CompanyInvestmentAction value) {
        return switch (value) {
            case STRONG_BUY -> List.of(
                    "1차 35~40%: 현재 구간에서 분할 진입",
                    "2차 25~30%: 지지 확인 또는 거래량 동반 재돌파",
                    "3차 잔여: 실적·가이던스 유지/상향 확인 후");
            case BUY -> List.of(
                    "1차 25%: 현재는 소액 분할 진입만",
                    "2차 30%: 가격 지지와 반전 거래량 재확인",
                    "3차 잔여: 다음 실적·가이던스 확인 후");
            case HOLD -> List.of(
                    "신규 1차 진입 대기: 가장 약한 판단축 개선 확인",
                    "진입 후보 전환: 반전·거래량 또는 컨센서스 상향 확인",
                    "확인 전 현금 보존");
            case REDUCE -> List.of(
                    "신규 진입 중단",
                    "반등 시 위험 노출 축소 검토",
                    "실적·섹터·추세 회복 전 재진입 보류");
            case SELL -> List.of(
                    "핵심 투자 논리 재검증 전 회피",
                    "손실 만회를 위한 물타기 금지",
                    "가이던스·현금흐름·추세가 모두 회복된 뒤 재평가");
        };
    }

    private static List<String> mergeThesisBreaks(List<String> decision, List<String> existing) {
        var result = new LinkedHashSet<String>();
        result.addAll(existing);
        result.addAll(decision);
        return result.stream().limit(8).toList();
    }

    private static String planAction(String value) {
        var normalized = normalized(value);
        if (normalized.contains("STRONG") && normalized.contains("BUY")) return "STRONG BUY";
        if (normalized.contains("BUY") || normalized.contains("SCALE_IN") || normalized.contains("ADD")) return "BUY";
        if (normalized.contains("REDUCE") || normalized.contains("TAKE_PROFIT")) return "REDUCE";
        if (normalized.contains("SELL")) return "SELL";
        return "HOLD";
    }

    private static int actionRank(String value) {
        return switch (value) {
            case "STRONG BUY" -> 5;
            case "BUY" -> 4;
            case "HOLD" -> 3;
            case "REDUCE" -> 2;
            default -> 1;
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int count(Object... values) {
        var count = 0;
        for (var value : values) if (value != null) count++;
        return count;
    }

    private static <T> int present(T value, T unknown) {
        return value == null || value == unknown ? 0 : 1;
    }

    private static ObjectValue object(StructuredValue value) {
        return value instanceof ObjectValue object ? object : new ObjectValue(Map.of());
    }

    private static ObjectValue nestedObject(ObjectValue parent, String field) {
        return parent.fields().get(field) instanceof ObjectValue object ? object : new ObjectValue(Map.of());
    }

    private static ObjectValue nestedObject(
            Map<String, StructuredValue> parent,
            String field
    ) {
        return parent.get(field) instanceof ObjectValue object ? object : new ObjectValue(Map.of());
    }

    private static ArrayValue array(ObjectValue parent, String field) {
        return parent.fields().get(field) instanceof ArrayValue array ? array : null;
    }

    private static LinkedHashMap<String, StructuredValue> fields(ObjectValue source) {
        return new LinkedHashMap<>(source.fields());
    }

    private static String text(ObjectValue source, String field) {
        return source.fields().get(field) instanceof TextValue text ? text.value() : null;
    }

    private static String text(Map<String, StructuredValue> source, String field) {
        return source.get(field) instanceof TextValue text ? text.value() : null;
    }

    private static Integer integer(ObjectValue source, String field) {
        var value = source.fields().get(field);
        if (!(value instanceof NumberValue number)) return null;
        var converted = number.value().doubleValue();
        if (!Double.isFinite(converted)
                || converted < Integer.MIN_VALUE
                || converted > Integer.MAX_VALUE) return null;
        return (int) Math.round(converted);
    }

    private static Integer nestedInteger(ObjectValue source, String objectField, String numberField) {
        return integer(nestedObject(source, objectField), numberField);
    }

    private static Double decimal(ObjectValue source, String field) {
        var value = source.fields().get(field);
        if (!(value instanceof NumberValue number)) return null;
        var converted = number.value().doubleValue();
        return Double.isFinite(converted) ? converted : null;
    }

    private static boolean bool(ObjectValue source, String field) {
        return source.fields().get(field) instanceof BooleanValue value && value.value();
    }

    private static List<String> textList(ObjectValue source, String field) {
        var value = source.fields().get(field);
        if (!(value instanceof ArrayValue array)) return List.of();
        return array.values().stream()
                .filter(TextValue.class::isInstance)
                .map(TextValue.class::cast)
                .map(TextValue::value)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private static String defaultText(String value) {
        return value == null ? "" : value;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int clampScore(double value) {
        return Math.max(0, Math.min(100, (int) Math.round(value)));
    }

    private static String signedPercent(double value) {
        return String.format(Locale.ROOT, "%+.1f%%", value);
    }

    private static TextValue text(String value) {
        return new TextValue(value == null ? "" : value);
    }

    private static StructuredValue number(Double value) {
        return value == null || !Double.isFinite(value)
                ? NullValue.INSTANCE : new NumberValue(BigDecimal.valueOf(value));
    }

    private static NumberValue number(int value) {
        return new NumberValue((long) value);
    }

    private static ArrayValue texts(List<String> values) {
        return new ArrayValue(values.stream()
                .map(CompanyInvestmentDecisionComposer::text)
                .map(StructuredValue.class::cast)
                .toList());
    }
}
