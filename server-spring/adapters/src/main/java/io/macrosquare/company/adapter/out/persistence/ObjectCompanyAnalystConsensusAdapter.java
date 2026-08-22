package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystEvidenceUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Last-valid analyst fallback whose source document is retained in MinIO. */
public final class ObjectCompanyAnalystConsensusAdapter implements LoadCompanyAnalystConsensusPort {

    private static final String KEY = "source-cache/analyst-consensus-nasdaq-megacap.json";
    private static final long MAX_BYTES = 16L * 1024 * 1024;

    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration staleTtl;

    public ObjectCompanyAnalystConsensusAdapter(
            ObjectStorage storage,
            ObjectMapper objectMapper,
            Clock clock,
            Duration staleTtl
    ) {
        this.storage = Objects.requireNonNull(storage);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.staleTtl = Objects.requireNonNull(staleTtl);
    }

    @Override
    public CompanyAnalystConsensus load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        try {
            var object = storage.find(KEY, MAX_BYTES).orElseThrow(() ->
                    new CompanyAnalystEvidenceUnavailableException(
                            "Persisted analyst consensus object is unavailable"));
            try (var input = new ByteArrayInputStream(object.content());
                 var parser = objectMapper.createParser(input)) {
                var current = LegacyCompanyAnalystEvidenceMapper.mapConsensus(
                        parser, ticker, clock.instant(), staleTtl);
                return new CompanyAnalystConsensus(current.analystScore(), current.upsidePct());
            }
        } catch (CompanyAnalystEvidenceUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw new CompanyAnalystEvidenceUnavailableException(
                    "Unable to load object-stored analyst consensus", error);
        }
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        var normalized = ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
        if (!normalized.matches("[A-Z0-9][A-Z0-9.-]{0,19}")) {
            throw new IllegalArgumentException("ticker contains unsupported characters");
        }
        return normalized;
    }
}
