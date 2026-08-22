package io.macrosquare.integrity.adapter.out.notification;

import io.macrosquare.integrity.application.model.IntegrityIncidentTransition;
import io.macrosquare.integrity.domain.DataIntegrityReport;
import io.macrosquare.integrity.domain.DataIntegrityViolation;
import io.macrosquare.notification.application.model.NotificationState;
import io.macrosquare.notification.application.model.NotificationStateChange;
import io.macrosquare.notification.application.model.OutboundNotification;
import io.macrosquare.notification.application.port.out.NotificationStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationIntegrityIncidentAdapterTest {

    @Test
    void alertsOnceRecoversAndAlertsAgainWhenTheSameIncidentRecurs() {
        var repository = new StateRepository();
        var adapter = new NotificationIntegrityIncidentAdapter(repository);
        var incident = new DataIntegrityReport(
                Instant.parse("2026-08-07T08:00:00Z"),
                List.of(new DataIntegrityViolation(
                        "BUY_WITHOUT_EVIDENCE_ROWS", 1, 0, "현재 근거 없이 생성된 BUY 액션")),
                List.of());
        var healthy = new DataIntegrityReport(
                Instant.parse("2026-08-07T08:05:00Z"), List.of(), List.of());

        assertEquals(IntegrityIncidentTransition.NEW_ALERT, adapter.transition(
                incident, "alert", "recovered", Instant.parse("2026-08-07T08:00:00Z")));
        assertEquals(IntegrityIncidentTransition.UNCHANGED_INCIDENT, adapter.transition(
                incident, "alert", "recovered", Instant.parse("2026-08-07T08:01:00Z")));
        assertEquals(1, repository.outbox.size());
        assertEquals(incident.fingerprint(), repository.state.integrityFingerprint());

        assertEquals(IntegrityIncidentTransition.RECOVERED, adapter.transition(
                healthy, "alert", "recovered", Instant.parse("2026-08-07T08:05:00Z")));
        assertEquals("", repository.state.integrityFingerprint());
        assertEquals(2, repository.outbox.size());
        assertEquals("data-integrity-recovery", repository.outbox.get(1).operation());

        assertEquals(IntegrityIncidentTransition.NEW_ALERT, adapter.transition(
                incident, "alert again", "recovered", Instant.parse("2026-08-07T08:10:00Z")));
        assertEquals(3, repository.outbox.size());
        assertEquals("data-integrity-alert", repository.outbox.get(2).operation());
        assertTrue(repository.outbox.get(2).text().contains("again"));
    }

    @Test
    void alertsAgainWhenASecondKeyFailsAtTheSameProviderDuringAnActiveIncident() {
        var repository = new StateRepository();
        var adapter = new NotificationIntegrityIncidentAdapter(repository);
        var at = Instant.parse("2026-08-07T08:00:00Z");
        var usdKrw = new DataIntegrityReport(
                at,
                List.of(new DataIntegrityViolation(
                        "HARD_COLLECTION_FAILURES", 1, 0, "필수 수집 실패")),
                List.of("YAHOO:DEGRADED:USDKRW"));
        var usdJpy = new DataIntegrityReport(
                at.plusSeconds(60),
                usdKrw.violations(),
                List.of("YAHOO:DEGRADED:USDJPY"));

        assertEquals(IntegrityIncidentTransition.NEW_ALERT, adapter.transition(
                usdKrw, "USDKRW alert", "recovered", at));
        assertEquals(IntegrityIncidentTransition.NEW_ALERT, adapter.transition(
                usdJpy, "USDJPY alert", "recovered", at.plusSeconds(60)));

        assertEquals(2, repository.outbox.size());
        assertEquals("data-integrity-alert", repository.outbox.get(0).operation());
        assertEquals("data-integrity-alert", repository.outbox.get(1).operation());
    }

    private static final class StateRepository implements NotificationStateRepository {
        private NotificationState state = NotificationState.empty();
        private final List<OutboundNotification> outbox = new ArrayList<>();

        @Override
        public NotificationState load() {
            return state;
        }

        @Override
        public void save(NotificationState state) {
            this.state = state;
        }

        @Override
        public synchronized <R> R updateAtomically(
                Function<NotificationState, NotificationStateChange<R>> transition
        ) {
            var change = transition.apply(state);
            state = change.state();
            outbox.addAll(change.notifications());
            return change.result();
        }
    }
}
