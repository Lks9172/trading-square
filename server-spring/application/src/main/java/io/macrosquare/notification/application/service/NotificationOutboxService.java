package io.macrosquare.notification.application.service;

import io.macrosquare.notification.application.port.in.NotificationOutboxDispatchUseCase;
import io.macrosquare.notification.application.port.in.NotificationOutboxMaintenanceUseCase;
import io.macrosquare.notification.application.port.out.NotificationOutboxRepository;
import io.macrosquare.notification.application.port.out.SendNotificationPort;
import io.macrosquare.shared.application.port.out.OperationalEventSink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Claims durable messages and performs the non-transactional provider call. */
public final class NotificationOutboxService
        implements NotificationOutboxDispatchUseCase, NotificationOutboxMaintenanceUseCase {

    private static final Duration MAXIMUM_RETRY_DELAY = Duration.ofHours(6);

    private final NotificationOutboxRepository outbox;
    private final SendNotificationPort sender;
    private final Clock clock;
    private final String leaseOwner = UUID.randomUUID().toString();
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration retryBaseDelay;
    private final int maximumAttempts;
    private final OperationalEventSink operationalEvents;

    public NotificationOutboxService(
            NotificationOutboxRepository outbox,
            SendNotificationPort sender,
            Clock clock,
            int batchSize,
            Duration leaseDuration,
            Duration retryBaseDelay,
            int maximumAttempts,
            OperationalEventSink operationalEvents
    ) {
        this.outbox = Objects.requireNonNull(outbox);
        this.sender = Objects.requireNonNull(sender);
        this.clock = Objects.requireNonNull(clock);
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("invalid outbox batchSize");
        this.batchSize = batchSize;
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.retryBaseDelay = positive(retryBaseDelay, "retryBaseDelay");
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalArgumentException("invalid outbox maximumAttempts");
        }
        this.maximumAttempts = maximumAttempts;
        this.operationalEvents = Objects.requireNonNull(operationalEvents);
    }

    @Override
    public int dispatchPending() {
        var now = clock.instant();
        var claimed = outbox.claimPending(
                leaseOwner, batchSize, now, leaseDuration, maximumAttempts);
        var delivered = 0;
        for (var notification : claimed) {
            try {
                var receipt = sender.send(notification.idempotencyKey(), notification.text());
                if (receipt.delivered()) {
                    outbox.markDelivered(
                            notification.id(), leaseOwner, receipt.providerMessageId(), clock.instant());
                    delivered++;
                    continue;
                }
                fail(notification.id(), notification.attempts(), receipt.failureCode());
            } catch (RuntimeException error) {
                operationalEvents.degraded(
                        "notification", "outbox-delivery", notification.operation(), error);
                fail(notification.id(), notification.attempts(), error.getClass().getSimpleName());
            }
        }
        return delivered;
    }

    @Override
    public int purgeTerminalBefore(Instant cutoff) {
        return outbox.purgeTerminalBefore(Objects.requireNonNull(cutoff, "cutoff"));
    }

    private void fail(UUID id, int attempts, String failureCode) {
        var terminal = attempts >= maximumAttempts;
        var availableAt = terminal ? clock.instant() : clock.instant().plus(retryDelay(attempts));
        outbox.markFailed(id, leaseOwner, availableAt, sanitize(failureCode), terminal);
    }

    private Duration retryDelay(int attempts) {
        var exponent = Math.min(Math.max(attempts - 1, 0), 20);
        try {
            var candidate = retryBaseDelay.multipliedBy(1L << exponent);
            return candidate.compareTo(MAXIMUM_RETRY_DELAY) > 0 ? MAXIMUM_RETRY_DELAY : candidate;
        } catch (ArithmeticException overflow) {
            return MAXIMUM_RETRY_DELAY;
        }
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "delivery-failed";
        var sanitized = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return sanitized.substring(0, Math.min(120, sanitized.length()));
    }
}
