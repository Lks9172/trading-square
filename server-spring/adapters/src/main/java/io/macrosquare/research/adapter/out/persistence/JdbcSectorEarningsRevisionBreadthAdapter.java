package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.application.port.out.LoadSectorEarningsRevisionBreadthPort;
import io.macrosquare.research.domain.rotation.SectorEarningsRevisionBreadth;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Read-only ACL from company-owned analyst snapshots to research breadth evidence. */
public final class JdbcSectorEarningsRevisionBreadthAdapter
        implements LoadSectorEarningsRevisionBreadthPort {

    private static final double UNCHANGED_BAND_PCT = 0.10;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSectorEarningsRevisionBreadthAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public Optional<SectorEarningsRevisionBreadth> load(
            String sectorKey,
            List<String> normalizedTickers,
            LocalDate asOfDate,
            int maxAgeDays
    ) {
        if (sectorKey == null || sectorKey.isBlank()) {
            throw new IllegalArgumentException("sectorKey is required");
        }
        Objects.requireNonNull(asOfDate, "asOfDate");
        if (maxAgeDays < 0) throw new IllegalArgumentException("maxAgeDays must not be negative");
        var tickers = normalizedTickers == null ? List.<String>of() : normalizedTickers.stream()
                .map(JdbcSectorEarningsRevisionBreadthAdapter::normalizeTicker)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        if (tickers.isEmpty()) return Optional.empty();

        var rows = jdbc.query("""
                select distinct on (ticker)
                       ticker, observed_on, eps_revision_30d_pct
                from company.analyst_snapshot
                where ticker in (:tickers)
                  and observed_on between :oldest and :asOf
                order by ticker, observed_on desc
                """, new MapSqlParameterSource()
                .addValue("tickers", tickers)
                .addValue("oldest", asOfDate.minusDays(maxAgeDays))
                .addValue("asOf", asOfDate), (row, ignored) -> new Observation(
                row.getObject("observed_on", LocalDate.class),
                nullableDouble(row, "eps_revision_30d_pct")));
        var covered = rows.stream().filter(row -> row.revision30dPct() != null).toList();
        if (covered.isEmpty()) return Optional.empty();
        var oldest = covered.stream().map(Observation::observedOn).min(LocalDate::compareTo).orElseThrow();
        var latest = covered.stream().map(Observation::observedOn).max(LocalDate::compareTo).orElseThrow();
        var up = (int) covered.stream().filter(row -> row.revision30dPct() > UNCHANGED_BAND_PCT).count();
        var down = (int) covered.stream().filter(row -> row.revision30dPct() < -UNCHANGED_BAND_PCT).count();
        return Optional.of(new SectorEarningsRevisionBreadth(
                asOfDate, oldest, latest, tickers.size(), covered.size(),
                up, down, covered.size() - up - down));
    }

    private static Double nullableDouble(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getObject(column, Double.class);
        return row.wasNull() ? null : value;
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        var value = ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
        if (!value.matches("[A-Z0-9][A-Z0-9.-]{0,19}")) {
            throw new IllegalArgumentException("ticker contains unsupported characters");
        }
        return value;
    }

    private record Observation(LocalDate observedOn, Double revision30dPct) {
    }
}
