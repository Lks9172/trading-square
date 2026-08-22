package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.port.out.CompanyAnalystHistoryPersistenceException;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistoryStorePort;
import io.macrosquare.company.application.port.out.SaveCompanyAnalystHistoryPort;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Atomic file persistence for the application-owned analyst-history store. */
public final class FileCompanyAnalystHistoryStoreAdapter
        implements LoadCompanyAnalystHistoryStorePort, SaveCompanyAnalystHistoryPort {

    private static final Pattern SAFE_TICKER = Pattern.compile("[A-Z0-9][A-Z0-9.-]{0,19}");
    private static final int LOCK_STRIPES = 32;

    private final ObjectMapper objectMapper;
    private final Path directory;
    private final Lock[] locks;

    public FileCompanyAnalystHistoryStoreAdapter(ObjectMapper objectMapper, Path directory) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
        this.locks = new Lock[LOCK_STRIPES];
        for (var index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
    }

    @Override
    public Optional<List<CompanyAnalystHistoryPoint>> load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        var lock = lockFor(ticker);
        lock.lock();
        try {
            var path = historyPath(ticker);
            try (var input = Files.newInputStream(path); var parser = objectMapper.createParser(input)) {
                return Optional.of(LegacyCompanyAnalystEvidenceMapper.mapHistory(parser));
            } catch (NoSuchFileException ignored) {
                return Optional.empty();
            } catch (Exception error) {
                throw new CompanyAnalystHistoryPersistenceException(
                        "Unable to load analyst history",
                        error
                );
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void save(String normalizedTicker, List<CompanyAnalystHistoryPoint> history, Instant updatedAt) {
        var ticker = normalizeTicker(normalizedTicker);
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(updatedAt, "updatedAt");
        var lock = lockFor(ticker);
        lock.lock();
        try {
            writeAtomically(ticker, history, updatedAt);
        } finally {
            lock.unlock();
        }
    }

    private void writeAtomically(
            String ticker,
            List<CompanyAnalystHistoryPoint> history,
            Instant updatedAt
    ) {
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            var finalPath = historyPath(ticker);
            temporary = Files.createTempFile(directory, finalPath.getFileName() + ".tmp-", ".json");
            var bytes = objectMapper.writeValueAsBytes(envelope(ticker, history, updatedAt));
            try (var channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        finalPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (Exception error) {
            throw new CompanyAnalystHistoryPersistenceException(
                    "Unable to persist analyst history",
                    error
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // The final exception already describes the failed atomic write.
                }
            }
        }
    }

    private static LinkedHashMap<String, Object> envelope(
            String ticker,
            List<CompanyAnalystHistoryPoint> history,
            Instant updatedAt
    ) {
        var values = new ArrayList<LinkedHashMap<String, Object>>(history.size());
        for (var point : history) {
            Objects.requireNonNull(point, "history point");
            var value = new LinkedHashMap<String, Object>();
            value.put("date", point.date().toString());
            value.put("analystScore", point.analystScore());
            value.put("upsidePct", point.upsidePct());
            value.put("epsEstimateRevision7dPct", point.epsEstimateRevision7dPct());
            value.put("epsEstimateRevision30dPct", point.epsEstimateRevision30dPct());
            value.put("epsEstimateRevision90dPct", point.epsEstimateRevision90dPct());
            values.add(value);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", 2);
        result.put("key", "company-analyst-history-" + ticker);
        result.put("updatedAt", updatedAt.toString());
        result.put("value", values);
        return result;
    }

    private Path historyPath(String ticker) {
        return directory.resolve(
                "company-analyst-history-" + ticker.toLowerCase(Locale.ROOT) + ".json"
        );
    }

    private Lock lockFor(String ticker) {
        return locks[Math.floorMod(ticker.hashCode(), locks.length)];
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
