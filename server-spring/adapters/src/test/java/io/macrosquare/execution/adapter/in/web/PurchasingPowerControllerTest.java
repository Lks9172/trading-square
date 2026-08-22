package io.macrosquare.execution.adapter.in.web;

import io.macrosquare.execution.application.port.in.EvaluatePurchasingPowerUseCase;
import io.macrosquare.execution.domain.service.PurchasingPowerPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PurchasingPowerControllerTest {

    @Test
    void exposesInputsAndInflationAdjustedScenarios() throws Exception {
        EvaluatePurchasingPowerUseCase useCase = mock(EvaluatePurchasingPowerUseCase.class);
        var projection = new PurchasingPowerPolicy().evaluate(100_000_000L, 30, 3, 2.5, 7);
        when(useCase.evaluate(100_000_000L, 30, 3, 2.5, 7)).thenReturn(projection);
        MockMvc mvc = standaloneSetup(new PurchasingPowerController(useCase)).build();

        mvc.perform(get("/api/execution-plan/purchasing-power")
                        .queryParam("principalKrw", "100000000")
                        .queryParam("years", "30")
                        .queryParam("inflationPct", "3")
                        .queryParam("cashYieldPct", "2.5")
                        .queryParam("productiveAssetReturnPct", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projection.principalKrw").value(100_000_000L))
                .andExpect(jsonPath("$.projection.cashLike.annualRealReturnPct").value(-0.49))
                .andExpect(jsonPath("$.projection.productiveAsset.purchasingPowerRetentionPct").isNumber());

        verify(useCase).evaluate(100_000_000L, 30, 3, 2.5, 7);
    }
}
