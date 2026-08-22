package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystEvidenceUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistorySeedPort;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable cutover seed loaded from MinIO rather than a host filesystem mount. */
public final class ObjectCompanyAnalystHistorySeedAdapter implements LoadCompanyAnalystHistorySeedPort {

    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;

    public ObjectCompanyAnalystHistorySeedAdapter(ObjectStorage storage, ObjectMapper objectMapper) {
        this.storage = Objects.requireNonNull(storage);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    @Override
    public List<CompanyAnalystHistoryPoint> load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        var key = "source-cache/company-analyst-history-" + ticker.toLowerCase(Locale.ROOT) + ".json";
        try {
            var object = storage.find(key, MAX_BYTES);
            if (object.isEmpty()) return List.of();
            try (var input = new ByteArrayInputStream(object.get().content());
                 var parser = objectMapper.createParser(input)) {
                return LegacyCompanyAnalystEvidenceMapper.mapHistory(parser);
            }
        } catch (Exception error) {
            throw new CompanyAnalystEvidenceUnavailableException(
                    "Unable to load object-stored company analyst history", error);
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
