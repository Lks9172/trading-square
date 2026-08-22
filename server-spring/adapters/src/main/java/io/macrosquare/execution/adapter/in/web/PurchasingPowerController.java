package io.macrosquare.execution.adapter.in.web;

import io.macrosquare.execution.application.port.in.EvaluatePurchasingPowerUseCase;
import io.macrosquare.execution.domain.model.PurchasingPowerProjection;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
public final class PurchasingPowerController {

    private final EvaluatePurchasingPowerUseCase purchasingPower;

    public PurchasingPowerController(EvaluatePurchasingPowerUseCase purchasingPower) {
        this.purchasingPower = Objects.requireNonNull(purchasingPower);
    }

    @GetMapping("/api/execution-plan/purchasing-power")
    public PurchasingPowerEnvelope evaluate(
            @RequestParam(name = "principalKrw", defaultValue = "100000000") long principalKrw,
            @RequestParam(name = "years", defaultValue = "30") int years,
            @RequestParam(name = "inflationPct", defaultValue = "3.0") double inflationPct,
            @RequestParam(name = "cashYieldPct", defaultValue = "2.5") double cashYieldPct,
            @RequestParam(name = "productiveAssetReturnPct", defaultValue = "7.0")
            double productiveAssetReturnPct
    ) {
        return new PurchasingPowerEnvelope(PurchasingPowerResponse.from(
                purchasingPower.evaluate(
                        principalKrw,
                        years,
                        inflationPct,
                        cashYieldPct,
                        productiveAssetReturnPct
                )
        ));
    }

    public record PurchasingPowerEnvelope(PurchasingPowerResponse projection) {
    }

    public record PurchasingPowerResponse(
            long principalKrw,
            int years,
            double inflationPct,
            long futureCostOfTodayBasketKrw,
            ScenarioResponse cashLike,
            ScenarioResponse productiveAsset,
            long productiveAssetRealAdvantageKrw,
            String summary,
            String methodology,
            List<String> cautions
    ) {
        static PurchasingPowerResponse from(PurchasingPowerProjection value) {
            return new PurchasingPowerResponse(
                    value.principalKrw(),
                    value.years(),
                    value.inflationPct(),
                    value.futureCostOfTodayBasketKrw(),
                    ScenarioResponse.from(value.cashLike()),
                    ScenarioResponse.from(value.productiveAsset()),
                    value.productiveAssetRealAdvantageKrw(),
                    value.summary(),
                    value.methodology(),
                    value.cautions()
            );
        }
    }

    public record ScenarioResponse(
            String key,
            String label,
            double annualNominalReturnPct,
            double annualRealReturnPct,
            long nominalFutureValueKrw,
            long realFutureValueKrw,
            double purchasingPowerRetentionPct,
            long realGainLossKrw
    ) {
        static ScenarioResponse from(PurchasingPowerProjection.PurchasingPowerScenario value) {
            return new ScenarioResponse(
                    value.key(),
                    value.label(),
                    value.annualNominalReturnPct(),
                    value.annualRealReturnPct(),
                    value.nominalFutureValueKrw(),
                    value.realFutureValueKrw(),
                    value.purchasingPowerRetentionPct(),
                    value.realGainLossKrw()
            );
        }
    }
}
