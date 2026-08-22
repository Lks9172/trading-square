package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.model.CompanyMarketCapitalization;
import io.macrosquare.company.application.port.out.CompanyMarketCapitalizationUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyMarketCapitalizationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/** Read-only, bounded Yahoo fundamentals-timeseries adapter. */
public final class YahooCompanyMarketCapitalizationAdapter implements LoadCompanyMarketCapitalizationPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(YahooCompanyMarketCapitalizationAdapter.class);
    private static final int MAX_ENTRIES = 512;
    private static final int LOCK_STRIPES = 64;
    private static final Map<String, String> SYMBOL_OVERRIDES = Map.of("MMC", "MRSH");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<URI> baseUrls;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration staleTtl;
    private final Semaphore permits;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();
    private final ReentrantLock[] tickerLocks = createTickerLocks();

    public YahooCompanyMarketCapitalizationAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            List<URI> baseUrls,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            int maxConcurrentFetches
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.baseUrls = List.copyOf(Objects.requireNonNull(baseUrls, "baseUrls"));
        if (this.baseUrls.isEmpty() || this.baseUrls.stream().anyMatch(url -> url == null || !url.isAbsolute())) {
            throw new IllegalArgumentException("Yahoo baseUrls must contain absolute URIs");
        }
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtl = requireNonNegative(cacheTtl, "cacheTtl");
        this.staleTtl = Objects.requireNonNull(staleTtl);
        if (staleTtl.compareTo(cacheTtl) < 0) {
            throw new IllegalArgumentException("staleTtl must be greater than or equal to cacheTtl");
        }
        if (maxConcurrentFetches < 1) throw new IllegalArgumentException("maxConcurrentFetches must be positive");
        this.permits = new Semaphore(maxConcurrentFetches, true);
    }

    @Override
    public CompanyMarketCapitalization load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        var now = clock.instant();
        var current = cache.get(ticker);
        if (current != null && now.isBefore(current.loadedAt().plus(cacheTtl))) return current.value();

        var lock = tickerLocks[Math.floorMod(ticker.hashCode(), tickerLocks.length)];
        lock.lock();
        try {
            now = clock.instant();
            current = cache.get(ticker);
            if (current != null && now.isBefore(current.loadedAt().plus(cacheTtl))) return current.value();
            try {
                var loaded = fetch(ticker);
                cache.put(ticker, new CachedValue(loaded, clock.instant()));
                evictOldest();
                return loaded;
            } catch (RuntimeException error) {
                if (current != null && now.isBefore(current.loadedAt().plus(staleTtl))) {
                    LOGGER.warn("Unable to refresh Yahoo market cap for {}; using bounded stale value", ticker, error);
                    return current.value();
                }
                throw error;
            }
        } finally {
            lock.unlock();
        }
    }

    private static ReentrantLock[] createTickerLocks() {
        var locks = new ReentrantLock[LOCK_STRIPES];
        for (var index = 0; index < locks.length; index++) locks[index] = new ReentrantLock(true);
        return locks;
    }

    private CompanyMarketCapitalization fetch(String ticker) {
        var acquired = false;
        try {
            permits.acquire();
            acquired = true;
            return fetchWithFallback(ticker);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CompanyMarketCapitalizationUnavailableException(
                    "Interrupted while loading Yahoo market capitalization", error);
        } finally {
            if (acquired) permits.release();
        }
    }

    private CompanyMarketCapitalization fetchWithFallback(String ticker) {
        RuntimeException lastFailure = null;
        var symbol = SYMBOL_OVERRIDES.getOrDefault(ticker, ticker);
        for (var baseUrl : baseUrls) {
            try {
                var value = restClient.get()
                        .uri(timeseriesUri(baseUrl, symbol))
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange((request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                            return YahooCompanyMarketCapitalizationMapper.map(
                                    objectMapper.readTree(response.getBody()), ticker, symbol);
                        });
                if (value == null) throw new IllegalArgumentException("Yahoo market capitalization was empty");
                var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                if (value.date().isAfter(today.plusDays(1)) || value.date().isBefore(today.minusDays(35))) {
                    throw new IllegalArgumentException("Yahoo market-cap date is outside the accepted window");
                }
                return value.withReferencePrice(fetchReferenceClose(baseUrl, symbol, value.date()));
            } catch (RestClientException | JacksonException | IllegalArgumentException error) {
                lastFailure = error;
            }
        }
        throw new CompanyMarketCapitalizationUnavailableException(
                "Unable to load Yahoo company market capitalization", lastFailure);
    }

    private double fetchReferenceClose(URI baseUrl, String symbol, LocalDate observationDate) {
        var response = restClient.get()
                .uri(chartUri(baseUrl, symbol, observationDate))
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, httpResponse) -> {
                    if (!httpResponse.getStatusCode().is2xxSuccessful()) throw httpResponse.createException();
                    return objectMapper.readTree(httpResponse.getBody());
                });
        var chart = response == null ? null : response.get("chart");
        var results = chart == null ? null : chart.get("result");
        if (results == null || !results.isArray() || results.isEmpty()) {
            throw new IllegalArgumentException("Yahoo reference-price response has no result");
        }
        var result = results.get(0);
        var meta = result == null ? null : result.get("meta");
        var returnedSymbol = meta == null ? null : meta.get("symbol");
        if (returnedSymbol == null || !returnedSymbol.isString()
                || !normalizeSymbol(symbol).equals(normalizeSymbol(returnedSymbol.stringValue()))) {
            throw new IllegalArgumentException("Yahoo reference-price response symbol mismatch");
        }
        var timestamps = result.get("timestamp");
        var indicators = result.get("indicators");
        var quotes = indicators == null ? null : indicators.get("quote");
        var closes = quotes == null || !quotes.isArray() || quotes.isEmpty()
                ? null
                : quotes.get(0).get("close");
        if (timestamps == null || closes == null || !timestamps.isArray() || !closes.isArray()) {
            throw new IllegalArgumentException("Yahoo reference-price response has no price series");
        }
        Double selected = null;
        LocalDate selectedDate = null;
        var count = Math.min(timestamps.size(), closes.size());
        for (var index = 0; index < count; index++) {
            var timestamp = timestamps.get(index);
            var close = closes.get(index);
            if (timestamp == null || !timestamp.isNumber() || close == null || !close.isNumber()) continue;
            var value = close.doubleValue();
            if (!Double.isFinite(value) || value <= 0) continue;
            var date = Instant.ofEpochSecond(timestamp.longValue()).atZone(ZoneOffset.UTC).toLocalDate();
            if (date.isAfter(observationDate)) continue;
            if (selectedDate == null || date.isAfter(selectedDate)) {
                selectedDate = date;
                selected = value;
            }
        }
        if (selected == null || selectedDate.isBefore(observationDate.minusDays(7))) {
            throw new IllegalArgumentException("Yahoo reference-price response has no fresh usable close");
        }
        return selected;
    }

    private URI timeseriesUri(URI baseUrl, String symbol) {
        var base = baseUrl.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        var encoded = UriUtils.encodePathSegment(symbol, StandardCharsets.UTF_8);
        var period2 = clock.instant().getEpochSecond() + Duration.ofDays(2).toSeconds();
        var period1 = period2 - Duration.ofDays(30).toSeconds();
        return URI.create(base + "/ws/fundamentals-timeseries/v1/finance/timeseries/" + encoded
                + "?symbol=" + encoded + "&type=trailingMarketCap&period1=" + period1 + "&period2=" + period2);
    }

    private static URI chartUri(URI baseUrl, String symbol, LocalDate observationDate) {
        var base = baseUrl.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        var encoded = UriUtils.encodePathSegment(symbol, StandardCharsets.UTF_8);
        var period1 = observationDate.minusDays(5).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        var period2 = observationDate.plusDays(2).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return URI.create(base + "/v8/finance/chart/" + encoded
                + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d&events=history");
    }

    private void evictOldest() {
        while (cache.size() > MAX_ENTRIES) {
            var oldest = cache.entrySet().stream()
                    .min(Map.Entry.comparingByValue((left, right) -> left.loadedAt().compareTo(right.loadedAt())))
                    .orElse(null);
            if (oldest == null) return;
            cache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static Duration requireNonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    private static String normalizeSymbol(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private record CachedValue(CompanyMarketCapitalization value, java.time.Instant loadedAt) {
    }
}
