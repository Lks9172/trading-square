package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.application.model.MarketCollectionStatus;
import io.macrosquare.market.application.port.out.MarketCollectionStatusRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PostgreSQL last-attempt ledger for market collectors. */
public final class JdbcMarketCollectionStatusRepository implements MarketCollectionStatusRepository {

    private static final String UPSERT = """
            insert into market.collection_status (
                source, status, attempted_at, completed_at, collected_count,
                persisted_count, failure_keys, failure_type
            ) values (
                :source, :status, :attemptedAt, :completedAt, :collected,
                :persisted, :failureKeys, :failureType
            )
            on conflict (source) do update set
                status = excluded.status,
                attempted_at = excluded.attempted_at,
                completed_at = excluded.completed_at,
                collected_count = excluded.collected_count,
                persisted_count = excluded.persisted_count,
                failure_keys = excluded.failure_keys,
                failure_type = excluded.failure_type
            where excluded.completed_at >= market.collection_status.completed_at
            """;

    private static final String LOAD = """
            select source, status, attempted_at, completed_at, collected_count,
                   persisted_count, failure_keys, failure_type
            from market.collection_status
            order by source
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMarketCollectionStatusRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void save(MarketCollectionStatus value) {
        Objects.requireNonNull(value);
        jdbc.update(UPSERT, new MapSqlParameterSource()
                .addValue("source", value.source().name())
                .addValue("status", value.state().name())
                .addValue("attemptedAt", Timestamp.from(value.attemptedAt()))
                .addValue("completedAt", Timestamp.from(value.completedAt()))
                .addValue("collected", value.collected())
                .addValue("persisted", value.persisted())
                .addValue("failureKeys", String.join(",", value.failureKeys()))
                .addValue("failureType", value.failureType()));
    }

    @Override
    public Map<MarketDataSource, MarketCollectionStatus> loadLatest() {
        var result = new EnumMap<MarketDataSource, MarketCollectionStatus>(MarketDataSource.class);
        jdbc.query(LOAD, (row, ignored) -> {
            var source = MarketDataSource.valueOf(row.getString("source"));
            result.put(source, new MarketCollectionStatus(
                    source,
                    MarketCollectionStatus.State.valueOf(row.getString("status")),
                    row.getTimestamp("attempted_at").toInstant(),
                    row.getTimestamp("completed_at").toInstant(),
                    row.getInt("collected_count"),
                    row.getInt("persisted_count"),
                    failureKeys(row.getString("failure_keys")),
                    row.getString("failure_type")
            ));
            return null;
        });
        return Map.copyOf(result);
    }

    private static List<String> failureKeys(String source) {
        if (source == null || source.isBlank()) return List.of();
        return Arrays.stream(source.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }
}
