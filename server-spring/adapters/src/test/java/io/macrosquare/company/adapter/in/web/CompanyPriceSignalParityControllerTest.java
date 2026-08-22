package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot;
import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot.ChartMarker;
import io.macrosquare.company.application.model.CompanyPriceSignalSnapshot.PriceHistorySummary;
import io.macrosquare.company.application.port.in.CompanyPriceSignalParityReport;
import io.macrosquare.company.application.port.out.CompanyPriceHistoryUnavailableException;
import io.macrosquare.company.domain.bottom.BottomActionBias;
import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import io.macrosquare.company.domain.bottom.BottomPatternPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceContextPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceSignal;
import io.macrosquare.company.domain.bottom.BottomStructureState;
import io.macrosquare.company.domain.bottom.DeepBottomSignal;
import io.macrosquare.company.domain.bottom.DeepBottomState;
import io.macrosquare.company.domain.bottom.ReversalConfirmation;
import io.macrosquare.company.domain.bottom.ReversalConfirmationStatus;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyPriceSignalParityControllerTest {

    @Test
    void exposesDirectYahooHistoryAndEveryBottomSignalParityGate() throws Exception {
        var date = LocalDate.parse("2026-07-17");
        var point = new BottomPatternPoint(date, 202.81, 150_000_000.0);
        var context = new BottomPriceContextPolicy(new BottomPatternPolicy()).evaluate(List.of(point));
        var priceSignal = new BottomPriceSignal(65, 34, 58, 55, 52, 52, BottomStructureState.BOTTOM_ATTEMPT);
        var deepBottom = new DeepBottomSignal(
                56, DeepBottomState.UNMET, BottomActionBias.WAIT, date, 0, "summary",
                1.18, 1.0, -14.0, 0.3, -3.8, List.of("reason"), List.of("caution")
        );
        var reversal = new ReversalConfirmation(
                ReversalConfirmationStatus.OFF, 56, date, "reversal", List.of("reason"), List.of("caution")
        );
        var snapshot = new CompanyPriceSignalSnapshot(
                new PriceHistorySummary(1, date, 202.81, date, 202.81),
                List.of(new ChartMarker("current", date, 202.81)),
                priceSignal,
                deepBottom,
                reversal
        );
        var report = new CompanyPriceSignalParityReport(
                "NVDA", 380, true, true, true, true, true, true,
                List.of(), snapshot, snapshot, context
        );
        var mvc = MockMvcBuilders.standaloneSetup(
                new CompanyPriceSignalParityController(ticker -> report)
        ).build();

        mvc.perform(get("/internal/v1/migration/company-price-signal-parity/nvda"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("NVDA"))
                .andExpect(jsonPath("$.lookbackDays").value(380))
                .andExpect(jsonPath("$.allMatched").value(true))
                .andExpect(jsonPath("$.priceHistoryMatched").value(true))
                .andExpect(jsonPath("$.markersMatched").value(true))
                .andExpect(jsonPath("$.priceSignalMatched").value(true))
                .andExpect(jsonPath("$.confirmedBottomMatched").value(true))
                .andExpect(jsonPath("$.reversalConfirmationMatched").value(true))
                .andExpect(jsonPath("$.result.spring.priceSignal.volumeConfirmationScore").value(55))
                .andExpect(jsonPath("$.result.spring.confirmedBottom.state").value("UNMET"))
                .andExpect(jsonPath("$.result.spring.reversalConfirmation.status").value("OFF"))
                .andExpect(jsonPath("$.springContext.chartPoints[0].volume").value(150000000));
    }

    @Test
    void hidesYahooPriceHistoryFailureDetailsBehindTheSafeBadGateway() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                        new CompanyPriceSignalParityController(ticker -> {
                            throw new CompanyPriceHistoryUnavailableException("Yahoo internal details", null);
                        })
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/internal/v1/migration/company-price-signal-parity/NVDA"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Company research parity data is temporarily unavailable"));
    }
}
