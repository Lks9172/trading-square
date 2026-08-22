package io.macrosquare.market.adapter.out.yahoo;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectSectorTotalReturnHistoryPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Yahoo adjusted-close adapter used only for dividend-aware sector total returns. */
public final class YahooSectorTotalReturnHistoryAdapter implements CollectSectorTotalReturnHistoryPort {

    public static final Map<String, String> SYMBOLS = symbols();
    // XLC launched in 2018. Nine calendar years asks Yahoo for the maximum
    // common history available across the standard eleven-sector universe and
    // leaves a full formation window before a seven-year walk-forward test.
    private static final long FULL_LOOKBACK_DAYS = 9L * 366L;
    private static final long RECENT_LOOKBACK_DAYS = 45L;

    private final RestClient restClient;
    private final List<URI> baseUrls;
    private final Clock clock;
    private final Executor executor;
    private final Map<String, String> symbols;

    public YahooSectorTotalReturnHistoryAdapter(
            RestClient restClient,
            List<URI> baseUrls,
            Clock clock,
            Executor executor
    ) {
        this(restClient, baseUrls, clock, executor, SYMBOLS);
    }

    YahooSectorTotalReturnHistoryAdapter(
            RestClient restClient,
            List<URI> baseUrls,
            Clock clock,
            Executor executor,
            Map<String, String> symbols
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.baseUrls = List.copyOf(Objects.requireNonNull(baseUrls));
        if (this.baseUrls.isEmpty() || this.baseUrls.stream().anyMatch(uri -> !uri.isAbsolute())) {
            throw new IllegalArgumentException("Yahoo baseUrls must contain absolute URIs");
        }
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.symbols = Map.copyOf(Objects.requireNonNull(symbols));
        if (this.symbols.isEmpty()) throw new IllegalArgumentException("symbols must not be empty");
    }

    @Override
    public MarketCollectionBatch collect(HistoryWindow window) {
        Objects.requireNonNull(window);
        var startedAt = clock.instant();
        var tasks = symbols.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(
                        () -> fetch(entry.getKey(), entry.getValue(), window), executor))
                .toList();
        var observations = new ArrayList<MarketObservation>();
        var failures = new ArrayList<MarketCollectionBatch.Failure>();
        for (var task : tasks) {
            var result = task.join();
            observations.addAll(result.observations());
            if (result.failure() != null) failures.add(result.failure());
        }
        observations.sort(Comparator.comparing(MarketObservation::key)
                .thenComparing(MarketObservation::observationDate));
        return new MarketCollectionBatch(
                MarketDataSource.YAHOO, startedAt, clock.instant(), observations, failures);
    }

    private Result fetch(String key, String symbol, HistoryWindow window) {
        RuntimeException lastError = null;
        var period2 = clock.instant().plusSeconds(86_400).getEpochSecond();
        var lookbackDays = window == HistoryWindow.FULL ? FULL_LOOKBACK_DAYS : RECENT_LOOKBACK_DAYS;
        var period1 = period2 - lookbackDays * 86_400L;
        for (var baseUrl : baseUrls) {
            try {
                var root = restClient.get().uri(chartUri(baseUrl, symbol, period1, period2))
                        .accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
                return new Result(parse(key, symbol, root, window), null);
            } catch (RuntimeException error) {
                lastError = error;
            }
        }
        return new Result(List.of(), new MarketCollectionBatch.Failure(
                key, safeReason(lastError == null ? new IllegalStateException() : lastError)));
    }

    private List<MarketObservation> parse(
            String key,
            String symbol,
            JsonNode root,
            HistoryWindow window
    ) {
        var result = root == null ? null : root.at("/chart/result/0");
        if (result == null || !result.isObject()) throw new IllegalArgumentException("chart result is missing");
        var returnedSymbol = text(result.at("/meta"), "symbol");
        if (!normalizeSymbol(symbol).equals(normalizeSymbol(returnedSymbol))) {
            throw new IllegalArgumentException("chart symbol does not match the request");
        }
        var timestamps = result.get("timestamp");
        var adjusted = result.at("/indicators/adjclose/0/adjclose");
        if (timestamps == null || !timestamps.isArray() || !adjusted.isArray()) {
            throw new IllegalArgumentException("adjusted-close history is missing");
        }
        var limit = Math.min(timestamps.size(), adjusted.size());
        var observations = new ArrayList<MarketObservation>(limit);
        for (var index = 0; index < limit; index++) {
            var timestamp = timestamps.get(index);
            var value = adjusted.get(index);
            if (timestamp == null || !timestamp.isIntegralNumber() || value == null || !value.isNumber()) continue;
            var price = value.asDouble();
            if (!Double.isFinite(price) || price <= 0) continue;
            var date = Instant.ofEpochSecond(timestamp.asLong()).atZone(ZoneOffset.UTC).toLocalDate();
            observations.add(new MarketObservation(
                    key, symbol + ":ADJCLOSE_TOTAL_RETURN", price, date, MarketDataSource.YAHOO));
        }
        if (observations.isEmpty()) throw new IllegalArgumentException("adjusted-close history is empty");
        observations.sort(Comparator.comparing(MarketObservation::observationDate));
        var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        var latest = observations.get(observations.size() - 1).observationDate();
        if (latest.isAfter(today.plusDays(1)) || latest.isBefore(today.minusDays(7))) {
            throw new IllegalArgumentException("adjusted-close history is outside the accepted freshness window");
        }
        var minimum = window == HistoryWindow.FULL ? 1_000 : 5;
        if (observations.size() < minimum) {
            throw new IllegalArgumentException("adjusted-close history has insufficient points");
        }
        return List.copyOf(observations);
    }

    private static URI chartUri(URI baseUrl, String symbol, long period1, long period2) {
        var base = baseUrl.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        var encoded = UriUtils.encodePathSegment(symbol, StandardCharsets.UTF_8);
        return URI.create(base + "/v8/finance/chart/" + encoded
                + "?period1=" + period1 + "&period2=" + period2
                + "&interval=1d&events=div%2Csplits&includeAdjustedClose=true");
    }

    private static String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value.stringValue();
    }

    private static String normalizeSymbol(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return error instanceof IllegalArgumentException
                ? "Malformed provider adjusted-close response"
                : error.getClass().getSimpleName();
    }

    private static Map<String, String> symbols() {
        var values = new LinkedHashMap<String, String>();
        values.put("SPY_TR", "SPY");
        for (var key : List.of(
                "XLK", "XLF", "XLE", "XLV", "XLI", "XLY", "XLC", "XLB",
                "XLRE", "XLU", "XLP", "SOXX", "SMH", "ITA", "GRID", "IGF")) {
            values.put(key + "_TR", key);
        }
        return Map.copyOf(values);
    }

    private record Result(List<MarketObservation> observations, MarketCollectionBatch.Failure failure) {
    }
}
