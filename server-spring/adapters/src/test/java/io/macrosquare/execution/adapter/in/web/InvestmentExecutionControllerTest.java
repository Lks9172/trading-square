package io.macrosquare.execution.adapter.in.web;

import io.macrosquare.execution.application.model.InvestmentPlanPatch;
import io.macrosquare.execution.application.model.TradeLogCommand;
import io.macrosquare.execution.application.port.in.ManageInvestmentExecutionUseCase;
import io.macrosquare.execution.domain.model.AssetTrancheSummary;
import io.macrosquare.execution.domain.model.InvestmentHorizon;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.execution.domain.model.TradeLogEntry;
import io.macrosquare.execution.domain.model.TradeLogKind;
import io.macrosquare.execution.domain.model.TrancheEntry;
import io.macrosquare.execution.domain.service.PortfolioAllocationPolicy;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvestmentExecutionControllerTest {

    @Test
    void preservesPlanPatchAndExecutionRouteContracts() throws Exception {
        var stub = new StubUseCase();
        var mvc = mvc(stub);

        mvc.perform(get("/api/plan"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"plan":{"horizon":"medium","targetReturnAnnualPct":12,"maxDrawdownTolerancePct":25,
                        "rebalanceIntervalDays":90,"leverageMaxPct":15,"profitTakeTargetPct":25,"stopLossPct":15,
                        "monthlyDCA_KRW":1000000,"updatedAt":"2026-07-20T00:00:00Z"}}
                        """, JsonCompareMode.STRICT));

        mvc.perform(post("/api/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"horizon\":\"long\",\"notes\":\"discipline\"}"))
                .andExpect(status().isOk());
        assertEquals(InvestmentHorizon.LONG, stub.observedPatch.horizon().value());
        assertEquals("discipline", stub.observedPatch.notes().value());

        mvc.perform(post("/api/execution-plan/tranche")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asset\":\"NASDAQ\",\"stage\":1}"))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"entry":{"asset":"NASDAQ","stage":1,"executedAt":"2026-07-20T00:00:00Z",
                        "priceAtEntry":25500,"regimeAtEntry":"RISK_ON","weightPct":30},"total":1}
                        """, JsonCompareMode.STRICT));

        mvc.perform(get("/api/execution-plan/tranche"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"entries":[],"summary":[{"asset":"NASDAQ","executedStages":[1],"nextStage":2,
                        "latestRegime":"RISK_ON","latestExecutedAt":"2026-07-20T00:00:00Z"}]}
                        """, JsonCompareMode.STRICT));

        mvc.perform(delete("/api/execution-plan/tranche/NASDAQ"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"asset\":\"NASDAQ\",\"remainingTotal\":0}", JsonCompareMode.STRICT));
    }

    @Test
    void mapsNestedTradeContextAndRejectsInvalidPayloads() throws Exception {
        var stub = new StubUseCase();
        var mvc = mvc(stub);

        mvc.perform(post("/api/trade-log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"user_action","asset":"NASDAQ","to":"SELL",
                                "context":{"reason":"manual","nested":{"score":75}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"ok\":true,\"againstSystemRecommendation\":true}", JsonCompareMode.STRICT));
        assertEquals(TradeLogKind.USER_ACTION, stub.observedTrade.kind());

        mvc.perform(post("/api/trade-log")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"kind required\"}", JsonCompareMode.STRICT));

        mvc.perform(post("/api/execution-plan/tranche")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"asset\":\"UNKNOWN\",\"stage\":1}"))
                .andExpect(status().isBadRequest());
    }

    private static MockMvc mvc(ManageInvestmentExecutionUseCase useCase) {
        return MockMvcBuilders.standaloneSetup(new InvestmentExecutionController(
                        useCase, new PortfolioAllocationPolicy()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static final class StubUseCase implements ManageInvestmentExecutionUseCase {
        private InvestmentPlanPatch observedPatch;
        private TradeLogCommand observedTrade;

        @Override
        public InvestmentPlan investmentPlan() {
            return InvestmentPlan.defaults(Instant.parse("2026-07-20T00:00:00Z"));
        }

        @Override
        public InvestmentPlan updateInvestmentPlan(InvestmentPlanPatch patch) {
            observedPatch = patch;
            return new InvestmentPlan(
                    patch.horizon().value(), 12, 25, 90, 15, 25, 15, 1_000_000,
                    null, null, null, null, null, null, null, null, null, patch.notes().value(),
                    Instant.parse("2026-07-20T00:00:00Z")
            );
        }

        @Override
        public TrancheWriteResult recordTranche(String asset, int stage, Double priceAtEntry) {
            return new TrancheWriteResult(new TrancheEntry(
                    asset, stage, Instant.parse("2026-07-20T00:00:00Z"), 25_500d, "RISK_ON", 30d
            ), 1);
        }

        @Override
        public TrancheBook trancheBook() {
            return new TrancheBook(List.of(), List.of(new AssetTrancheSummary(
                    "NASDAQ", List.of(1), 2, "RISK_ON", Instant.parse("2026-07-20T00:00:00Z")
            )));
        }

        @Override
        public int clearTranches(String asset) {
            return 0;
        }

        @Override
        public List<TradeLogEntry> recentTradeLog(int limit) {
            return List.of();
        }

        @Override
        public TradeLogWriteResult appendTradeLog(TradeLogCommand command) {
            observedTrade = command;
            return new TradeLogWriteResult(true);
        }
    }
}
