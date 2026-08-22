package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.application.model.MarketHistorySeedSeries;
import io.macrosquare.market.application.port.out.LoadMarketHistorySeedPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Bounded read-only adapter for the historical JSON arrays produced before cutover. */
public final class LegacyMarketHistorySeedAdapter implements LoadMarketHistorySeedPort {

    private final ObjectMapper objectMapper;
    private final Path directory;
    private final long maximumFileBytes;
    private final int maximumPointsPerSeries;
    private final Map<MarketDataSource, Map<String, String>> providerCodes;

    public LegacyMarketHistorySeedAdapter(
            ObjectMapper objectMapper,
            Path directory,
            long maximumFileBytes,
            int maximumPointsPerSeries,
            Map<MarketDataSource, Map<String, String>> providerCodes
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.directory = absolute(directory);
        if (maximumFileBytes <= 0) throw new IllegalArgumentException("maximumFileBytes must be positive");
        if (maximumPointsPerSeries <= 0 || maximumPointsPerSeries > 20_000) {
            throw new IllegalArgumentException("maximumPointsPerSeries is invalid");
        }
        this.maximumFileBytes = maximumFileBytes;
        this.maximumPointsPerSeries = maximumPointsPerSeries;
        var copy = new LinkedHashMap<MarketDataSource, Map<String, String>>();
        Objects.requireNonNull(providerCodes).forEach((source, values) -> copy.put(source, Map.copyOf(values)));
        this.providerCodes = Map.copyOf(copy);
    }

    @Override
    public List<MarketHistorySeedSeries> listAvailableSeries() {
        if (!Files.isDirectory(directory)) return List.of();
        var result = new ArrayList<MarketHistorySeedSeries>();
        providerCodes.forEach((source, values) -> values.forEach((key, providerCode) -> {
            if (Files.isRegularFile(path(source, key))) {
                result.add(new MarketHistorySeedSeries(source, key, providerCode));
            }
        }));
        return result.stream()
                .sorted(Comparator.comparing((MarketHistorySeedSeries item) -> item.source().name())
                        .thenComparing(MarketHistorySeedSeries::key))
                .toList();
    }

    @Override
    public List<MarketObservation> load(MarketHistorySeedSeries series) {
        Objects.requireNonNull(series);
        var expectedProvider = providerCodes.getOrDefault(series.source(), Map.of()).get(series.key());
        if (!series.providerCode().equals(expectedProvider)) {
            throw new IllegalArgumentException("unknown seed series");
        }
        var path = path(series.source(), series.key());
        try {
            var size = Files.size(path);
            if (size <= 0 || size > maximumFileBytes) {
                throw new IllegalArgumentException("history seed file exceeds its bound");
            }
            var root = objectMapper.readTree(Files.readAllBytes(path));
            if (root == null || !root.isArray()) throw new IllegalArgumentException("history seed must be an array");
            var byDate = new LinkedHashMap<LocalDate, MarketObservation>();
            for (var item : root) {
                if (!item.isObject()) throw new IllegalArgumentException("history point must be an object");
                var dateNode = item.get("date");
                var valueNode = item.get("value");
                if (dateNode == null || !dateNode.isString() || valueNode == null || !valueNode.isNumber()) {
                    throw new IllegalArgumentException("history point is malformed");
                }
                var value = valueNode.asDouble();
                if (!Double.isFinite(value)) throw new IllegalArgumentException("history value must be finite");
                var date = LocalDate.parse(dateNode.stringValue());
                byDate.put(date, new MarketObservation(
                        series.key(), series.providerCode(), value, date, series.source()));
            }
            return byDate.values().stream()
                    .sorted(Comparator.comparing(MarketObservation::observationDate))
                    .skip(Math.max(0, byDate.size() - maximumPointsPerSeries))
                    .toList();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read bounded market history seed", error);
        }
    }

    private Path path(MarketDataSource source, String key) {
        if (key == null || !key.matches("[A-Z0-9_]+")) throw new IllegalArgumentException("invalid seed key");
        var prefix = switch (source) {
            case FRED -> "fred-";
            case YAHOO -> "yahoo-";
            default -> throw new IllegalArgumentException("unsupported seed source");
        };
        var candidate = directory.resolve(prefix + key.toLowerCase(Locale.ROOT) + ".json").normalize();
        if (!candidate.startsWith(directory)) throw new IllegalArgumentException("seed path escapes directory");
        return candidate;
    }

    private static Path absolute(Path path) {
        Objects.requireNonNull(path, "directory");
        if (!path.isAbsolute()) throw new IllegalArgumentException("directory must be absolute");
        return path.normalize();
    }
}
