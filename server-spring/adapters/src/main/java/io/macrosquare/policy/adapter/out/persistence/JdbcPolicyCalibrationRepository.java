package io.macrosquare.policy.adapter.out.persistence;

import io.macrosquare.policy.application.port.out.PolicyCalibrationRepository;
import io.macrosquare.policy.application.port.out.PolicyPersistenceException;
import io.macrosquare.policy.domain.model.PolicyCalibrationObservation;
import io.macrosquare.policy.domain.model.PolicyDecisionDirection;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

public final class JdbcPolicyCalibrationRepository implements PolicyCalibrationRepository {

    private static final String UPSERT = """
            insert into policy.confidence_calibration (
                document_id, published_at, raw_confidence, tone_score,
                actual_decision, direction_matched, observed_at
            ) values (
                :documentId, :publishedAt, :rawConfidence, :toneScore,
                :actualDecision, :directionMatched, clock_timestamp()
            )
            on conflict (document_id) do update set
                published_at = excluded.published_at,
                raw_confidence = excluded.raw_confidence,
                tone_score = excluded.tone_score,
                actual_decision = excluded.actual_decision,
                direction_matched = excluded.direction_matched,
                observed_at = excluded.observed_at
            """;
    private static final String LOAD = """
            select document_id, published_at, raw_confidence, tone_score,
                   actual_decision, direction_matched
            from (
                select * from policy.confidence_calibration
                order by published_at desc, document_id desc
                limit :limit
            ) recent
            order by published_at, document_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcPolicyCalibrationRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public int save(List<PolicyCalibrationObservation> observations) {
        if (observations == null || observations.isEmpty()) return 0;
        try {
            return transactions.execute(status -> {
                var parameters = observations.stream().map(value -> new MapSqlParameterSource()
                        .addValue("documentId", value.documentId())
                        .addValue("publishedAt", Timestamp.from(value.publishedAt()))
                        .addValue("rawConfidence", value.rawConfidence())
                        .addValue("toneScore", value.toneScore())
                        .addValue("actualDecision", value.actualDecision().name())
                        .addValue("directionMatched", value.directionMatched()))
                        .toArray(MapSqlParameterSource[]::new);
                jdbc.batchUpdate(UPSERT, parameters);
                return observations.size();
            });
        } catch (RuntimeException error) {
            throw new PolicyPersistenceException("Unable to persist policy calibration observations", error);
        }
    }

    @Override
    public List<PolicyCalibrationObservation> loadChronological(int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit is out of range");
        try {
            return jdbc.query(LOAD, new MapSqlParameterSource("limit", limit), (row, number) ->
                    new PolicyCalibrationObservation(
                            row.getString("document_id"), row.getTimestamp("published_at").toInstant(),
                            row.getInt("raw_confidence"), row.getInt("tone_score"),
                            PolicyDecisionDirection.valueOf(row.getString("actual_decision")),
                            row.getBoolean("direction_matched")));
        } catch (RuntimeException error) {
            throw new PolicyPersistenceException("Unable to load policy calibration observations", error);
        }
    }
}
