package io.macrosquare.market.adapter.out.yahoo;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Direct Yahoo chart-meta collector for the macro and sector universe. */
public final class YahooMarketObservationAdapter implements CollectMarketObservationsPort {

    public static final Map<String, String> SYMBOLS = symbols();
    private static final int FETCH_ATTEMPTS = 2;

    private final RestClient restClient;
    private final List<URI> baseUrls;
    private final Clock clock;
    private final Executor executor;
    private final Map<String, String> symbols;

    public YahooMarketObservationAdapter(
            RestClient restClient,
            List<URI> baseUrls,
            Clock clock,
            Executor executor
    ) {
        this(restClient, baseUrls, clock, executor, SYMBOLS);
    }

    YahooMarketObservationAdapter(
            RestClient restClient,
            List<URI> baseUrls,
            Clock clock,
            Executor executor,
            Map<String, String> symbols
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.baseUrls = List.copyOf(Objects.requireNonNull(baseUrls));
        if (this.baseUrls.isEmpty()) throw new IllegalArgumentException("Yahoo baseUrls must not be empty");
        this.clock = Objects.requireNonNull(clock);
        this.executor = Objects.requireNonNull(executor);
        this.symbols = Map.copyOf(Objects.requireNonNull(symbols));
        if (this.symbols.isEmpty()) throw new IllegalArgumentException("symbols must not be empty");
    }

    @Override
    public MarketDataSource source() {
        return MarketDataSource.YAHOO;
    }

    @Override
    public MarketCollectionBatch collect() {
        var startedAt = clock.instant();
        var tasks = symbols.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> fetch(entry.getKey(), entry.getValue()), executor))
                .toList();
        var observations = new ArrayList<MarketObservation>();
        var failures = new ArrayList<MarketCollectionBatch.Failure>();
        for (var task : tasks) {
            var result = task.join();
            observations.addAll(result.observations());
            if (result.failure() != null) failures.add(result.failure());
        }
        return new MarketCollectionBatch(source(), startedAt, clock.instant(), observations, failures);
    }

    private Result fetch(String key, String symbol) {
        RuntimeException lastError = null;
        // Yahoo occasionally returns a syntactically valid chart envelope with
        // incomplete FX metadata from both query hosts during the same pass.
        // Retry the bounded host set once before publishing a source gap. The
        // shared request throttle still paces every attempt process-wide.
        for (var attempt = 0; attempt < FETCH_ATTEMPTS; attempt++) {
            for (var baseUrl : baseUrls) {
                try {
                    var uri = baseUrl.resolve("/v8/finance/chart/" + encodePath(symbol) + "?range=5d&interval=1d");
                    var root = restClient.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                            .retrieve().body(JsonNode.class);
                    var meta = root == null ? null : root.at("/chart/result/0/meta");
                    if (meta == null || !meta.isObject()) throw new IllegalArgumentException("chart meta is missing");
                    var returnedSymbol = text(meta, "symbol");
                    if (!matchesRequestedSymbol(symbol, returnedSymbol)) {
                        throw new IllegalArgumentException("chart symbol does not match the request");
                    }
                    var price = number(meta, "regularMarketPrice");
                    var epochSeconds = integral(meta, "regularMarketTime");
                    var date = Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
                    var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
                    if (date.isAfter(today.plusDays(1)) || date.isBefore(today.minusDays(7))) {
                        throw new IllegalArgumentException("regularMarketTime is outside the accepted collection window");
                    }
                    var values = new ArrayList<MarketObservation>();
                    values.add(new MarketObservation(key, symbol, price, date, source()));
                    var high = optionalNumber(meta, "fiftyTwoWeekHigh");
                    if (high != null && high > 0) {
                        if (price > high * 1.05) {
                            throw new IllegalArgumentException("fiftyTwoWeekHigh is inconsistent with current price");
                        }
                        values.add(new MarketObservation(key + "_52WH", symbol + "_52WH", high, date, source()));
                    }
                    return new Result(List.copyOf(values), null);
                } catch (RuntimeException error) {
                    lastError = error;
                }
            }
        }
        return new Result(List.of(), new MarketCollectionBatch.Failure(
                key, safeReason(lastError == null ? new IllegalStateException() : lastError)));
    }

    private static String encodePath(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static double number(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) throw new IllegalArgumentException(field + " is missing");
        var result = value.asDouble();
        if (!Double.isFinite(result) || result <= 0) throw new IllegalArgumentException(field + " is invalid");
        return result;
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return value.stringValue();
    }

    private static Long integral(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber()) throw new IllegalArgumentException(field + " is missing");
        return value.asLong();
    }

    private static Double optionalNumber(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isNumber()) return null;
        var result = value.asDouble();
        return Double.isFinite(result) ? result : null;
    }

    private static String normalizeSymbol(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    /**
     * Yahoo legitimately alternates FX metadata between the requested shorthand
     * (for example {@code JPY=X}) and its explicit USD-base alias
     * ({@code USDJPY=X}). Both represent the same quote and value direction.
     * Keep every non-FX symbol strict so a provider cross-symbol response can
     * never enter the normalized market series.
     */
    private static boolean matchesRequestedSymbol(String requested, String returned) {
        var expected = normalizeSymbol(requested);
        var actual = normalizeSymbol(returned);
        if (expected.equals(actual)) return true;
        if (expected.matches("[A-Z]{3}=X")) {
            return ("USD" + expected).equals(actual);
        }
        return false;
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return error instanceof IllegalArgumentException ? "Malformed provider response" : error.getClass().getSimpleName();
    }

    private static Map<String, String> symbols() {
        var values = new LinkedHashMap<String, String>();
        values.put("SP500", "^GSPC");
        values.put("NASDAQ", "^IXIC");
        values.put("KOSPI", "^KS11");
        values.put("KOSDAQ", "^KQ11");
        values.put("SAMSUNG", "005930.KS");
        values.put("GOLD", "GC=F");
        values.put("SILVER", "SI=F");
        values.put("COPPER", "HG=F");
        values.put("WTI", "CL=F");
        values.put("DXY", "DX-Y.NYB");
        values.put("USDJPY", "JPY=X");
        values.put("USDKRW", "KRW=X");
        values.put("EWZ", "EWZ");
        values.put("INDA", "INDA");
        values.put("VNM", "VNM");
        values.put("EWJ", "EWJ");
        values.put("XLK", "XLK");
        values.put("XLF", "XLF");
        values.put("XLE", "XLE");
        values.put("XLV", "XLV");
        values.put("XLI", "XLI");
        values.put("XLY", "XLY");
        values.put("XLC", "XLC");
        values.put("XLB", "XLB");
        values.put("XLRE", "XLRE");
        values.put("XLU", "XLU");
        values.put("XLP", "XLP");
        values.put("SOXX", "SOXX");
        values.put("SMH", "SMH");
        values.put("ITA", "ITA");
        values.put("GRID", "GRID");
        values.put("IGF", "IGF");
        values.put("TQQQ", "TQQQ");
        values.put("NQ_FUTURES", "NQ=F");
        values.put("ES_FUTURES", "ES=F");
        values.put("SKEW", "^SKEW");
        values.put("VVIX", "^VVIX");
        values.put("OVX", "^OVX");
        values.put("HYG", "HYG");
        values.put("IEF", "IEF");
        values.put("BTC", "BTC-USD");
        values.put("ETH", "ETH-USD");
        values.put("SOL", "SOL-USD");
        values.put("XRP", "XRP-USD");
        values.put("BNB", "BNB-USD");
        return Map.copyOf(values);
    }

    private record Result(List<MarketObservation> observations, MarketCollectionBatch.Failure failure) {
    }
}
