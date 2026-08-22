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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Atomic single-process fallback persistence. Production uses the PostgreSQL
 * adapter; this file format remains available for local rollback and fixtures.
 */
public final class FileNotificationStateRepository
        implements NotificationStateRepository, NotificationOutboxRepository {

    private static final long MAXIMUM_STATE_BYTES = 16_777_216L;
    private static final int MAXIMUM_CANDIDATES = 100;
    private static final int MAXIMUM_OUTBOX_ITEMS = 250;

    private final ObjectMapper objectMapper;
    private final Path file;
    // File reads, fsyncs and atomic moves can block. A Java 21 virtual thread must not
    // perform them while owning an intrinsic monitor because that pins its carrier.
    private final ReentrantLock stateLock = new ReentrantLock(true);

    public FileNotificationStateRepository(ObjectMapper objectMapper, Path directory) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        var normalized = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.file = normalized.resolve("notification-state-v1.json");
    }

    @Override
    public NotificationState load() {
        return withStateLock(() -> readDocument().state());
    }

    @Override
    public void save(NotificationState state) {
        withStateLock(() -> {
            var current = readDocument();
            writeDocument(new StoredDocument(Objects.requireNonNull(state), current.outbox()));
        });
    }

    @Override
    public <R> R updateAtomically(
            Function<NotificationState, NotificationStateChange<R>> transition
    ) {
        return withStateLock(() -> {
            var current = readDocument();
            var change = Objects.requireNonNull(
                    Objects.requireNonNull(transition, "transition").apply(current.state()),
                    "notification state change");
            var outbox = new ArrayList<>(current.outbox());
            for (var notification : change.notifications()) {
                var duplicate = outbox.stream().anyMatch(value ->
                        value.idempotencyKey().equals(notification.idempotencyKey()));
                if (!duplicate) outbox.add(StoredOutbox.pending(notification));
            }
            if (outbox.size() > MAXIMUM_OUTBOX_ITEMS) {
                throw new NotificationStatePersistenceException("Notification outbox exceeds the persistence bound");
            }
            if (!change.state().equals(current.state()) || !outbox.equals(current.outbox())) {
                writeDocument(new StoredDocument(change.state(), outbox));
            }
            return change.result();
        });
    }

    @Override
    public List<ClaimedNotification> claimPending(
            String leaseOwner,
            int limit,
            Instant now,
            Duration leaseDuration,
            int maximumAttempts
    ) {
        return withStateLock(() -> {
            Objects.requireNonNull(leaseOwner, "leaseOwner");
            Objects.requireNonNull(now, "now");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (limit < 1 || maximumAttempts < 1) {
                throw new IllegalArgumentException("invalid outbox claim bounds");
            }
            var current = readDocument();
            var outbox = new ArrayList<>(current.outbox());
            var indexes = new ArrayList<Integer>();
            for (var index = 0; index < outbox.size(); index++) {
                if (outbox.get(index).claimable(now, maximumAttempts)) indexes.add(index);
            }
            indexes.sort(Comparator.comparing(index -> outbox.get(index).createdAtInstant()));
            var claimed = new ArrayList<ClaimedNotification>();
            var leasedUntil = now.plus(leaseDuration);
            for (var index : indexes.stream().limit(Math.min(limit, 100)).toList()) {
                var leased = outbox.get(index).claim(leaseOwner, leasedUntil);
                outbox.set(index, leased);
                claimed.add(leased.toClaimed());
            }
            if (!claimed.isEmpty()) writeDocument(new StoredDocument(current.state(), outbox));
            return List.copyOf(claimed);
        });
    }

    @Override
    public void markDelivered(
            UUID id,
            String leaseOwner,
            String providerMessageId,
            Instant deliveredAt
    ) {
        withStateLock(() -> mutateLeased(
                id, leaseOwner, value -> value.delivered(providerMessageId, deliveredAt)));
    }

    @Override
    public void markFailed(
            UUID id,
            String leaseOwner,
            Instant availableAt,
            String failureCode,
            boolean terminal
    ) {
        withStateLock(() -> mutateLeased(
                id, leaseOwner, value -> value.failed(availableAt, failureCode, terminal)));
    }

    @Override
    public int purgeTerminalBefore(Instant cutoff) {
        return withStateLock(() -> {
            Objects.requireNonNull(cutoff, "cutoff");
            var current = readDocument();
            var retained = current.outbox().stream()
                    .filter(value -> !value.terminalBefore(cutoff))
                    .toList();
            var purged = current.outbox().size() - retained.size();
            if (purged > 0) writeDocument(new StoredDocument(current.state(), retained));
            return purged;
        });
    }

    private <T> T withStateLock(Supplier<T> action) {
        stateLock.lock();
        try {
            return action.get();
        } finally {
            stateLock.unlock();
        }
    }

    private void withStateLock(Runnable action) {
        stateLock.lock();
        try {
            action.run();
        } finally {
            stateLock.unlock();
        }
    }

    private void mutateLeased(UUID id, String leaseOwner, Function<StoredOutbox, StoredOutbox> mutation) {
        var current = readDocument();
        var outbox = new ArrayList<>(current.outbox());
        for (var index = 0; index < outbox.size(); index++) {
            var value = outbox.get(index);
            if (!value.id().equals(id)) continue;
            if (!"IN_FLIGHT".equals(value.status()) || !Objects.equals(leaseOwner, value.leaseOwner())) {
                throw new NotificationStatePersistenceException("Notification outbox lease is no longer owned");
            }
            outbox.set(index, mutation.apply(value));
            writeDocument(new StoredDocument(current.state(), outbox));
            return;
        }
        throw new NotificationStatePersistenceException("Notification outbox item was not found");
    }

    private StoredDocument readDocument() {
        if (!Files.isRegularFile(file)) return new StoredDocument(NotificationState.empty(), List.of());
        try {
            if (Files.size(file) > MAXIMUM_STATE_BYTES) {
                throw new NotificationStatePersistenceException("Notification state exceeds the persistence bound");
            }
            var root = objectMapper.readTree(Files.readAllBytes(file));
            if (root == null || !root.isObject()) {
                throw new NotificationStatePersistenceException("Notification state must be a JSON object");
            }
            var schemaVersion = root.get("schemaVersion");
            if (schemaVersion != null && (!schemaVersion.isIntegralNumber()
                    || schemaVersion.intValue() < 1 || schemaVersion.intValue() > 3)) {
                throw new NotificationStatePersistenceException("Unsupported notification state schema version");
            }
            return new StoredDocument(readState(root), readOutbox(root));
        } catch (NotificationStatePersistenceException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new NotificationStatePersistenceException("Unable to read notification state", error);
        }
    }

    private NotificationState readState(JsonNode root) {
        var keys = new LinkedHashSet<String>();
        var values = root.get("candidateKeys");
        if (values != null && values.isArray()) {
            for (var value : values) if (value.isString()) keys.add(value.stringValue());
        }
        var fingerprint = root.get("marketFingerprint") != null && root.get("marketFingerprint").isString()
                ? root.get("marketFingerprint").stringValue() : "";
        var integrityFingerprint = root.get("integrityFingerprint") != null
                && root.get("integrityFingerprint").isString()
                ? root.get("integrityFingerprint").stringValue() : "";
        var updatedAt = root.get("updatedAt") != null && root.get("updatedAt").isString()
                ? Instant.parse(root.get("updatedAt").stringValue()) : Instant.EPOCH;
        var candidates = new ArrayList<InvestmentCandidate>();
        var candidateValues = root.get("candidates");
        if (candidateValues != null && candidateValues.isArray()) {
            for (var value : candidateValues) {
                if (candidates.size() >= MAXIMUM_CANDIDATES) break;
                candidates.add(objectMapper.treeToValue(value, StoredCandidate.class).toDomain());
            }
        }
        return new NotificationState(keys, fingerprint, integrityFingerprint, updatedAt, candidates);
    }

    private List<StoredOutbox> readOutbox(JsonNode root) {
        var result = new ArrayList<StoredOutbox>();
        var values = root.get("outbox");
        if (values != null && values.isArray()) {
            for (var value : values) {
                if (result.size() >= MAXIMUM_OUTBOX_ITEMS) {
                    throw new NotificationStatePersistenceException("Notification outbox exceeds the persistence bound");
                }
                result.add(objectMapper.treeToValue(value, StoredOutbox.class).validated());
            }
        }
        return List.copyOf(result);
    }

    private void writeDocument(StoredDocument document) {
        try {
            Files.createDirectories(file.getParent());
            var state = document.state();
            var payload = objectMapper.writeValueAsBytes(new StoredState(
                    3,
                    state.candidateKeys().stream().sorted().toList(),
                    state.marketFingerprint(),
                    state.integrityFingerprint(),
                    state.updatedAt().toString(),
                    state.candidates().stream().limit(MAXIMUM_CANDIDATES).map(StoredCandidate::from).toList(),
                    document.outbox()
            ));
            if (payload.length > MAXIMUM_STATE_BYTES) {
                throw new NotificationStatePersistenceException("Notification state exceeds the persistence bound");
            }
            var temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            try {
                Files.write(temporary, payload, StandardOpenOption.TRUNCATE_EXISTING);
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
                forceDirectory(file.getParent());
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (NotificationStatePersistenceException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new NotificationStatePersistenceException("Unable to persist notification state", error);
        }
    }

    private static void forceDirectory(Path directory) {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (Exception ignored) {
            // The state file was already forced; directory fsync is not supported everywhere.
        }
    }

    private record StoredDocument(NotificationState state, List<StoredOutbox> outbox) {
        private StoredDocument {
            Objects.requireNonNull(state);
            outbox = List.copyOf(outbox);
        }
    }

    private record StoredState(
            int schemaVersion,
            List<String> candidateKeys,
            String marketFingerprint,
            String integrityFingerprint,
            String updatedAt,
            List<StoredCandidate> candidates,
            List<StoredOutbox> outbox
    ) {
    }

    private record StoredOutbox(
            UUID id,
            String idempotencyKey,
            String operation,
            String payload,
            String status,
            String createdAt,
            String availableAt,
            int attempts,
            String leaseOwner,
            String leasedUntil,
            String deliveredAt,
            String providerMessageId,
            String lastError
    ) {
        static StoredOutbox pending(OutboundNotification value) {
            return new StoredOutbox(
                    value.id(), value.idempotencyKey(), value.operation(), value.text(), "PENDING",
                    value.createdAt().toString(), value.createdAt().toString(), 0,
                    null, null, null, null, null);
        }

        StoredOutbox validated() {
            Objects.requireNonNull(id, "outbox id");
            if (idempotencyKey == null || operation == null || payload == null || status == null
                    || createdAt == null || availableAt == null) {
                throw new IllegalArgumentException("invalid stored notification outbox item");
            }
            Instant.parse(createdAt);
            Instant.parse(availableAt);
            if (leasedUntil != null) Instant.parse(leasedUntil);
            if (deliveredAt != null) Instant.parse(deliveredAt);
            return this;
        }

        Instant createdAtInstant() {
            return Instant.parse(createdAt);
        }

        boolean claimable(Instant now, int maximumAttempts) {
            if (Instant.parse(availableAt).isAfter(now)) return false;
            if ("PENDING".equals(status) || "RETRY".equals(status)) return attempts < maximumAttempts;
            return "IN_FLIGHT".equals(status) && leasedUntil != null
                    && attempts <= maximumAttempts && !Instant.parse(leasedUntil).isAfter(now);
        }

        StoredOutbox claim(String owner, Instant until) {
            return new StoredOutbox(
                    id, idempotencyKey, operation, payload, "IN_FLIGHT", createdAt, availableAt,
                    attempts + 1, owner, until.toString(), deliveredAt, providerMessageId, lastError);
        }

        ClaimedNotification toClaimed() {
            return new ClaimedNotification(
                    id, idempotencyKey, operation, payload, attempts, leaseOwner, Instant.parse(leasedUntil));
        }

        StoredOutbox delivered(String providerId, Instant at) {
            return new StoredOutbox(
                    id, idempotencyKey, operation, payload, "DELIVERED", createdAt, availableAt,
                    attempts, null, null, at.toString(), bounded(providerId, 128), null);
        }

        StoredOutbox failed(Instant next, String error, boolean terminal) {
            return new StoredOutbox(
                    id, idempotencyKey, operation, payload, terminal ? "DEAD" : "RETRY",
                    createdAt, next.toString(), attempts, null, null, deliveredAt,
                    providerMessageId, bounded(error, 128));
        }

        boolean terminalBefore(Instant cutoff) {
            if ("DELIVERED".equals(status) && deliveredAt != null) {
                return Instant.parse(deliveredAt).isBefore(cutoff);
            }
            return "DEAD".equals(status) && Instant.parse(availableAt).isBefore(cutoff);
        }

        private static String bounded(String value, int maximum) {
            if (value == null) return null;
            return value.substring(0, Math.min(maximum, value.length()));
        }
    }

    private record StoredCandidate(
            CandidateKind kind,
            String symbol,
            String name,
            String classification,
            BottomCandidateState bottomState,
            Integer bottomScore,
            int totalScore,
            int buyScore,
            String action,
            String signalDate,
            String reversalStatus,
            Integer reversalScore,
            List<String> reasons,
            TechnicalTimingEvidence macdTiming
    ) {
        static StoredCandidate from(InvestmentCandidate value) {
            return new StoredCandidate(
                    value.kind(), value.symbol(), value.name(), value.classification(), value.bottomState(),
                    value.bottomScore(), value.totalScore(), value.buyScore(), value.action(),
                    value.signalDate() == null ? null : value.signalDate().toString(),
                    value.reversalStatus(), value.reversalScore(), value.reasons(), value.technicalTiming()
            );
        }

        InvestmentCandidate toDomain() {
            return new InvestmentCandidate(
                    kind, symbol, name, classification, bottomState, bottomScore, totalScore, buyScore, action,
                    signalDate == null || signalDate.isBlank() ? null : LocalDate.parse(signalDate),
                    reversalStatus, reversalScore, reasons, macdTiming
            );
        }
    }
}
