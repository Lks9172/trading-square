package io.macrosquare.execution.application.model;

import io.macrosquare.execution.domain.model.InvestmentHorizon;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

public record InvestmentPlanPatch(
        PatchValue<InvestmentHorizon> horizon,
        PatchValue<Double> targetReturnAnnualPct,
        PatchValue<Double> maxDrawdownTolerancePct,
        PatchValue<Integer> rebalanceIntervalDays,
        PatchValue<Double> leverageMaxPct,
        PatchValue<Double> profitTakeTargetPct,
        PatchValue<Double> stopLossPct,
        PatchValue<Long> monthlyDcaKrw,
        PatchValue<Map<String, Double>> currentHoldings,
        PatchValue<Long> totalCapitalKrw,
        PatchValue<Double> totalCapitalUsd,
        PatchValue<Map<String, Double>> currentHoldingsUsd,
        PatchValue<LocalDate> accountStartDate,
        PatchValue<Double> startingCapitalUsd,
        PatchValue<Long> startingCapitalKrw,
        PatchValue<Double> investmentExperienceYears,
        PatchValue<String> accountType,
        PatchValue<String> notes
) {
    public InvestmentPlanPatch {
        horizon = required(horizon);
        targetReturnAnnualPct = required(targetReturnAnnualPct);
        maxDrawdownTolerancePct = required(maxDrawdownTolerancePct);
        rebalanceIntervalDays = required(rebalanceIntervalDays);
        leverageMaxPct = required(leverageMaxPct);
        profitTakeTargetPct = required(profitTakeTargetPct);
        stopLossPct = required(stopLossPct);
        monthlyDcaKrw = required(monthlyDcaKrw);
        currentHoldings = required(currentHoldings);
        totalCapitalKrw = required(totalCapitalKrw);
        totalCapitalUsd = required(totalCapitalUsd);
        currentHoldingsUsd = required(currentHoldingsUsd);
        accountStartDate = required(accountStartDate);
        startingCapitalUsd = required(startingCapitalUsd);
        startingCapitalKrw = required(startingCapitalKrw);
        investmentExperienceYears = required(investmentExperienceYears);
        accountType = required(accountType);
        notes = required(notes);
    }

    private static <T> PatchValue<T> required(PatchValue<T> value) {
        return Objects.requireNonNull(value, "patch value");
    }
}
