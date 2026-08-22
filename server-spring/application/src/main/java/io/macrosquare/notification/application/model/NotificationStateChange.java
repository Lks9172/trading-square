package io.macrosquare.notification.application.model;

import java.util.List;
import java.util.Objects;

/** State transition and side effects committed in one persistence transaction. */
public record NotificationStateChange<R>(
        NotificationState state,
        List<OutboundNotification> notifications,
        R result
) {
    public NotificationStateChange {
        Objects.requireNonNull(state, "state");
        notifications = List.copyOf(notifications == null ? List.of() : notifications);
    }

    public static <R> NotificationStateChange<R> stateOnly(NotificationState state, R result) {
        return new NotificationStateChange<>(state, List.of(), result);
    }

    public static <R> NotificationStateChange<R> withNotification(
            NotificationState state,
            OutboundNotification notification,
            R result
    ) {
        return new NotificationStateChange<>(state, List.of(Objects.requireNonNull(notification)), result);
    }
}
