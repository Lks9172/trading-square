package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyReadModels.SearchItem;
import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanyReadModels.Summary;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.port.in.CompanyTickerNotFoundException;
import io.macrosquare.company.application.port.in.QueryCompanyReadUseCase;
import io.macrosquare.company.application.port.out.CompanyReadUnavailableException;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyReadControllerTest {

    @Test
    void preservesTheLegacySearchContractAndFieldOrder() throws Exception {
        var mvc = mvc(new StubUseCase() {
            @Override
            public SearchResult search(String query, int requestedLimit) {
                return new SearchResult(List.of(new SearchItem("NVDA", "0001045810", "NVIDIA CORP")));
            }
        });

        mvc.perform(get("/api/company-search").param("q", "NVDA").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "{\"items\":[{\"ticker\":\"NVDA\",\"cik\":\"0001045810\",\"title\":\"NVIDIA CORP\"}]}"
                ));
    }

    @Test
    void emulatesTheLegacyJavascriptLimitParsing() throws Exception {
        var observed = new AtomicInteger();
        var mvc = mvc(new StubUseCase() {
            @Override
            public SearchResult search(String query, int requestedLimit) {
                observed.set(requestedLimit);
                return new SearchResult(List.of());
            }
        });

        mvc.perform(get("/api/company-search").param("q", "A").param("limit", "0"))
                .andExpect(status().isOk());
        assertEquals(8, observed.get());
        mvc.perform(get("/api/company-search").param("q", "A").param("limit", "999"))
                .andExpect(status().isOk());
        assertEquals(12, observed.get());
        mvc.perform(get("/api/company-search").param("q", "A").param("limit", "-2"))
                .andExpect(status().isOk());
        assertEquals(1, observed.get());
        mvc.perform(get("/api/company-search").param("q", "A").param("limit", "8abc"))
                .andExpect(status().isOk());
        assertEquals(8, observed.get());
        mvc.perform(get("/api/company-search").param("q", "A").param("limit", "bad"))
                .andExpect(status().isOk());
        assertEquals(8, observed.get());
    }

    @Test
    void preservesSummaryNullsNumbersAndTickerQueryOrder() throws Exception {
        var observed = new AtomicReference<List<String>>();
        var mvc = mvc(new StubUseCase() {
            @Override
            public SummaryResult summaries(List<String> tickers) {
                observed.set(tickers);
                return new SummaryResult(List.of(new Summary(
                        "NVDA", "NVIDIA CORP", 64, 64, "선별 접근",
                        new BigDecimal("61.403298350824585"), null, null,
                        42, 67, "바닥 시도", 64, 52, 55, 52
                )));
            }
        });

        mvc.perform(get("/api/company-summaries").param("tickers", "nvda,MSFT"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "{\"items\":[{\"ticker\":\"NVDA\",\"name\":\"NVIDIA CORP\",\"totalScore\":64,"
                                + "\"buyScore\":64,\"buyLabel\":\"선별 접근\",\"revenueGrowthYoY\":61.403298350824585,"
                                + "\"operatingMargin\":null,\"evToSales\":null,\"crowdingScore\":42,\"appealScore\":67,"
                                + "\"bottomState\":\"바닥 시도\",\"earningsBottomScore\":64,\"priceBottomScore\":52,"
                                + "\"volumeConfirmationScore\":55,\"failureRiskScore\":52}]}"
                ));
        assertEquals(List.of("nvda", "MSFT"), observed.get());
    }

    @Test
    void preservesEmptyAndUnavailableResponses() throws Exception {
        var emptyMvc = mvc(new StubUseCase());
        emptyMvc.perform(get("/api/company-search"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"items\":[]}"));
        emptyMvc.perform(get("/api/company-summaries"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"items\":[]}"));

        var unavailableMvc = mvc(new StubUseCase() {
            @Override
            public SearchResult search(String query, int requestedLimit) {
                throw new CompanyReadUnavailableException("upstream failed");
            }
        });
        unavailableMvc.perform(get("/api/company-search").param("q", "NVDA"))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("{\"error\":\"Legacy company data is temporarily unavailable\"}"));
    }

    @Test
    void preservesTheFullResearchTopLevelOrderAndNestedDocumentValues() throws Exception {
        var observed = new AtomicReference<String>();
        var mvc = mvc(new StubUseCase() {
            @Override
            public Research detail(String ticker) {
                observed.set(ticker);
                return researchFixture();
            }
        });

        mvc.perform(get("/api/company/nvda"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(
                        "{\"profile\":{\"ticker\":\"NVDA\",\"cik\":\"0001045810\"},"
                                + "\"quote\":{\"price\":173.42,\"date\":null},\"financials\":{},"
                                + "\"score\":{},\"buyScore\":{},\"filings\":[],\"irMaterials\":[],"
                                + "\"highlights\":[\"quality\"],\"peerGroup\":\"semiconductor\","
                                + "\"bottleneck\":null,\"narrative\":null,\"capitalFlow\":{},"
                                + "\"cashFlowQuality\":{},\"multipleInsight\":{},\"guidanceInsight\":null,"
                                + "\"timeframeView\":{},\"correctionAssessment\":{},\"thesisMonitor\":{},"
                                + "\"reversalConfirmation\":{},\"sectorContext\":{},\"verdicts\":{},"
                                + "\"bottomSignal\":{},\"positionSizing\":{},\"executionBridge\":{},\"peers\":[]}"
                ));
        assertEquals("nvda", observed.get());
    }

    @Test
    void mapsUnknownCompanyToTheLegacy404Contract() throws Exception {
        var mvc = mvc(new StubUseCase() {
            @Override
            public Research detail(String ticker) {
                throw new CompanyTickerNotFoundException("NOTREAL");
            }
        });

        mvc.perform(get("/api/company/NOTREAL"))
                .andExpect(status().isNotFound())
                .andExpect(content().string("{\"error\":\"SEC ticker mapping not found for NOTREAL\"}"));
    }

    private static MockMvc mvc(QueryCompanyReadUseCase useCase) {
        return MockMvcBuilders.standaloneSetup(new CompanyReadController(useCase, new ObjectMapper()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Research researchFixture() {
        var profile = new LinkedHashMap<String, io.macrosquare.company.application.model.CompanyReadModels.StructuredValue>();
        profile.put("ticker", new TextValue("NVDA"));
        profile.put("cik", new TextValue("0001045810"));
        var quote = new LinkedHashMap<String, io.macrosquare.company.application.model.CompanyReadModels.StructuredValue>();
        quote.put("price", new NumberValue(new BigDecimal("173.42")));
        quote.put("date", NullValue.INSTANCE);
        var emptyObject = new ObjectValue(new LinkedHashMap<>());
        var emptyArray = new ArrayValue(List.of());
        return new Research(
                new ObjectValue(profile), new ObjectValue(quote), emptyObject, emptyObject, emptyObject,
                emptyArray, emptyArray, new ArrayValue(List.of(new TextValue("quality"))),
                new TextValue("semiconductor"), NullValue.INSTANCE, NullValue.INSTANCE,
                emptyObject, emptyObject, emptyObject, NullValue.INSTANCE, emptyObject, emptyObject,
                emptyObject, emptyObject, emptyObject, emptyObject, emptyObject, emptyObject, emptyObject,
                emptyArray
        );
    }

    private static class StubUseCase implements QueryCompanyReadUseCase {
        @Override
        public SearchResult search(String query, int requestedLimit) {
            return new SearchResult(List.of());
        }

        @Override
        public SummaryResult summaries(List<String> tickers) {
            return new SummaryResult(List.of());
        }

        @Override
        public Research detail(String ticker) {
            throw new UnsupportedOperationException();
        }
    }
}
