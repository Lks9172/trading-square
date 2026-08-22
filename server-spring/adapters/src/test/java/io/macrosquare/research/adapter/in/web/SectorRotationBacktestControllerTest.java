package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.domain.rotation.SectorWalkForwardBacktest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SectorRotationBacktestControllerTest {

    @Test
    void exposesMatchedScopeAndRefusesToCallTheWholeForecastValidated() throws Exception {
        var horizon = new SectorWalkForwardBacktest.HorizonResult(
                1, 12, 50, 58.33, .1, .2, 0, .1,
                75, 80, 55, 60, .2, .3, 25, 75, 0, 25, 75);
        var threeMonth = new SectorWalkForwardBacktest.HorizonResult(
                3, 12, 55, 58.33, .3, .2, .1, .1,
                75, 80, 55, 60, .2, .3, 30, 78, 2, 25, 82);
        var sixMonth = new SectorWalkForwardBacktest.HorizonResult(
                6, 12, 60, 58.33, .6, .2, .2, .1,
                75, 80, 55, 60, .2, .3, 35, 82, 5, 30, 86);
        var horizons = Map.of(
                "oneMonth", horizon, "threeMonth", threeMonth, "sixMonth", sixMonth);
        var result = new SectorWalkForwardBacktest(
                "CURRENT_TOTAL_RETURN_RISK_ADJUSTED_MOMENTUM_WALK_FORWARD_V2",
                LocalDate.parse("2025-01-31"), LocalDate.parse("2025-12-31"), 12,
                horizons, horizons, 25, List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new SectorRotationBacktestController(years -> result)).build();

        mvc.perform(get("/api/research/sectors/backtest/current").param("years", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methodology.dataBasis").value("ADJUSTED_CLOSE_TOTAL_RETURN"))
                .andExpect(jsonPath("$.methodology.liveRelativeStrengthLayerMatched").value(true))
                .andExpect(jsonPath("$.methodology.fullRotationForecastValidated").value(false))
                .andExpect(jsonPath("$.methodology.methodologyOrigin")
                        .value("PREDEFINED_INSTITUTIONAL_MOMENTUM_PROXY"))
                .andExpect(jsonPath("$.comparisonBaseline.compatibility")
                        .value("COMPARISON_ONLY_NOT_LIVE"))
                .andExpect(jsonPath("$.comparisonBaseline.assessment.status").value("IMPROVED"))
                .andExpect(jsonPath("$.summary.oneMonth.sampleCount").value(12))
                .andExpect(jsonPath("$.summary.sixMonth.overlapAdjustmentLagMonths").value(5))
                .andExpect(jsonPath("$.summary.sixMonth.top1HitRateOverlapAdjusted95LowerPct").value(30));
    }
}
