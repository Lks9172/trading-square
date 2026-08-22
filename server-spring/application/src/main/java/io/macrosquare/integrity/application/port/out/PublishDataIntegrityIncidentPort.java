package io.macrosquare.integrity.application.port.out;

import io.macrosquare.integrity.application.model.IntegrityIncidentTransition;
import io.macrosquare.integrity.domain.DataIntegrityReport;

import java.time.Instant;

public interface PublishDataIntegrityIncidentPort {
    IntegrityIncidentTransition transition(
            DataIntegrityReport report,
            String alertText,
            String recoveryText,
            Instant now
    );
}
