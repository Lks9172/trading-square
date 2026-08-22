package io.macrosquare.integrity.application.port.out;

import io.macrosquare.integrity.domain.DataIntegrityEvidence;

public interface LoadDataIntegrityEvidencePort {
    DataIntegrityEvidence load();
}
