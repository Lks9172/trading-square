package io.macrosquare.integrity.application.port.in;

import io.macrosquare.integrity.application.model.DataIntegrityCheckResult;

public interface CheckDataIntegrityUseCase {
    DataIntegrityCheckResult check(String trigger);
}
