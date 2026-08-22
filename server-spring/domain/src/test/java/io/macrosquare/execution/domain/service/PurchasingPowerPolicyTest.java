package io.macrosquare.execution.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchasingPowerPolicyTest {

    private final PurchasingPowerPolicy policy = new PurchasingPowerPolicy();

    @Test
    void separatesGrowingNominalBalanceFromFallingRealPurchasingPower() {
        var result = policy.evaluate(100_000_000L, 30, 3.0, 2.5, 7.0);

        assertTrue(result.cashLike().nominalFutureValueKrw() > result.principalKrw());
        assertTrue(result.cashLike().realFutureValueKrw() < result.principalKrw());
        assertEquals(-0.49, result.cashLike().annualRealReturnPct(), 0.01);
        assertTrue(result.productiveAsset().realFutureValueKrw() > result.principalKrw());
        assertTrue(result.productiveAssetRealAdvantageKrw() > 0);
        assertTrue(result.methodology().contains("실질 미래가치"));
    }

    @Test
    void rejectsRatesThatWouldMakeCompoundingUndefined() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.evaluate(100_000_000L, 30, -100, 2.5, 7));
    }
}
