package io.macrosquare.notification.application.port.out;

import io.macrosquare.notification.application.model.NotificationState;
import io.macrosquare.notification.application.model.NotificationStateChange;

import java.util.function.Function;

public interface NotificationStateRepository {
    NotificationState load();

    void save(NotificationState state);

    /**
     * Applies a state transition and enqueues its outbound notifications atomically.
     * Implementations must serialize transitions across application instances.
     */
    <R> R updateAtomically(Function<NotificationState, NotificationStateChange<R>> transition);
}
