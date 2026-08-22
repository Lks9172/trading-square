package io.macrosquare.institutional.application.port.out;

import io.macrosquare.institutional.domain.model.InstitutionalFiling;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InstitutionalFilingRepository {
    int save(List<InstitutionalFiling> filings);

    List<InstitutionalFiling> loadLatestPerManager(int filingLimit);

    /** Latest common collection time only when every requested manager has durable evidence. */
    Optional<Instant> latestCollectedAt(List<String> managerCiks);
}
