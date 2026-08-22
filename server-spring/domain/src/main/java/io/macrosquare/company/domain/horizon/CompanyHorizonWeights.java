package io.macrosquare.company.domain.horizon;

public record CompanyHorizonWeights(
        int company,
        int quality,
        int growth,
        int valuation,
        int balanceSheet,
        int buy,
        int bottom,
        int reversal,
        int technical
) {
    public CompanyHorizonWeights {
        var total = company + quality + growth + valuation + balanceSheet + buy + bottom + reversal + technical;
        if (total != 100) throw new IllegalArgumentException("horizon weights must sum to 100");
        if (company < 0 || quality < 0 || growth < 0 || valuation < 0 || balanceSheet < 0
                || buy < 0 || bottom < 0 || reversal < 0 || technical < 0) {
            throw new IllegalArgumentException("horizon weights must be non-negative");
        }
    }

    public int total() {
        return company + quality + growth + valuation + balanceSheet + buy + bottom + reversal + technical;
    }
}
