package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.company.application.model.CompanyMacdTimingSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcCompanyResearchSummaryRepositoryTest {

    @Test
    void hidesOlderCalculationContractsAndStampsCurrentDecisionVersionOnWrite() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(any(String.class), any(SqlParameterSource.class))).thenReturn(1);
        var repository = new JdbcCompanyResearchSummaryRepository(jdbc, new ObjectMapper());

        repository.findAll();
        repository.save(snapshot());

        verify(jdbc).query(
                contains("where calculation_version = :calculationVersion"),
                (SqlParameterSource) argThat(parameters -> Integer.valueOf(6)
                        .equals(((SqlParameterSource) parameters).getValue("calculationVersion"))),
                any(RowCallbackHandler.class)
        );
        verify(jdbc).update(
                contains("price_signal_reasons, macd_timing, execution_action"),
                (SqlParameterSource) argThat(parameters -> {
                    var source = (SqlParameterSource) parameters;
                    assertEquals(6, source.getValue("calculationVersion"));
                    assertEquals("BUY", source.getValue("executionAction"));
                    assertEquals("STRONG", source.getValue("reversalStatus"));
                    assertEquals(86, source.getValue("reversalScore"));
                    return true;
                })
        );
    }

    private static CompanyResearchSummarySnapshot snapshot() {
        return new CompanyResearchSummarySnapshot(
                "TEST", LocalDate.parse("2026-06-30"), 100_000.0,
                10.0, 20.0, 5.0, 80, 75, 82, 70, 85,
                78, "매수 우호", 80, 25, "INDEPENDENT_MARKET_CAP", true, List.of(),
                "CURRENT", LocalDate.parse("2026-06-30"), LocalDate.parse("2026-07-30"),
                "10-Q", 0, List.of(), 72, 75, 20, 80, "CONVICTION", "BUY",
                Instant.parse("2026-08-07T00:00:00Z")
        ).withPriceSignals(
                72, 75, 20, 80, "CONVICTION", LocalDate.parse("2026-08-05"),
                "STRONG", 86, List.of("current price evidence"),
                macdTiming(),
                Instant.parse("2026-08-07T00:00:00Z")
        );
    }

    private static CompanyMacdTimingSnapshot macdTiming() {
        var daily = new CompanyMacdTimingSnapshot.Timeframe(
                LocalDate.parse("2026-08-06"), "ABOVE_SIGNAL", "BULLISH_CROSS",
                LocalDate.parse("2026-08-04"), 2, "EXPANDING_POSITIVE", "NONE",
                null, null, false);
        return new CompanyMacdTimingSnapshot(daily, daily, true);
    }
}
