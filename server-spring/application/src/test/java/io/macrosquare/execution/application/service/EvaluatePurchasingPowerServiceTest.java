package io.macrosquare.execution.application.service;

import io.macrosquare.execution.domain.service.PurchasingPowerPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluatePurchasingPowerServiceTest {

    @Test
    void delegatesTheScenarioToThePureDomainPolicy() {
        var service = new EvaluatePurchasingPowerService(new PurchasingPowerPolicy());

        var result = service.evaluate(100_000_000L, 30, 3, 2.5, 7);

        assertEquals(100_000_000L, result.principalKrw());
        assertEquals(30, result.years());
        assertEquals("CASH_LIKE", result.cashLike().key());
        assertEquals("PRODUCTIVE_ASSET", result.productiveAsset().key());
    }
}
