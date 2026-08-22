package io.macrosquare.execution.adapter.out.persistence;

import io.macrosquare.execution.domain.model.InvestmentPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcInvestmentExecutionAdapterTest {

    @Test
    void locksAndUpdatesThePlanInsideOneTransaction() {
        var fixture = fixture();
        var before = InvestmentPlan.defaults(Instant.parse("2026-07-20T00:00:00Z"));
        when(fixture.jdbc.query(
                eq("select * from execution.investment_plan where singleton_id = 1 for update"),
                any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<InvestmentPlan>>any()
        )).thenReturn(List.of(before));

        var result = fixture.adapter.updateAtomically(
                before,
                current -> withNotes(current, "atomic")
        );

        assertEquals(before, result.before());
        assertEquals("atomic", result.after().notes());
        verify(fixture.jdbc).query(
                eq("select pg_advisory_xact_lock(:namespace, :aggregate)"),
                any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Integer>>any()
        );
        verify(fixture.jdbc).query(
                eq("select * from execution.investment_plan where singleton_id = 1 for update"),
                any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<InvestmentPlan>>any()
        );
        verify(fixture.jdbc).update(contains("on conflict (singleton_id) do update"),
                any(SqlParameterSource.class));
    }

    @Test
    void preservesApplicationValidationFailuresAndDoesNotWrite() {
        var fixture = fixture();
        var before = InvestmentPlan.defaults(Instant.parse("2026-07-20T00:00:00Z"));
        when(fixture.jdbc.query(
                eq("select * from execution.investment_plan where singleton_id = 1 for update"),
                any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<InvestmentPlan>>any()
        )).thenReturn(List.of(before));
        var rejection = new IllegalArgumentException("invalid patch");

        var thrown = assertThrows(IllegalArgumentException.class,
                () -> fixture.adapter.updateAtomically(before, ignored -> { throw rejection; }));

        assertSame(rejection, thrown);
        verify(fixture.jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    private static Fixture fixture() {
        var jdbc = mock(NamedParameterJdbcOperations.class);
        var status = mock(TransactionStatus.class);
        TransactionOperations transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                return callback.doInTransaction(status);
            }
        };
        when(jdbc.query(
                eq("select pg_advisory_xact_lock(:namespace, :aggregate)"),
                any(SqlParameterSource.class),
                ArgumentMatchers.<RowMapper<Integer>>any()
        )).thenReturn(List.of(0));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        return new Fixture(
                jdbc,
                new JdbcInvestmentExecutionAdapter(jdbc, transactions, new ObjectMapper())
        );
    }

    private static InvestmentPlan withNotes(InvestmentPlan current, String notes) {
        return new InvestmentPlan(
                current.horizon(),
                current.targetReturnAnnualPct(),
                current.maxDrawdownTolerancePct(),
                current.rebalanceIntervalDays(),
                current.leverageMaxPct(),
                current.profitTakeTargetPct(),
                current.stopLossPct(),
                current.monthlyDcaKrw(),
                current.currentHoldings(),
                current.totalCapitalKrw(),
                current.totalCapitalUsd(),
                current.currentHoldingsUsd(),
                current.accountStartDate(),
                current.startingCapitalUsd(),
                current.startingCapitalKrw(),
                current.investmentExperienceYears(),
                current.accountType(),
                notes,
                current.updatedAt()
        );
    }

    private record Fixture(
            NamedParameterJdbcOperations jdbc,
            JdbcInvestmentExecutionAdapter adapter
    ) {
    }
}
