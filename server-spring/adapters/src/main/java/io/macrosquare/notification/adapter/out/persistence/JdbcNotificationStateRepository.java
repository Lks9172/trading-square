package io.macrosquare.notification.adapter.out.persistence;

import io.macrosquare.notification.application.model.ClaimedNotification;
import io.macrosquare.notification.application.model.NotificationState;
import io.macrosquare.notification.application.model.NotificationStateChange;
import io.macrosquare.notification.application.model.OutboundNotification;
import io.macrosquare.notification.application.port.out.NotificationOutboxRepository;
import io.macrosquare.notification.application.port.out.NotificationStatePersistenceException;
import io.macrosquare.notification.application.port.out.NotificationStateRepository;
import io.macrosquare.notification.domain.BottomCandidateState;
import io.macrosquare.notification.domain.CandidateKind;
import io.macrosquare.notification.domain.InvestmentCandidate;
import io.macrosquare.notification.domain.TechnicalTimingEvidence;
import io.macrosquare.shared.adapter.out.persistence.PostgresTemporal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Transactional PostgreSQL deduplication state shared by all scheduler threads. */
public final class JdbcNotificationStateRepository
        implements NotificationStateRepository, NotificationOutboxRepository {

    private static final String CHANNEL = "telegram";
    /** ASCII "MSQN" in PostgreSQL's two-int advisory-lock namespace. */
    private static final int STATE_LOCK_NAMESPACE = 1_297_305_934;
    private static final int STATE_LOCK_AGGREGATE = 1;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final ObjectMapper objectMapper;

    public JdbcNotificationStateRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions,
            ObjectMapper objectMapper
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public NotificationState load() {
        try {
            return loadState();
        } catch (NotificationStatePersistenceException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new NotificationStatePersistenceException("Unable to load notification state", error);
        }
    }

    @Override
    public void save(NotificationState state) {
        Objects.requireNonNull(state);
        try {
            transactions.executeWithoutResult(ignored -> {
                lockStateAggregate();
                persistState(state);
            });
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to persist notification state", error);
        }
    }

    @Override
    public <R> R updateAtomically(Function<NotificationState, NotificationStateChange<R>> transition) {
        Objects.requireNonNull(transition, "transition");
        try {
            return Objects.requireNonNull(transactions.execute(ignored -> {
                lockStateAggregate();
                var before = loadState();
                var change = Objects.requireNonNull(transition.apply(before), "notification state change");
                // An outbox row references delivery_state, so even a state-neutral event
                // (for example the weekly report) must materialize the channel row.
                if (!change.state().equals(before) || !change.notifications().isEmpty()) {
                    persistState(change.state());
                }
                enqueue(change.notifications());
                return change.result();
            }), "notification transition result");
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to atomically update notification state", error);
        }
    }

    @Override
    public List<ClaimedNotification> claimPending(
            String leaseOwner,
            int limit,
            Instant now,
            Duration leaseDuration,
            int maximumAttempts
    ) {
        Objects.requireNonNull(leaseOwner, "leaseOwner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (limit < 1 || maximumAttempts < 1) throw new IllegalArgumentException("invalid outbox claim bounds");
        try {
            return Objects.requireNonNull(transactions.execute(ignored -> jdbc.query("""
                    with claimable as (
                        select id
                        from notification.outbox
                        where available_at <= :now
                          and (
                              (status in ('PENDING', 'RETRY') and attempts < :maximumAttempts)
                              or (status = 'IN_FLIGHT' and leased_until <= :now
                                  and attempts <= :maximumAttempts)
                          )
                        order by created_at, id
                        for update skip locked
                        limit :limit
                    )
                    update notification.outbox as target
                    set status = 'IN_FLIGHT',
                        lease_owner = :leaseOwner,
                        leased_until = :leasedUntil,
                        attempts = target.attempts + 1
                    from claimable
                    where target.id = claimable.id
                    returning target.id, target.idempotency_key, target.operation,
                              target.payload, target.attempts, target.lease_owner,
                              target.leased_until
                    """, parameters()
                    .addValue("maximumAttempts", maximumAttempts)
                    .addValue("now", PostgresTemporal.timestamp(now))
                    .addValue("limit", Math.min(limit, 100))
                    .addValue("leaseOwner", leaseOwner)
                    .addValue("leasedUntil", PostgresTemporal.timestamp(now.plus(leaseDuration))),
                    (row, ignoredRow) -> claimed(row))), "outbox claim result");
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to claim notification outbox", error);
        }
    }

    @Override
    public void markDelivered(UUID id, String leaseOwner, String providerMessageId, Instant deliveredAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(deliveredAt, "deliveredAt");
        try {
            var updated = jdbc.update("""
                    update notification.outbox
                    set status = 'DELIVERED', delivered_at = :deliveredAt,
                        provider_message_id = :providerMessageId,
                        lease_owner = null, leased_until = null, last_error = null
                    where id = :id and status = 'IN_FLIGHT' and lease_owner = :leaseOwner
                    """, parameters()
                    .addValue("id", id)
                    .addValue("leaseOwner", leaseOwner)
                    .addValue("providerMessageId", bounded(providerMessageId, 128))
                    .addValue("deliveredAt", PostgresTemporal.timestamp(deliveredAt)));
            requireSingleUpdate(updated, id, "deliver");
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to acknowledge notification outbox", error);
        }
    }

    @Override
    public void markFailed(
            UUID id,
            String leaseOwner,
            Instant availableAt,
            String failureCode,
            boolean terminal
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(availableAt, "availableAt");
        try {
            var updated = jdbc.update("""
                    update notification.outbox
                    set status = :status, available_at = :availableAt,
                        lease_owner = null, leased_until = null, last_error = :lastError
                    where id = :id and status = 'IN_FLIGHT' and lease_owner = :leaseOwner
                    """, parameters()
                    .addValue("id", id)
                    .addValue("leaseOwner", leaseOwner)
                    .addValue("status", terminal ? "DEAD" : "RETRY")
                    .addValue("availableAt", PostgresTemporal.timestamp(availableAt))
                    .addValue("lastError", bounded(failureCode, 128)));
            requireSingleUpdate(updated, id, "fail");
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to reschedule notification outbox", error);
        }
    }

    @Override
    public int purgeTerminalBefore(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        try {
            return jdbc.update("""
                    delete from notification.outbox
                    where status in ('DELIVERED', 'DEAD')
                      and coalesce(delivered_at, available_at) < :cutoff
                    """, parameters().addValue("cutoff", PostgresTemporal.timestamp(cutoff)));
        } catch (RuntimeException error) {
            throw persistenceFailure("Unable to purge terminal notification outbox", error);
        }
    }

    private NotificationState loadState() {
        var stateRows = jdbc.query("""
                    select market_fingerprint, integrity_fingerprint, updated_at
                    from notification.delivery_state
                    where channel = :channel
                    """, parameters(), (row, ignored) -> new StateRow(
                    row.getString("market_fingerprint"),
                    row.getString("integrity_fingerprint"),
                    row.getObject("updated_at", java.time.OffsetDateTime.class).toInstant()));
            if (stateRows.isEmpty()) return NotificationState.empty();
            var keys = new LinkedHashSet<>(jdbc.query("""
                    select candidate_key from notification.candidate_key
                    where channel = :channel order by candidate_key
                    """, parameters(), (row, ignored) -> row.getString("candidate_key")));
            var candidates = jdbc.query("""
                    select kind, symbol, name, classification, bottom_state, bottom_score,
                           total_score, buy_score, action, signal_date, reversal_status,
                           reversal_score, reasons, macd_timing
                    from notification.candidate_snapshot
                    where channel = :channel
                    order by candidate_key
                    """, parameters(), (row, ignored) -> candidate(row));
            var state = stateRows.getFirst();
            return new NotificationState(
                    keys, state.marketFingerprint(), state.integrityFingerprint(), state.updatedAt(), candidates);
    }

    private void persistState(NotificationState state) {
        jdbc.update("""
                        insert into notification.delivery_state (
                            channel, market_fingerprint, integrity_fingerprint, updated_at, version
                        ) values (
                            :channel, :marketFingerprint, :integrityFingerprint, :updatedAt, 1
                        )
                        on conflict (channel) do update set
                            market_fingerprint = excluded.market_fingerprint,
                            integrity_fingerprint = excluded.integrity_fingerprint,
                            updated_at = excluded.updated_at,
                            version = notification.delivery_state.version + 1
                        """, parameters()
                        .addValue("marketFingerprint", state.marketFingerprint())
                .addValue("integrityFingerprint", state.integrityFingerprint())
                .addValue("updatedAt", PostgresTemporal.timestamp(state.updatedAt())));
        jdbc.update("delete from notification.candidate_key where channel = :channel", parameters());
        jdbc.update("delete from notification.candidate_snapshot where channel = :channel", parameters());
        if (!state.candidateKeys().isEmpty()) {
            jdbc.batchUpdate("""
                            insert into notification.candidate_key (channel, candidate_key)
                            values (:channel, :candidateKey)
                            """, state.candidateKeys().stream().map(key -> parameters().addValue("candidateKey", key))
                    .toArray(MapSqlParameterSource[]::new));
        }
        if (!state.candidates().isEmpty()) {
            jdbc.batchUpdate("""
                            insert into notification.candidate_snapshot (
                                channel, candidate_key, kind, symbol, name, classification,
                                bottom_state, bottom_score, total_score, buy_score, action,
                                signal_date, reversal_status, reversal_score, reasons, macd_timing
                            ) values (
                                :channel, :candidateKey, :kind, :symbol, :name, :classification,
                                :bottomState, :bottomScore, :totalScore, :buyScore, :action,
                                :signalDate, :reversalStatus, :reversalScore, cast(:reasons as jsonb),
                                cast(:macdTiming as jsonb)
                            )
                            """, state.candidates().stream().map(this::candidateParameters)
                    .toArray(MapSqlParameterSource[]::new));
        }
    }

    private void enqueue(List<OutboundNotification> notifications) {
        if (notifications.isEmpty()) return;
        jdbc.batchUpdate("""
                insert into notification.outbox (
                    id, channel, idempotency_key, operation, payload,
                    status, created_at, available_at
                ) values (
                    :id, :channel, :idempotencyKey, :operation, :payload,
                    'PENDING', :createdAt, :createdAt
                )
                on conflict (channel, idempotency_key) do nothing
                """, notifications.stream().map(value -> parameters()
                        .addValue("id", value.id())
                        .addValue("idempotencyKey", value.idempotencyKey())
                        .addValue("operation", value.operation())
                        .addValue("payload", value.text())
                        .addValue("createdAt", PostgresTemporal.timestamp(value.createdAt())))
                .toArray(MapSqlParameterSource[]::new));
    }

    private void lockStateAggregate() {
        jdbc.query(
                "select pg_advisory_xact_lock(:namespace, :aggregate)",
                new MapSqlParameterSource()
                        .addValue("namespace", STATE_LOCK_NAMESPACE)
                        .addValue("aggregate", STATE_LOCK_AGGREGATE),
                (row, ignored) -> 0
        );
    }

    private static ClaimedNotification claimed(ResultSet row) throws SQLException {
        return new ClaimedNotification(
                row.getObject("id", UUID.class),
                row.getString("idempotency_key"),
                row.getString("operation"),
                row.getString("payload"),
                row.getInt("attempts"),
                row.getString("lease_owner"),
                row.getObject("leased_until", java.time.OffsetDateTime.class).toInstant()
        );
    }

    private static void requireSingleUpdate(int updated, UUID id, String operation) {
        if (updated != 1) {
            throw new IllegalStateException("Unable to " + operation + " leased notification " + id);
        }
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "";
        return value.substring(0, Math.min(value.length(), maximum));
    }

    private static NotificationStatePersistenceException persistenceFailure(String message, RuntimeException error) {
        if (error instanceof NotificationStatePersistenceException persistence) return persistence;
        return new NotificationStatePersistenceException(message, error);
    }

    private InvestmentCandidate candidate(ResultSet row) throws SQLException {
        var reasons = new ArrayList<String>();
        var node = objectMapper.readTree(row.getString("reasons"));
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                if (value.isString()) reasons.add(value.stringValue());
            });
        }
        return new InvestmentCandidate(
                CandidateKind.valueOf(row.getString("kind")),
                row.getString("symbol"),
                row.getString("name"),
                row.getString("classification"),
                BottomCandidateState.valueOf(row.getString("bottom_state")),
                nullableInteger(row, "bottom_score"),
                row.getInt("total_score"),
                row.getInt("buy_score"),
                row.getString("action"),
                row.getObject("signal_date", java.time.LocalDate.class),
                row.getString("reversal_status"),
                nullableInteger(row, "reversal_score"),
                reasons,
                technicalTiming(row.getString("macd_timing"))
        );
    }

    private MapSqlParameterSource candidateParameters(InvestmentCandidate value) {
        return parameters()
                .addValue("candidateKey", value.key())
                .addValue("kind", value.kind().name())
                .addValue("symbol", value.symbol())
                .addValue("name", value.name())
                .addValue("classification", value.classification())
                .addValue("bottomState", value.bottomState().name())
                .addValue("bottomScore", value.bottomScore())
                .addValue("totalScore", value.totalScore())
                .addValue("buyScore", value.buyScore())
                .addValue("action", value.action())
                .addValue("signalDate", value.signalDate())
                .addValue("reversalStatus", value.reversalStatus())
                .addValue("reversalScore", value.reversalScore())
                .addValue("reasons", objectMapper.writeValueAsString(value.reasons()))
                .addValue("macdTiming", value.technicalTiming() == null
                        ? null : objectMapper.writeValueAsString(value.technicalTiming()));
    }

    private TechnicalTimingEvidence technicalTiming(String json) {
        if (json == null || json.isBlank()) return null;
        return objectMapper.readValue(json, TechnicalTimingEvidence.class);
    }

    private static MapSqlParameterSource parameters() {
        return new MapSqlParameterSource("channel", CHANNEL);
    }

    private static Integer nullableInteger(ResultSet row, String column) throws SQLException {
        var value = row.getInt(column);
        return row.wasNull() ? null : value;
    }

    private record StateRow(
            String marketFingerprint,
            String integrityFingerprint,
            java.time.Instant updatedAt
    ) {
    }
}
