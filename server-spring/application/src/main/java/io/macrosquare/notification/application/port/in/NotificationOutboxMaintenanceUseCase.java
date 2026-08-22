package io.macrosquare.notification.application.port.in;

import java.time.Instant;

/** Removes terminal delivery records after their audit retention window. */
public interface NotificationOutboxMaintenanceUseCase {
    int purgeTerminalBefore(Instant cutoff);
}
