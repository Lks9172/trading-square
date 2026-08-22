package io.macrosquare.execution.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record InvestmentPlan(
        InvestmentHorizon horizon,
        double targetReturnAnnualPct,
        double maxDrawdownTolerancePct,
        int rebalanceIntervalDays,
        double leverageMaxPct,
        double profitTakeTargetPct,
        double stopLossPct,
        long monthlyDcaKrw,
        Map<String, Double> currentHoldings,
        Long totalCapitalKrw,
        Double totalCapitalUsd,
        Map<String, Double> currentHoldingsUsd,
        LocalDate accountStartDate,
        Double startingCapitalUsd,
        Long startingCapitalKrw,
        Double investmentExperienceYears,
        String accountType,
        String notes,
        Instant updatedAt
) {
    private static final int MAX_NOTES_LENGTH = 4_000;

    public InvestmentPlan {
        horizon = Objects.requireNonNull(horizon, "horizon");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        requireFiniteRange(targetReturnAnnualPct, -100, 1_000, "targetReturnAnnualPct");
        requireFiniteRange(maxDrawdownTolerancePct, 0, 100, "maxDrawdownTolerancePct");
        if (rebalanceIntervalDays < 1 || rebalanceIntervalDays > 3_650) {
            throw new IllegalArgumentException("rebalanceIntervalDays must be between 1 and 3650");
        }
        requireFiniteRange(leverageMaxPct, 0, 100, "leverageMaxPct");
        requireFiniteRange(profitTakeTargetPct, 0, 1_000, "profitTakeTargetPct");
        requireFiniteRange(stopLossPct, 0, 100, "stopLossPct");
        if (monthlyDcaKrw < 0) throw new IllegalArgumentException("monthlyDCA_KRW must not be negative");
        // Legacy production data contains both documented percentage values and
        // historical absolute-position values. Rejecting the latter would make
        // a lossless cutover impossible. Unit interpretation remains an
        // application/reporting concern; the domain still enforces supported
        // assets, finiteness and non-negative holdings.
        currentHoldings = immutableHoldings(currentHoldings, "currentHoldings");
        currentHoldingsUsd = immutableHoldings(currentHoldingsUsd, "currentHoldingsUSD");
        if (totalCapitalKrw != null && totalCapitalKrw < 0) {
            throw new IllegalArgumentException("totalCapitalKRW must not be negative");
        }
        requireNullableNonNegative(totalCapitalUsd, "totalCapitalUSD");
        requireNullableNonNegative(startingCapitalUsd, "startingCapitalUSD");
        if (startingCapitalKrw != null && startingCapitalKrw < 0) {
            throw new IllegalArgumentException("startingCapitalKRW must not be negative");
        }
        requireNullableNonNegative(investmentExperienceYears, "investmentExperienceYears");
        if (accountType != null && !accountType.matches("general|isa|pension|foreign")) {
            throw new IllegalArgumentException("accountType must be general, isa, pension, or foreign");
        }
        if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
            throw new IllegalArgumentException("notes is too long");
        }
    }

    public static InvestmentPlan defaults(Instant now) {
        return new InvestmentPlan(
                InvestmentHorizon.MEDIUM,
                12,
                25,
                90,
                15,
                25,
                15,
                1_000_000,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now
        );
    }

    private static Map<String, Double> immutableHoldings(Map<String, Double> source, String field) {
        if (source == null) return null;
        var result = new LinkedHashMap<String, Double>();
        source.forEach((key, value) -> {
            if (key == null || !key.matches("cash|nasdaq|leverage|gold|silver|copper|korea|emerging")) {
                throw new IllegalArgumentException(field + " contains an unsupported asset");
            }
            requireFiniteRange(value, 0, Double.MAX_VALUE, field + "." + key);
            result.put(key, value);
        });
        return java.util.Collections.unmodifiableMap(result);
    }

    private static void requireNullableNonNegative(Double value, String field) {
        if (value != null) requireFiniteRange(value, 0, Double.MAX_VALUE, field);
    }

    private static void requireFiniteRange(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is outside the supported range");
        }
    }
}
