package io.macrosquare.execution.domain.model;

import java.util.List;

/**
 * Inflation-adjusted comparison of holding cash-like savings and a hypothetical
 * productive asset. Values are expressed both in future nominal KRW and in
 * today's purchasing-power KRW.
 */
public record PurchasingPowerProjection(
        long principalKrw,
        int years,
        double inflationPct,
        long futureCostOfTodayBasketKrw,
        PurchasingPowerScenario cashLike,
        PurchasingPowerScenario productiveAsset,
        long productiveAssetRealAdvantageKrw,
        String summary,
        String methodology,
        List<String> cautions
) {
    public PurchasingPowerProjection {
        if (principalKrw < 1) throw new IllegalArgumentException("principalKrw must be positive");
        if (years < 1 || years > 100) throw new IllegalArgumentException("years must be between 1 and 100");
        requireFinite(inflationPct);
        if (futureCostOfTodayBasketKrw < 1) {
            throw new IllegalArgumentException("futureCostOfTodayBasketKrw must be positive");
        }
        if (cashLike == null || productiveAsset == null) {
            throw new IllegalArgumentException("both purchasing-power scenarios are required");
        }
        summary = summary == null || summary.isBlank()
                ? "명목 수익이 아니라 물가를 뺀 실질 구매력을 비교합니다."
                : summary.trim();
        if (methodology == null || methodology.isBlank()) {
            throw new IllegalArgumentException("methodology is required");
        }
        cautions = List.copyOf(cautions == null ? List.of() : cautions);
    }

    public record PurchasingPowerScenario(
            String key,
            String label,
            double annualNominalReturnPct,
            double annualRealReturnPct,
            long nominalFutureValueKrw,
            long realFutureValueKrw,
            double purchasingPowerRetentionPct,
            long realGainLossKrw
    ) {
        public PurchasingPowerScenario {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("scenario key is required");
            if (label == null || label.isBlank()) throw new IllegalArgumentException("scenario label is required");
            requireFinite(annualNominalReturnPct, annualRealReturnPct, purchasingPowerRetentionPct);
            if (nominalFutureValueKrw < 0 || realFutureValueKrw < 0) {
                throw new IllegalArgumentException("future values must not be negative");
            }
        }
    }

    private static void requireFinite(double... values) {
        for (var value : values) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("purchasing-power number must be finite");
            }
        }
    }
}
