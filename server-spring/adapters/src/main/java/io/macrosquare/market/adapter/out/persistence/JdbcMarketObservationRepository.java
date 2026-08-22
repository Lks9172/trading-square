package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.application.port.out.MarketObservationPersistenceException;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Date;
import java.util.List;
import java.util.Objects;

/** PostgreSQL time-series adapter with idempotent source/key/date upserts. */
public final class JdbcMarketObservationRepository implements MarketObservationRepository {

    private static final String UPSERT = """
            insert into market.observation (
                source, series_key, provider_code, observed_on, value, collected_at
            ) values (
                :source, :seriesKey, :providerCode, :observedOn, :value, clock_timestamp()
            )
            on conflict (source, series_key, observed_on) do update set
                provider_code = excluded.provider_code,
                value = excluded.value,
                collected_at = excluded.collected_at
            """;

    private static final String LATEST = """
            select distinct on (series_key)
                series_key, provider_code, value, observed_on
            from market.observation
            where source = :source
            order by series_key, observed_on desc
            """;

    private static final String HISTORY = """
            select series_key, provider_code, value, observed_on
            from market.observation
            where source = :source and series_key = :seriesKey
            order by observed_on
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMarketObservationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public int save(List<MarketObservation> observations) {
        if (observations == null || observations.isEmpty()) return 0;
        try {
            var batches = observations.stream().map(value -> {
                Objects.requireNonNull(value, "observation");
                return new MapSqlParameterSource()
                        .addValue("source", value.source().name())
                        .addValue("seriesKey", value.key())
                        .addValue("providerCode", value.providerCode())
                        .addValue("observedOn", Date.valueOf(value.observationDate()))
                        .addValue("value", value.value());
            }).toArray(MapSqlParameterSource[]::new);
            jdbc.batchUpdate(UPSERT, batches);
            return observations.size();
        } catch (RuntimeException error) {
            throw new MarketObservationPersistenceException("Unable to persist market observations", error);
        }
    }

    @Override
    public List<MarketObservation> loadLatest(MarketDataSource source) {
        Objects.requireNonNull(source);
        return query(LATEST, new MapSqlParameterSource("source", source.name()), source);
    }

    @Override
    public List<MarketObservation> loadHistory(MarketDataSource source, String key) {
        Objects.requireNonNull(source);
        if (key == null || key.isBlank()) throw new IllegalArgumentException("market observation key is required");
        return query(HISTORY, new MapSqlParameterSource()
                .addValue("source", source.name()).addValue("seriesKey", key), source);
    }

    private List<MarketObservation> query(String sql, MapSqlParameterSource parameters, MarketDataSource source) {
        try {
            return jdbc.query(sql, parameters, (row, ignored) -> new MarketObservation(
                    row.getString("series_key"),
                    row.getString("provider_code"),
                    row.getDouble("value"),
                    row.getObject("observed_on", java.time.LocalDate.class),
                    source
            ));
        } catch (RuntimeException error) {
            throw new MarketObservationPersistenceException("Unable to load market observations", error);
        }
    }
}
