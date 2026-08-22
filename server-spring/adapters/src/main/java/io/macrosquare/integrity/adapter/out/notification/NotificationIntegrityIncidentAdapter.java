package io.macrosquare.integrity.adapter.out.notification;

import io.macrosquare.integrity.application.model.IntegrityIncidentTransition;
import io.macrosquare.integrity.application.port.out.PublishDataIntegrityIncidentPort;
import io.macrosquare.integrity.domain.DataIntegrityReport;
import io.macrosquare.notification.application.model.NotificationStateChange;
import io.macrosquare.notification.application.model.OutboundNotification;
import io.macrosquare.notification.application.port.out.NotificationStateRepository;

import java.time.Instant;
import java.util.Objects;

/** Anti-corruption adapter from integrity incidents to the durable notification outbox. */
public final class NotificationIntegrityIncidentAdapter implements PublishDataIntegrityIncidentPort {

    private final NotificationStateRepository state;

    public NotificationIntegrityIncidentAdapter(NotificationStateRepository state) {
        this.state = Objects.requireNonNull(state);
    }

    @Override
    public IntegrityIncidentTransition transition(
            DataIntegrityReport report,
            String alertText,
            String recoveryText,
            Instant now
    ) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(now, "now");
        return state.updateAtomically(previous -> {
            var active = previous.integrityFingerprint();
            if (report.healthy()) {
                if (active.isBlank()) {
                    return NotificationStateChange.stateOnly(
                            previous, IntegrityIncidentTransition.HEALTHY);
                }
                var next = previous.withIntegrityFingerprint("", now);
                var recovery = OutboundNotification.create(
                        "data-integrity-recovery",
                        active + '|' + now.toEpochMilli(),
                        Objects.requireNonNull(recoveryText),
                        now
                );
                return NotificationStateChange.withNotification(
                        next, recovery, IntegrityIncidentTransition.RECOVERED);
            }

            var fingerprint = report.fingerprint();
            if (fingerprint.equals(active)) {
                return NotificationStateChange.stateOnly(
                        previous, IntegrityIncidentTransition.UNCHANGED_INCIDENT);
            }
            var next = previous.withIntegrityFingerprint(fingerprint, now);
            var alert = OutboundNotification.create(
                    "data-integrity-alert",
                    fingerprint + '|' + now.toEpochMilli(),
                    Objects.requireNonNull(alertText),
                    now
            );
            return NotificationStateChange.withNotification(
                    next, alert, IntegrityIncidentTransition.NEW_ALERT);
        });
    }
}
