package io.macrosquare.notification.application.port.out;

import io.macrosquare.notification.application.model.ClaimedNotification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationOutboxRepository {
    List<ClaimedNotification> claimPending(
            String leaseOwner,
            int limit,
            Instant now,
            Duration leaseDuration,
            int maximumAttempts
    );

    void markDelivered(UUID id, String leaseOwner, String providerMessageId, Instant deliveredAt);

    void markFailed(UUID id, String leaseOwner, Instant availableAt, String failureCode, boolean terminal);

    int purgeTerminalBefore(Instant cutoff);
}
