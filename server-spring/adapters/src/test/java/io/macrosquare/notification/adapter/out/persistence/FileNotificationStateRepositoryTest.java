package io.macrosquare.notification.adapter.out.persistence;

import io.macrosquare.notification.application.model.NotificationState;
import io.macrosquare.notification.application.model.NotificationStateChange;
import io.macrosquare.notification.application.model.OutboundNotification;
import io.macrosquare.notification.application.port.out.NotificationStatePersistenceException;
import io.macrosquare.notification.domain.BottomCandidateState;
import io.macrosquare.notification.domain.CandidateKind;
import io.macrosquare.notification.domain.InvestmentCandidate;
import io.macrosquare.notification.domain.TechnicalTimingEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNotificationStateRepositoryTest {

    @TempDir
    Path directory;

    @Test
    void atomicallyRoundTripsTheLatestQualifiedCandidateDetails() throws Exception {
        var repository = new FileNotificationStateRepository(new ObjectMapper(), directory);
        var candidate = candidate();
        var expected = new NotificationState(
                Set.of(candidate.key()), "NEUTRAL:68", "a".repeat(64),
                Instant.parse("2026-07-21T03:00:00Z"),
                List.of(candidate));

        repository.save(expected);

        assertEquals(expected, repository.load());
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void readsThePreviousKeysOnlySchemaAndAllowsStartupToSeedCandidateDetails() throws Exception {
        Files.writeString(directory.resolve("notification-state-v1.json"), """
                {"candidateKeys":["company:TEST"],"marketFingerprint":"old","updatedAt":"2026-07-20T00:00:00Z"}
                """);
        var repository = new FileNotificationStateRepository(new ObjectMapper(), directory);

        var state = repository.load();

        assertEquals(Set.of("company:TEST"), state.candidateKeys());
        assertEquals("old", state.marketFingerprint());
        assertTrue(state.candidates().isEmpty());
    }

    @Test
    void rejectsCorruptStateInsteadOfResettingDeduplicationSilently() throws Exception {
        Files.writeString(directory.resolve("notification-state-v1.json"), "{not-json");
        var repository = new FileNotificationStateRepository(new ObjectMapper(), directory);

        assertThrows(NotificationStatePersistenceException.class, repository::load);
    }

    @Test
    void rejectsACorruptPersistedIntegrityFingerprint() throws Exception {
        Files.writeString(directory.resolve("notification-state-v1.json"), """
                {"schemaVersion":3,"integrityFingerprint":"not-a-sha","updatedAt":"2026-07-20T00:00:00Z"}
                """);
        var repository = new FileNotificationStateRepository(new ObjectMapper(), directory);

        assertThrows(NotificationStatePersistenceException.class, repository::load);
    }

    @Test
    void atomicallyPersistsClaimsAndAcknowledgesAnOutboxMessage() {
        var repository = new FileNotificationStateRepository(new ObjectMapper(), directory);
        var now = Instant.parse("2026-07-21T03:00:00Z");
        var message = OutboundNotification.create("startup", "deploy-1", "hello", now);

        repository.updateAtomically(previous -> NotificationStateChange.withNotification(
                new NotificationState(Set.of(), "fingerprint", now, List.of()), message, true));

        var claimed = repository.claimPending(
                "worker-1", 10, now, Duration.ofMinutes(5), 12);
        assertEquals(1, claimed.size());
        assertEquals(message.idempotencyKey(), claimed.getFirst().idempotencyKey());

        repository.markDelivered(message.id(), "worker-1", "telegram:42", now.plusSeconds(1));
        assertTrue(repository.claimPending(
                "worker-2", 10, now.plus(Duration.ofHours(1)), Duration.ofMinutes(5), 12).isEmpty());
        assertEquals("fingerprint", repository.load().marketFingerprint());
    }

    @Test
    void purgesOnlyTerminalMessagesOutsideTheRetentionWindow() throws Exception {
        var repository = new FileNotificationStateRepository(new ObjectMapper(), directory);
        var old = Instant.parse("2026-05-01T00:00:00Z");
        var current = Instant.parse("2026-07-21T00:00:00Z");
        var delivered = OutboundNotification.create("old-delivered", "old-delivered", "one", old);
        var dead = OutboundNotification.create("old-dead", "old-dead", "two", old.plusSeconds(2));
        var pending = OutboundNotification.create("pending", "pending", "three", current);

        enqueue(repository, delivered);
        var first = repository.claimPending("worker", 1, old, Duration.ofMinutes(5), 1).getFirst();
        repository.markDelivered(first.id(), "worker", "telegram:1", old.plusSeconds(1));
        enqueue(repository, dead);
        var second = repository.claimPending("worker", 1, old.plusSeconds(2), Duration.ofMinutes(5), 1).getFirst();
        repository.markFailed(second.id(), "worker", old.plusSeconds(3), "rejected", true);
        enqueue(repository, pending);

        assertEquals(2, repository.purgeTerminalBefore(current.minus(Duration.ofDays(30))));

        var root = new ObjectMapper().readTree(Files.readAllBytes(
                directory.resolve("notification-state-v1.json")));
        assertEquals(1, root.get("outbox").size());
        assertEquals("PENDING", root.get("outbox").get(0).get("status").stringValue());
        assertEquals(0, repository.purgeTerminalBefore(current.plus(Duration.ofDays(1))));
    }

    private static void enqueue(
            FileNotificationStateRepository repository,
            OutboundNotification message
    ) {
        repository.updateAtomically(previous ->
                NotificationStateChange.withNotification(previous, message, true));
    }

    private static InvestmentCandidate candidate() {
        return new InvestmentCandidate(
                CandidateKind.COMPANY, "TEST", "Test Company", "Technology",
                BottomCandidateState.CONVICTION, 84, 78, 82, "STRONG BUY",
                LocalDate.parse("2026-07-19"), "STRONG", 88,
                List.of("volume capitulation", "reversal confirmation"),
                new TechnicalTimingEvidence(
                        new TechnicalTimingEvidence.Timeframe(
                                LocalDate.parse("2026-07-20"), TechnicalTimingEvidence.Position.ABOVE_SIGNAL,
                                TechnicalTimingEvidence.Cross.BULLISH_CROSS, LocalDate.parse("2026-07-18"), 2,
                                TechnicalTimingEvidence.Histogram.EXPANDING_POSITIVE,
                                TechnicalTimingEvidence.Divergence.BULLISH,
                                LocalDate.parse("2026-07-19"), 1, true),
                        new TechnicalTimingEvidence.Timeframe(
                                LocalDate.parse("2026-07-20"), TechnicalTimingEvidence.Position.BELOW_SIGNAL,
                                TechnicalTimingEvidence.Cross.BEARISH_CROSS, LocalDate.parse("2026-07-18"), 1,
                                TechnicalTimingEvidence.Histogram.CONTRACTING_NEGATIVE,
                                TechnicalTimingEvidence.Divergence.NONE, null, null, false),
                        true));
    }
}
