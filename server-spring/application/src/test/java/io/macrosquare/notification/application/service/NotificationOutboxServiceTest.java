package io.macrosquare.notification.application.service;

import io.macrosquare.notification.application.model.ClaimedNotification;
import io.macrosquare.notification.application.model.NotificationDeliveryReceipt;
import io.macrosquare.notification.application.port.out.NotificationOutboxRepository;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void acknowledgesAProviderReceiptOnlyAfterDeliverySucceeds() {
        var repository = new FakeOutbox(1);
        var service = service(repository,
                (key, text) -> NotificationDeliveryReceipt.delivered("telegram:42"), 3);

        assertEquals(1, service.dispatchPending());

        assertTrue(repository.delivered);
        assertEquals("telegram:42", repository.providerId);
        assertFalse(repository.failed);
    }

    @Test
    void reschedulesATransientFailureWithoutLosingTheDurableMessage() {
        var repository = new FakeOutbox(1);
        var service = service(repository,
                (key, text) -> NotificationDeliveryReceipt.failed("timeout"), 3);

        assertEquals(0, service.dispatchPending());

        assertTrue(repository.failed);
        assertFalse(repository.terminal);
        assertEquals("timeout", repository.failureCode);
        assertEquals(NOW.plusSeconds(30), repository.availableAt);
    }

    @Test
    void movesAnExhaustedMessageToTheDeadState() {
        var repository = new FakeOutbox(3);
        var service = service(repository,
                (key, text) -> NotificationDeliveryReceipt.failed("provider-rejected"), 3);

        service.dispatchPending();

        assertTrue(repository.failed);
        assertTrue(repository.terminal);
    }

    @Test
    void delegatesTerminalRetentionWithoutMixingItIntoDelivery() {
        var repository = new FakeOutbox(1);
        repository.purgeResult = 7;
        var service = service(repository,
                (key, text) -> NotificationDeliveryReceipt.delivered("telegram:42"), 3);
        var cutoff = NOW.minus(Duration.ofDays(30));

        assertEquals(7, service.purgeTerminalBefore(cutoff));
        assertEquals(cutoff, repository.purgeCutoff);
    }

    private static NotificationOutboxService service(
            FakeOutbox repository,
            io.macrosquare.notification.application.port.out.SendNotificationPort sender,
            int maximumAttempts
    ) {
        return new NotificationOutboxService(
                repository,
                sender,
                CLOCK,
                20,
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                maximumAttempts,
                OperationalEventSink.noop());
    }

    private static final class FakeOutbox implements NotificationOutboxRepository {
        private final UUID id = UUID.randomUUID();
        private final int attempts;
        private boolean delivered;
        private boolean failed;
        private boolean terminal;
        private String providerId;
        private String failureCode;
        private Instant availableAt;
        private Instant purgeCutoff;
        private int purgeResult;

        private FakeOutbox(int attempts) {
            this.attempts = attempts;
        }

        @Override
        public List<ClaimedNotification> claimPending(
                String leaseOwner,
                int limit,
                Instant now,
                Duration leaseDuration,
                int maximumAttempts
        ) {
            return List.of(new ClaimedNotification(
                    id, "a".repeat(64), "test", "payload", attempts,
                    leaseOwner, now.plus(leaseDuration)));
        }

        @Override
        public void markDelivered(UUID id, String leaseOwner, String providerMessageId, Instant deliveredAt) {
            this.delivered = true;
            this.providerId = providerMessageId;
        }

        @Override
        public void markFailed(
                UUID id,
                String leaseOwner,
                Instant availableAt,
                String failureCode,
                boolean terminal
        ) {
            this.failed = true;
            this.availableAt = availableAt;
            this.failureCode = failureCode;
            this.terminal = terminal;
        }

        @Override
        public int purgeTerminalBefore(Instant cutoff) {
            this.purgeCutoff = cutoff;
            return purgeResult;
        }
    }
}
