package io.macrosquare.research.domain.rotation;

public record SectorRotationEvidence(
        String key,
        String label,
        SectorClassification classification,
        Double mediumTermRelativeStrength,
        Double shortTermRelativeStrength,
        Integer institutionalMomentumScore,
        Boolean absoluteTrendPositive,
        Integer qualityScore,
        Integer appealScore,
        Integer valuationScore,
        Integer earningsRevisionScore,
        Integer crowdingScore,
        Integer buyScore,
        java.time.LocalDate earningsRevisionObservedOn,
        Integer earningsRevisionCoveragePct,
        Integer fundFlowScore,
        java.time.LocalDate fundFlowObservedOn,
        Integer priceBreadthScore,
        java.time.LocalDate priceBreadthObservedOn,
        Integer priceBreadthCoveragePct
) {
    public SectorRotationEvidence(
            String key,
            String label,
            SectorClassification classification,
            Double mediumTermRelativeStrength,
            Double shortTermRelativeStrength,
            Integer institutionalMomentumScore,
            Boolean absoluteTrendPositive,
            Integer qualityScore,
            Integer appealScore,
            Integer valuationScore,
            Integer earningsRevisionScore,
            Integer crowdingScore,
            Integer buyScore
    ) {
        this(key, label, classification, mediumTermRelativeStrength, shortTermRelativeStrength,
                institutionalMomentumScore, absoluteTrendPositive, qualityScore, appealScore,
                valuationScore, earningsRevisionScore, crowdingScore, buyScore,
                null, null, null, null, null, null, null);
    }

    public SectorRotationEvidence(
            String key,
            String label,
            SectorClassification classification,
            Double mediumTermRelativeStrength,
            Double shortTermRelativeStrength,
            Integer institutionalMomentumScore,
            Boolean absoluteTrendPositive,
            Integer qualityScore,
            Integer appealScore,
            Integer valuationScore,
            Integer earningsRevisionScore,
            Integer crowdingScore,
            Integer buyScore,
            java.time.LocalDate earningsRevisionObservedOn,
            Integer earningsRevisionCoveragePct
    ) {
        this(key, label, classification, mediumTermRelativeStrength, shortTermRelativeStrength,
                institutionalMomentumScore, absoluteTrendPositive, qualityScore, appealScore,
                valuationScore, earningsRevisionScore, crowdingScore, buyScore,
                earningsRevisionObservedOn, earningsRevisionCoveragePct,
                null, null, null, null, null);
    }

    public SectorRotationEvidence {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        if (classification == null) throw new IllegalArgumentException("classification is required");
        requireFinite(mediumTermRelativeStrength, "mediumTermRelativeStrength");
        requireFinite(shortTermRelativeStrength, "shortTermRelativeStrength");
        requireScore(institutionalMomentumScore, "institutionalMomentumScore");
        requireScore(qualityScore, "qualityScore");
        requireScore(appealScore, "appealScore");
        requireScore(valuationScore, "valuationScore");
        requireScore(earningsRevisionScore, "earningsRevisionScore");
        requireScore(crowdingScore, "crowdingScore");
        requireScore(buyScore, "buyScore");
        requireScore(earningsRevisionCoveragePct, "earningsRevisionCoveragePct");
        requireScore(fundFlowScore, "fundFlowScore");
        requireScore(priceBreadthScore, "priceBreadthScore");
        requireScore(priceBreadthCoveragePct, "priceBreadthCoveragePct");
        if ((earningsRevisionObservedOn == null) != (earningsRevisionCoveragePct == null)
                || (earningsRevisionObservedOn != null && earningsRevisionScore == null)) {
            throw new IllegalArgumentException("dated earnings revision evidence must be complete");
        }
        if ((fundFlowObservedOn == null) != (fundFlowScore == null)) {
            throw new IllegalArgumentException("dated fund flow evidence must be complete");
        }
        if ((priceBreadthObservedOn == null) != (priceBreadthCoveragePct == null)
                || (priceBreadthObservedOn != null && priceBreadthScore == null)) {
            throw new IllegalArgumentException("dated price breadth evidence must be complete");
        }
        key = key.trim();
        label = label.trim();
    }

    private static void requireFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static void requireScore(Integer value, String field) {
        if (value != null && (value < 0 || value > 100)) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
    }
}
