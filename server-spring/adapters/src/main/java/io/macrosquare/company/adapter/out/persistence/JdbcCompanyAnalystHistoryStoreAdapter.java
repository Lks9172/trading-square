package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystHistoryPersistenceException;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistoryStorePort;
import io.macrosquare.company.application.port.out.SaveCompanyAnalystHistoryPort;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.shared.adapter.out.persistence.PostgresTemporal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Transactional PostgreSQL store for daily analyst revision history. */
public final class JdbcCompanyAnalystHistoryStoreAdapter
        implements LoadCompanyAnalystHistoryStorePort, SaveCompanyAnalystHistoryPort {

    private static final Pattern SAFE_TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,19}");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcCompanyAnalystHistoryStoreAdapter(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public Optional<List<CompanyAnalystHistoryPoint>> load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        try {
            var exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from company.analyst_series_state where ticker = :ticker)",
                    new MapSqlParameterSource("ticker", ticker), Boolean.class));
            if (!exists) return Optional.empty();
            return Optional.of(jdbc.query("""
                    select observed_on, analyst_score, upside_pct,
                           eps_revision_7d_pct, eps_revision_30d_pct, eps_revision_90d_pct
                    from company.analyst_snapshot
                    where ticker = :ticker
                    order by observed_on
                    """, new MapSqlParameterSource("ticker", ticker), (row, ignored) ->
                    new CompanyAnalystHistoryPoint(
                            row.getObject("observed_on", java.time.LocalDate.class),
                            nullableDouble(row, "analyst_score"),
                            nullableDouble(row, "upside_pct"),
                            nullableDouble(row, "eps_revision_7d_pct"),
                            nullableDouble(row, "eps_revision_30d_pct"),
                            nullableDouble(row, "eps_revision_90d_pct")
                    )));
        } catch (RuntimeException error) {
            throw new CompanyAnalystHistoryPersistenceException("Unable to load analyst history", error);
        }
    }

    @Override
    public void save(
            String normalizedTicker,
            List<CompanyAnalystHistoryPoint> history,
            java.time.Instant updatedAt
    ) {
        var ticker = normalizeTicker(normalizedTicker);
        var copy = List.copyOf(Objects.requireNonNull(history, "history"));
        Objects.requireNonNull(updatedAt, "updatedAt");
        try {
            transactions.executeWithoutResult(ignored -> {
                jdbc.update("""
                        insert into company.analyst_series_state (ticker, updated_at)
                        values (:ticker, :updatedAt)
                        on conflict (ticker) do update set updated_at = excluded.updated_at
                        """, new MapSqlParameterSource("ticker", ticker)
                                .addValue("updatedAt", PostgresTemporal.timestamp(updatedAt)));
                jdbc.update("delete from company.analyst_snapshot where ticker = :ticker",
                        new MapSqlParameterSource("ticker", ticker));
                if (!copy.isEmpty()) {
                    var batch = copy.stream().map(point -> {
                        Objects.requireNonNull(point, "history point");
                        return new MapSqlParameterSource()
                                .addValue("ticker", ticker)
                                .addValue("observedOn", point.date())
                                .addValue("analystScore", point.analystScore())
                                .addValue("upsidePct", point.upsidePct())
                                .addValue("epsRevision7dPct", point.epsEstimateRevision7dPct())
                                .addValue("epsRevision30dPct", point.epsEstimateRevision30dPct())
                                .addValue("epsRevision90dPct", point.epsEstimateRevision90dPct())
                                .addValue("collectedAt", PostgresTemporal.timestamp(updatedAt));
                    }).toArray(MapSqlParameterSource[]::new);
                    jdbc.batchUpdate("""
                            insert into company.analyst_snapshot (
                                ticker, observed_on, analyst_score, upside_pct,
                                eps_revision_7d_pct, eps_revision_30d_pct, eps_revision_90d_pct,
                                collected_at
                            ) values (
                                :ticker, :observedOn, :analystScore, :upsidePct,
                                :epsRevision7dPct, :epsRevision30dPct, :epsRevision90dPct,
                                :collectedAt
                            )
                            """, batch);
                }
            });
        } catch (RuntimeException error) {
            throw new CompanyAnalystHistoryPersistenceException("Unable to persist analyst history", error);
        }
    }

    private static Double nullableDouble(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getObject(column, Double.class);
        return row.wasNull() ? null : value;
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        var normalized = ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
        if (!SAFE_TICKER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("ticker contains unsupported characters");
        }
        return normalized;
    }
}
