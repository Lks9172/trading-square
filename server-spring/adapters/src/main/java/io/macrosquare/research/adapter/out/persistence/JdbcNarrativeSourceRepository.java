package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.application.port.out.NarrativeSourceRepository;
import io.macrosquare.research.domain.narrative.NarrativeSourceObservation;
import io.macrosquare.research.domain.narrative.NarrativeSourceQuality;
import io.macrosquare.research.domain.narrative.NarrativeSourceReading;
import io.macrosquare.research.domain.narrative.NarrativeSourceStatus;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public final class JdbcNarrativeSourceRepository implements NarrativeSourceRepository {

    private static final String LOCK = """
            select pg_advisory_xact_lock(hashtextextended(:lockKey, 0))
            """;
    private static final String LATEST_REVISION = """
            select revision, content_hash
            from research.narrative_source_observation
            where theme_id = :themeId and source_key = :sourceKey and observation_date = :observationDate
            order by revision desc
            limit 1
            """;
    private static final String INSERT = """
            insert into research.narrative_source_observation (
                theme_id, source_key, source_label, observation_date, revision,
                observed_at, quality, status, value, score, detail, source_url,
                content_hash, raw_object_key
            ) values (
                :themeId, :sourceKey, :sourceLabel, :observationDate, :revision,
                :observedAt, :quality, :status, :value, :score, :detail, :sourceUrl,
                :contentHash, nullif(:rawObjectKey, '')
            )
            """;
    private static final String LOAD_SINCE = """
            select theme_id, source_key, source_label, observation_date, revision,
                   observed_at, quality, status, value, score, detail, source_url,
                   content_hash, coalesce(raw_object_key, '') as raw_object_key
            from research.narrative_source_observation
            where observation_date >= :since
            order by observation_date desc, revision desc, observed_at desc
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcNarrativeSourceRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public int save(List<NarrativeSourceReading> readings) {
        if (readings == null || readings.isEmpty()) return 0;
        return transactions.execute(status -> {
            var inserted = 0;
            for (var reading : readings) inserted += saveOne(reading);
            return inserted;
        });
    }

    @Override
    public List<NarrativeSourceObservation> loadSince(LocalDate since) {
        return jdbc.query(LOAD_SINCE, new MapSqlParameterSource("since", Date.valueOf(since)),
                (row, number) -> new NarrativeSourceObservation(
                        new NarrativeSourceReading(
                                NarrativeTheme.fromId(row.getString("theme_id")),
                                row.getString("source_key"),
                                row.getString("source_label"),
                                row.getObject("observation_date", LocalDate.class),
                                row.getTimestamp("observed_at").toInstant(),
                                NarrativeSourceQuality.valueOf(row.getString("quality")),
                                NarrativeSourceStatus.valueOf(row.getString("status")),
                                row.getObject("value") == null ? null : row.getDouble("value"),
                                row.getDouble("score"),
                                row.getString("detail"),
                                row.getString("source_url"),
                                row.getString("content_hash"),
                                row.getString("raw_object_key")
                        ),
                        row.getInt("revision")
                ));
    }

    private int saveOne(NarrativeSourceReading reading) {
        var parameters = parameters(reading);
        var lockKey = reading.theme().id() + '|' + reading.sourceKey() + '|' + reading.observationDate();
        jdbc.query(LOCK, new MapSqlParameterSource("lockKey", lockKey),
                (org.springframework.jdbc.core.RowCallbackHandler) row -> { });
        var current = jdbc.query(LATEST_REVISION, parameters, (row, number) -> new CurrentRevision(
                row.getInt("revision"), row.getString("content_hash")));
        if (!current.isEmpty() && current.getFirst().contentHash().equals(reading.contentHash())) return 0;
        var revision = current.isEmpty() ? 1 : current.getFirst().revision() + 1;
        return jdbc.update(INSERT, parameters.addValue("revision", revision));
    }

    private static MapSqlParameterSource parameters(NarrativeSourceReading value) {
        return new MapSqlParameterSource()
                .addValue("themeId", value.theme().id())
                .addValue("sourceKey", value.sourceKey())
                .addValue("sourceLabel", value.label())
                .addValue("observationDate", Date.valueOf(value.observationDate()))
                .addValue("observedAt", Timestamp.from(value.observedAt()))
                .addValue("quality", value.quality().name())
                .addValue("status", value.status().name())
                .addValue("value", value.value())
                .addValue("score", value.score())
                .addValue("detail", value.detail())
                .addValue("sourceUrl", value.sourceUrl())
                .addValue("contentHash", value.contentHash())
                .addValue("rawObjectKey", value.rawObjectKey());
    }

    private record CurrentRevision(int revision, String contentHash) {
    }
}
