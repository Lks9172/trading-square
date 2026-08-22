package io.macrosquare.execution.application.service;

import io.macrosquare.execution.application.port.in.EvaluatePurchasingPowerUseCase;
import io.macrosquare.execution.domain.model.PurchasingPowerProjection;
import io.macrosquare.execution.domain.service.PurchasingPowerPolicy;

import java.util.Objects;

public final class EvaluatePurchasingPowerService implements EvaluatePurchasingPowerUseCase {

    private final PurchasingPowerPolicy policy;

    public EvaluatePurchasingPowerService(PurchasingPowerPolicy policy) {
        this.policy = Objects.requireNonNull(policy);
    }

    @Override
    public PurchasingPowerProjection evaluate(
            long principalKrw,
            int years,
            double inflationPct,
            double cashYieldPct,
            double productiveAssetReturnPct
    ) {
        return policy.evaluate(
                principalKrw,
                years,
                inflationPct,
                cashYieldPct,
                productiveAssetReturnPct
        );
    }
}
