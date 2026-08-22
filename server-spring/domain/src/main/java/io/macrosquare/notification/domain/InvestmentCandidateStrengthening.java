package io.macrosquare.notification.domain;

/** Material company-signal changes that merit another notification after entry. */
public record InvestmentCandidateStrengthening(
        boolean reversalBecameStrong,
        Integer previousTotalScoreBand,
        Integer currentTotalScoreBand,
        Integer previousBuyScoreBand,
        Integer currentBuyScoreBand
) {
    public InvestmentCandidateStrengthening {
        validateBand(previousTotalScoreBand, "previousTotalScoreBand");
        validateBand(currentTotalScoreBand, "currentTotalScoreBand");
        validateBand(previousBuyScoreBand, "previousBuyScoreBand");
        validateBand(currentBuyScoreBand, "currentBuyScoreBand");
        if ((previousTotalScoreBand == null) != (currentTotalScoreBand == null)
                || (previousBuyScoreBand == null) != (currentBuyScoreBand == null)) {
            throw new IllegalArgumentException("score-band transitions require both previous and current bands");
        }
    }

    public boolean strengthened() {
        return reversalBecameStrong || currentTotalScoreBand != null || currentBuyScoreBand != null;
    }

    public static InvestmentCandidateStrengthening none() {
        return new InvestmentCandidateStrengthening(false, null, null, null, null);
    }

    private static void validateBand(Integer value, String field) {
        if (value != null && (value < 70 || value > 100 || value % 5 != 0)) {
            throw new IllegalArgumentException(field + " must be a five-point band between 70 and 100");
        }
    }
}
