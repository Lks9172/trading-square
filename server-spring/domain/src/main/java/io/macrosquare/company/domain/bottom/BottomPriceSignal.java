package io.macrosquare.company.domain.bottom;

public record BottomPriceSignal(
        int priceResetScore,
        int patternScore,
        int absorptionScore,
        int volumeConfirmationScore,
        int priceBottomScore,
        int failureRiskScore,
        BottomStructureState structureState
) {
    public BottomPriceSignal {
        requireScore(priceResetScore, "priceResetScore");
        requireScore(patternScore, "patternScore");
        requireScore(absorptionScore, "absorptionScore");
        requireScore(volumeConfirmationScore, "volumeConfirmationScore");
        requireScore(priceBottomScore, "priceBottomScore");
        requireScore(failureRiskScore, "failureRiskScore");
        if (structureState == null) throw new IllegalArgumentException("structureState is required");
    }

    private static void requireScore(int value, String field) {
        if (value < 0 || value > 100) throw new IllegalArgumentException(field + " must be between 0 and 100");
    }
}
