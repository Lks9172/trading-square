package io.macrosquare.crypto.adapter.in.web;

import io.macrosquare.crypto.CryptoResearchFixture;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;
import io.macrosquare.crypto.application.port.in.CryptoSymbolNotFoundException;
import io.macrosquare.crypto.application.port.in.QueryCryptoResearchUseCase;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CryptoResearchControllerTest {

    @Test
    void servesTheCompleteCatalogProjection() throws Exception {
        var mvc = mvc(new QueryCryptoResearchUseCase() {
            @Override
            public Catalog catalog() {
                return CryptoResearchFixture.catalog();
            }

            @Override
            public Research detail(String symbol) {
                return CryptoResearchFixture.research(true);
            }
        });

        mvc.perform(get("/api/research/crypto"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].profile.symbol").value("BTC"))
                .andExpect(jsonPath("$.items[0].onchain.tvlUsd").value(5328263349.336395))
                .andExpect(jsonPath("$.items[0].trendCharts.btcDominanceProxy30d[0].value").value(56.5))
                .andExpect(jsonPath("$.items[0].bottomSignal.confirmedBottom.state").value("미충족"))
                .andExpect(jsonPath("$.items[0].executionBridge.action").value("SCALE_IN"))
                .andExpect(jsonPath("$.marketRegime.regime").value("RISK_ON"))
                .andExpect(jsonPath("$.assets[0].narrativeTheme").value("디지털 금"));
    }

    @Test
    void preservesNullExecutionBridgeAndBottomChartFields() throws Exception {
        var mvc = mvc(new QueryCryptoResearchUseCase() {
            @Override
            public Catalog catalog() {
                return CryptoResearchFixture.catalog();
            }

            @Override
            public Research detail(String symbol) {
                return CryptoResearchFixture.research(false);
            }
        });

        mvc.perform(get("/api/research/crypto/xrp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionBridge").isEmpty())
                .andExpect(jsonPath("$.bottomSignal.chart.markers[0].kind").value("current"))
                .andExpect(jsonPath("$.bottomSignal.chart.markers[0].value").value(64653.91))
                .andExpect(jsonPath("$.bottomSignal.failureSignals[0]").value("거래량 확인 부족"));
    }

    @Test
    void preservesTheLegacyNotFoundBody() throws Exception {
        var mvc = mvc(new QueryCryptoResearchUseCase() {
            @Override
            public Catalog catalog() {
                return CryptoResearchFixture.catalog();
            }

            @Override
            public Research detail(String symbol) {
                throw new CryptoSymbolNotFoundException("NOTREAL");
            }
        });

        mvc.perform(get("/api/research/crypto/NOTREAL"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("{\"error\":\"crypto symbol not found: NOTREAL\"}"));
    }

    private static MockMvc mvc(QueryCryptoResearchUseCase useCase) {
        return MockMvcBuilders.standaloneSetup(new CryptoResearchController(useCase, new ObjectMapper()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }
}
