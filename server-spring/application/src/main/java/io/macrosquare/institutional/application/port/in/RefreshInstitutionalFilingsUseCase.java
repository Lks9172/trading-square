package io.macrosquare.institutional.application.port.in;

import io.macrosquare.institutional.application.model.InstitutionalRefreshReport;

import java.time.Instant;
import java.util.Optional;

public interface RefreshInstitutionalFilingsUseCase {
    InstitutionalRefreshReport refresh();

    /** Returns empty when a durable successful collection is already recent enough. */
    default Optional<InstitutionalRefreshReport> refreshIfStale(Instant freshAfter) {
        return Optional.of(refresh());
    }
}
