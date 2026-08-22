package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystEvidenceUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistorySeedPort;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only ACL over Node's atomically persisted per-ticker analyst history. */
public final class LegacySourceCacheCompanyAnalystHistoryAdapter implements LoadCompanyAnalystHistorySeedPort {

    private static final Pattern SAFE_TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,19}");

    private final ObjectMapper objectMapper;
    private final Path sourceCacheDirectory;

    public LegacySourceCacheCompanyAnalystHistoryAdapter(
            ObjectMapper objectMapper,
            Path sourceCacheDirectory
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.sourceCacheDirectory = Objects.requireNonNull(sourceCacheDirectory, "sourceCacheDirectory")
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public List<CompanyAnalystHistoryPoint> load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        var fileName = "company-analyst-history-" + ticker.toLowerCase(Locale.ROOT) + ".json";
        var path = sourceCacheDirectory.resolve(fileName);
        try (var input = Files.newInputStream(path); var parser = objectMapper.createParser(input)) {
            return LegacyCompanyAnalystEvidenceMapper.mapHistory(parser);
        } catch (NoSuchFileException ignored) {
            return List.of();
        } catch (Exception error) {
            throw new CompanyAnalystEvidenceUnavailableException(
                    "Unable to load persisted company analyst history",
                    error
            );
        }
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        var normalized = ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
        if (!SAFE_TICKER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("ticker contains unsupported characters");
        }
        return normalized;
    }
}
