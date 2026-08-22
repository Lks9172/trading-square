package io.macrosquare.company.domain.investment;

import java.util.List;

public record CompanyInvestmentDecision(
        String version,
        int investmentMeritScore,
        int entryReadinessScore,
        int decisionScore,
        CompanyInvestmentAction action,
        CompanyOpportunityType opportunityType,
        InvestmentDimension quality,
        InvestmentDimension valuation,
        InvestmentDimension catalyst,
        InvestmentDimension sector,
        InvestmentDimension timing,
        RiskAssessment risk,
        DataQualityAssessment dataQuality,
        ScaleInEligibility scaleInEligibility,
        EntryStrategy entryStrategy,
        List<ForwardOutlook> forwardOutlooks,
        String summary,
        List<String> whyNow,
        List<String> whyWait,
        List<String> thesisBreaks,
        String methodology
) {
    public CompanyInvestmentDecision {
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version is required");
        validateScore(investmentMeritScore, "investmentMeritScore");
        validateScore(entryReadinessScore, "entryReadinessScore");
        validateScore(decisionScore, "decisionScore");
        if (action == null) throw new IllegalArgumentException("action is required");
        if (opportunityType == null) throw new IllegalArgumentException("opportunityType is required");
        if (quality == null || valuation == null || catalyst == null || sector == null || timing == null) {
            throw new IllegalArgumentException("all dimensions are required");
        }
        if (risk == null || dataQuality == null) throw new IllegalArgumentException("risk and dataQuality are required");
        if (scaleInEligibility == null) throw new IllegalArgumentException("scaleInEligibility is required");
        if (entryStrategy == null) throw new IllegalArgumentException("entryStrategy is required");
        forwardOutlooks = List.copyOf(forwardOutlooks == null ? List.of() : forwardOutlooks);
        if (summary == null || summary.isBlank()) throw new IllegalArgumentException("summary is required");
        whyNow = List.copyOf(whyNow == null ? List.of() : whyNow);
        whyWait = List.copyOf(whyWait == null ? List.of() : whyWait);
        thesisBreaks = List.copyOf(thesisBreaks == null ? List.of() : thesisBreaks);
        if (methodology == null || methodology.isBlank()) throw new IllegalArgumentException("methodology is required");
    }

    public record EntryStrategy(
            int initialEntryPctOfTarget,
            int reservePctOfTarget,
            String zoneLabel,
            String summary,
            List<String> addConditions,
            List<String> reduceConditions
    ) {
        public EntryStrategy {
            validateScore(initialEntryPctOfTarget, "initialEntryPctOfTarget");
            validateScore(reservePctOfTarget, "reservePctOfTarget");
            if (initialEntryPctOfTarget + reservePctOfTarget != 100) {
                throw new IllegalArgumentException("entry and reserve percentages must total 100");
            }
            zoneLabel = zoneLabel == null || zoneLabel.isBlank() ? "구조 확인 대기" : zoneLabel.trim();
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("entry summary is required");
            addConditions = List.copyOf(addConditions == null ? List.of() : addConditions);
            reduceConditions = List.copyOf(reduceConditions == null ? List.of() : reduceConditions);
        }
    }

    public record RiskAssessment(
            int score,
            CompanyRiskLevel level,
            String summary,
            List<String> reasons
    ) {
        public RiskAssessment {
            validateScore(score, "risk score");
            if (level == null) throw new IllegalArgumentException("risk level is required");
            if (summary == null || summary.isBlank()) throw new IllegalArgumentException("risk summary is required");
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
        }
    }

    public record DataQualityAssessment(
            int coveragePct,
            int confidence,
            DataQualityLevel level,
            String summary,
            List<String> warnings
    ) {
        public DataQualityAssessment {
            validateScore(coveragePct, "coveragePct");
            validateScore(confidence, "data confidence");
            if (level == null) throw new IllegalArgumentException("data quality level is required");
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("data quality summary is required");
            }
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }
    }

    /**
     * Gate for staged buying. A falling price is never enough: the underlying
     * business and thesis must first be capable of recovering.
     */
    public record ScaleInEligibility(
            int score,
            ScaleInEligibilityState state,
            int portfolioConcentrationCapPct,
            String summary,
            List<String> reasons,
            List<String> blockers
    ) {
        public ScaleInEligibility {
            validateScore(score, "scale-in eligibility score");
            if (state == null) throw new IllegalArgumentException("scale-in eligibility state is required");
            validateScore(portfolioConcentrationCapPct, "portfolioConcentrationCapPct");
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("scale-in eligibility summary is required");
            }
            reasons = List.copyOf(reasons == null ? List.of() : reasons);
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
            if ((state == ScaleInEligibilityState.INELIGIBLE
                    || state == ScaleInEligibilityState.UNAVAILABLE)
                    && portfolioConcentrationCapPct != 0) {
                throw new IllegalArgumentException("ineligible or unavailable assets must have a zero concentration cap");
            }
        }
    }

    public record ForwardOutlook(
            ForwardHorizon horizon,
            int forwardTradingDays,
            Double positiveReturnLikelihoodPct,
            Double targetReturnPct,
            Double targetHitLikelihoodPct,
            Double averageReturnPct,
            Double averageMaxDrawdownPct,
            int sampleCount,
            int confidence,
            OutlookMethod method,
            String caution
    ) {
        public ForwardOutlook {
            if (horizon == null) throw new IllegalArgumentException("horizon is required");
            if (forwardTradingDays < 1) throw new IllegalArgumentException("forwardTradingDays must be positive");
            requirePercent(positiveReturnLikelihoodPct, "positiveReturnLikelihoodPct");
            requirePercent(targetHitLikelihoodPct, "targetHitLikelihoodPct");
            requireFinite(targetReturnPct, averageReturnPct, averageMaxDrawdownPct);
            if (sampleCount < 0) throw new IllegalArgumentException("sampleCount must not be negative");
            validateScore(confidence, "outlook confidence");
            if (method == null) throw new IllegalArgumentException("outlook method is required");
            if (caution == null || caution.isBlank()) throw new IllegalArgumentException("outlook caution is required");
        }
    }

    public enum CompanyInvestmentAction {
        STRONG_BUY,
        BUY,
        HOLD,
        REDUCE,
        SELL
    }

    public enum CompanyOpportunityType {
        QUALITY_AT_REASONABLE_PRICE,
        EARLY_CATALYST,
        DEEP_VALUE_TURNAROUND,
        QUALITY_BUT_EXPENSIVE,
        VALUE_TRAP_RISK,
        MOMENTUM_WITH_RISK,
        BALANCED_WATCH,
        INSUFFICIENT_EVIDENCE
    }

    public enum CompanyRiskLevel {
        LOW,
        MODERATE,
        HIGH,
        CRITICAL
    }

    public enum DataQualityLevel {
        HIGH,
        MODERATE,
        LOW
    }

    public enum ScaleInEligibilityState {
        ELIGIBLE,
        CONDITIONAL,
        INELIGIBLE,
        UNAVAILABLE
    }

    public enum ForwardHorizon {
        ONE_MONTH,
        THREE_MONTHS,
        SIX_MONTHS
    }

    public enum OutlookMethod {
        WALK_FORWARD,
        SCORE_HEURISTIC
    }

    private static void validateScore(int value, String field) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }

    private static void requirePercent(Double value, String field) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 100)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }

    private static void requireFinite(Double... values) {
        for (var value : values) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException("outlook number must be finite");
            }
        }
    }
}
