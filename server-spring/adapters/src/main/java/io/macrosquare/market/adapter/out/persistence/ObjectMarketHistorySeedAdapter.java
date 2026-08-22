package io.macrosquare.market.adapter.out.persistence;

import io.macrosquare.market.application.model.MarketHistorySeedSeries;
import io.macrosquare.market.application.port.out.LoadMarketHistorySeedPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable pre-cutover market history read from MinIO for idempotent PostgreSQL seeding. */
public final class ObjectMarketHistorySeedAdapter implements LoadMarketHistorySeedPort {

    private static final String PREFIX = "legacy-history/";

    private final ObjectStorage storage;
    private final ObjectMapper objectMapper;
    private final long maximumObjectBytes;
    private final int maximumPointsPerSeries;
    private final Map<MarketDataSource, Map<String, String>> providerCodes;

    public ObjectMarketHistorySeedAdapter(
            ObjectStorage storage,
            ObjectMapper objectMapper,
            long maximumObjectBytes,
            int maximumPointsPerSeries,
            Map<MarketDataSource, Map<String, String>> providerCodes
    ) {
        this.storage = Objects.requireNonNull(storage);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        if (maximumObjectBytes <= 0 || maximumPointsPerSeries <= 0 || maximumPointsPerSeries > 20_000) {
            throw new IllegalArgumentException("market history seed bounds are invalid");
        }
        this.maximumObjectBytes = maximumObjectBytes;
        this.maximumPointsPerSeries = maximumPointsPerSeries;
        var copy = new LinkedHashMap<MarketDataSource, Map<String, String>>();
        Objects.requireNonNull(providerCodes).forEach((source, values) -> copy.put(source, Map.copyOf(values)));
        this.providerCodes = Map.copyOf(copy);
    }

    @Override
    public List<MarketHistorySeedSeries> listAvailableSeries() {
        // The object-store port deliberately caps a single listing at 10k to
        // prevent unbounded startup scans. The configured market seed universe
        // is only a few dozen series, so one bounded listing is sufficient.
        var keys = new java.util.HashSet<>(storage.list(PREFIX, 10_000));
        var result = new ArrayList<MarketHistorySeedSeries>();
        providerCodes.forEach((source, values) -> values.forEach((key, providerCode) -> {
            if (keys.contains(objectKey(source, key))) {
                result.add(new MarketHistorySeedSeries(source, key, providerCode));
            }
        }));
        return result.stream().sorted(Comparator
                .comparing((MarketHistorySeedSeries item) -> item.source().name())
                .thenComparing(MarketHistorySeedSeries::key)).toList();
    }

    @Override
    public List<MarketObservation> load(MarketHistorySeedSeries series) {
        Objects.requireNonNull(series);
        var expectedProvider = providerCodes.getOrDefault(series.source(), Map.of()).get(series.key());
        if (!series.providerCode().equals(expectedProvider)) throw new IllegalArgumentException("unknown seed series");
        var bytes = storage.find(objectKey(series.source(), series.key()), maximumObjectBytes)
                .orElseThrow(() -> new IllegalStateException("market history seed object is unavailable"))
                .content();
        var root = objectMapper.readTree(bytes);
        if (root == null || !root.isArray()) throw new IllegalArgumentException("history seed must be an array");
        var byDate = new LinkedHashMap<LocalDate, MarketObservation>();
        for (var item : root) {
            var dateNode = item.get("date");
            var valueNode = item.get("value");
            if (!item.isObject() || dateNode == null || !dateNode.isString()
                    || valueNode == null || !valueNode.isNumber()) {
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
    }

    private static String objectKey(MarketDataSource source, String key) {
        if (key == null || !key.matches("[A-Z0-9_]+")) throw new IllegalArgumentException("invalid seed key");
        var filePrefix = switch (source) {
            case FRED -> "fred-";
            case YAHOO -> "yahoo-";
            default -> throw new IllegalArgumentException("unsupported seed source");
        };
        return PREFIX + filePrefix + key.toLowerCase(Locale.ROOT) + ".json";
    }
}
