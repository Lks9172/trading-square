package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystEvidenceUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only persisted fallback used only when direct Yahoo collection fails. */
public final class LegacySourceCacheCompanyAnalystConsensusAdapter implements LoadCompanyAnalystConsensusPort {

    private static final String CONSENSUS_FILE = "analyst-consensus-nasdaq-megacap.json";
    private static final Pattern SAFE_TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,19}");

    private final ObjectMapper objectMapper;
    private final Path sourceCacheDirectory;
    private final Clock clock;
    private final Duration staleTtl;

    public LegacySourceCacheCompanyAnalystConsensusAdapter(
            ObjectMapper objectMapper,
            Path sourceCacheDirectory,
            Clock clock,
            Duration staleTtl
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.sourceCacheDirectory = Objects.requireNonNull(sourceCacheDirectory, "sourceCacheDirectory")
                .toAbsolutePath()
                .normalize();
        this.clock = Objects.requireNonNull(clock);
        this.staleTtl = Objects.requireNonNull(staleTtl, "staleTtl");
        if (staleTtl.isNegative()) throw new IllegalArgumentException("staleTtl must not be negative");
    }

    @Override
    public CompanyAnalystConsensus load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        var path = sourceCacheDirectory.resolve(CONSENSUS_FILE);
        try (var input = Files.newInputStream(path); var parser = objectMapper.createParser(input)) {
            var current = LegacyCompanyAnalystEvidenceMapper.mapConsensus(
                    parser,
                    ticker,
                    clock.instant(),
                    staleTtl
            );
            return new CompanyAnalystConsensus(current.analystScore(), current.upsidePct());
        } catch (NoSuchFileException error) {
            throw new CompanyAnalystEvidenceUnavailableException(
                    "Persisted analyst consensus snapshot is unavailable",
                    error
            );
        } catch (CompanyAnalystEvidenceUnavailableException error) {
            throw error;
        } catch (Exception error) {
            throw new CompanyAnalystEvidenceUnavailableException(
                    "Unable to load persisted analyst consensus",
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
