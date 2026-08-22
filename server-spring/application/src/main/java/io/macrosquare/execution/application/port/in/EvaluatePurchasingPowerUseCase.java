package io.macrosquare.execution.application.port.in;

import io.macrosquare.execution.domain.model.PurchasingPowerProjection;

public interface EvaluatePurchasingPowerUseCase {

    PurchasingPowerProjection evaluate(
            long principalKrw,
            int years,
            double inflationPct,
            double cashYieldPct,
            double productiveAssetReturnPct
    );
}
