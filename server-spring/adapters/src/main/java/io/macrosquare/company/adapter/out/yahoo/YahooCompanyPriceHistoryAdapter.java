package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.port.out.CompanyPriceHistoryUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyPriceHistoryPort;
import io.macrosquare.company.domain.bottom.BottomPatternPoint;
import io.macrosquare.company.domain.bottom.CompanyPriceHistoryQualityPolicy;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/** Read-only Yahoo daily history adapter with bounded stale-while-revalidate caching. */
public final class YahooCompanyPriceHistoryAdapter implements LoadCompanyPriceHistoryPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(YahooCompanyPriceHistoryAdapter.class);
    private static final int DEFAULT_MAX_ENTRIES = 512;
    private static final Map<String, String> SYMBOL_OVERRIDES = Map.of("MMC", "MRSH");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<URI> baseUrls;
    private final Clock clock;
    private final int lookbackDays;
    private final Duration cacheTtl;
    private final Duration staleTtl;
    private final Executor refreshExecutor;
    private final int maxEntries;
    private final Semaphore fetchPermits;
    private final CompanyPriceHistoryQualityPolicy historyQualityPolicy =
            new CompanyPriceHistoryQualityPolicy();
    private final ConcurrentHashMap<String, CachedHistory> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<List<BottomPatternPoint>>> inFlight =
            new ConcurrentHashMap<>();

    public YahooCompanyPriceHistoryAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            List<URI> baseUrls,
            Clock clock,
            int lookbackDays,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor,
            int maxConcurrentFetches
    ) {
        this(
                restClient, objectMapper, baseUrls, clock, lookbackDays, cacheTtl, staleTtl,
                refreshExecutor, DEFAULT_MAX_ENTRIES, maxConcurrentFetches
        );
    }

    YahooCompanyPriceHistoryAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            List<URI> baseUrls,
            Clock clock,
            int lookbackDays,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor,
            int maxEntries,
            int maxConcurrentFetches
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.baseUrls = List.copyOf(Objects.requireNonNull(baseUrls, "baseUrls"));
        if (this.baseUrls.isEmpty() || this.baseUrls.stream().anyMatch(baseUrl -> !baseUrl.isAbsolute())) {
            throw new IllegalArgumentException("Yahoo baseUrls must contain absolute URIs");
        }
        this.clock = Objects.requireNonNull(clock);
        if (lookbackDays < 120) throw new IllegalArgumentException("lookbackDays must be at least 120");
        this.lookbackDays = lookbackDays;
        this.cacheTtl = requireNonNegative(cacheTtl, "cacheTtl");
        this.staleTtl = Objects.requireNonNull(staleTtl);
        if (staleTtl.compareTo(cacheTtl) < 0) {
            throw new IllegalArgumentException("staleTtl must be greater than or equal to cacheTtl");
        }
        this.refreshExecutor = Objects.requireNonNull(refreshExecutor);
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        if (maxConcurrentFetches < 1) throw new IllegalArgumentException("maxConcurrentFetches must be positive");
        this.maxEntries = maxEntries;
        this.fetchPermits = new Semaphore(maxConcurrentFetches, true);
    }

    @Override
    public List<BottomPatternPoint> load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        var current = cache.get(ticker);
        var now = clock.instant();
        if (isFresh(current, now)) return current.history();
        if (isUsableStale(current, now)) {
            refreshInBackground(ticker);
            return current.history();
        }
        return loadSynchronously(ticker);
    }

    private List<BottomPatternPoint> loadSynchronously(String ticker) {
        var pending = new CompletableFuture<List<BottomPatternPoint>>();
        var existing = inFlight.putIfAbsent(ticker, pending);
        if (existing != null) return await(existing);
        try {
            var current = cache.get(ticker);
            if (isFresh(current, clock.instant())) {
                pending.complete(current.history());
                return current.history();
            }
            var loaded = fetch(ticker);
            cacheSuccess(ticker, loaded);
            pending.complete(loaded);
            return loaded;
        } catch (RuntimeException error) {
            pending.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(ticker, pending);
        }
    }

    private void refreshInBackground(String ticker) {
        var pending = new CompletableFuture<List<BottomPatternPoint>>();
        if (inFlight.putIfAbsent(ticker, pending) != null) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    var loaded = fetch(ticker);
                    cacheSuccess(ticker, loaded);
                    pending.complete(loaded);
                } catch (RuntimeException error) {
                    pending.completeExceptionally(error);
                    LOGGER.warn("Unable to refresh stale Yahoo price history for {}; retaining prior history", ticker, error);
                } finally {
                    inFlight.remove(ticker, pending);
                }
            });
        } catch (RuntimeException error) {
            inFlight.remove(ticker, pending);
            pending.completeExceptionally(error);
            LOGGER.warn("Unable to schedule Yahoo price-history refresh for {}", ticker, error);
        }
    }

    private List<BottomPatternPoint> fetch(String ticker) {
        var acquired = false;
        try {
            fetchPermits.acquire();
            acquired = true;
            return fetchWithFallback(ticker);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CompanyPriceHistoryUnavailableException(
                    "Interrupted while waiting to load Yahoo price history", error
            );
        } finally {
            if (acquired) fetchPermits.release();
        }
    }

    private List<BottomPatternPoint> fetchWithFallback(String ticker) {
        RuntimeException lastFailure = null;
        var symbol = SYMBOL_OVERRIDES.getOrDefault(ticker, ticker);
        var period2 = clock.instant().getEpochSecond();
        var period1 = period2 - lookbackDays * 86_400L;
        for (var baseUrl : baseUrls) {
            try {
                var history = restClient.get()
                        .uri(chartUri(baseUrl, symbol, period1, period2))
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange((request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                            try (var parser = objectMapper.createParser(response.getBody())) {
                                return YahooCompanyPriceHistoryMapper.map(parser, symbol);
                            }
                        });
                if (history == null || history.isEmpty()) {
                    throw new IllegalArgumentException("Yahoo price-history response was empty");
                }
                // Never poison the SWR cache with a temporary pre/post corporate-action
                // basis mix. The application boundary validates again before scoring,
                // but this adapter-side guard preserves the last known-good series and
                // makes the next provider correction immediately retryable instead of
                // serving an invalid cached payload for another cache TTL.
                var quality = historyQualityPolicy.evaluate(history);
                if (!quality.eligible()) {
                    throw new IllegalArgumentException(
                            "Yahoo price history failed basis validation: "
                                    + String.join("; ", quality.warnings()));
                }
                var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
                var latest = history.stream().map(BottomPatternPoint::date)
                        .max(LocalDate::compareTo).orElseThrow();
                if (latest.isAfter(today.plusDays(1)) || latest.isBefore(today.minusDays(7))) {
                    throw new IllegalArgumentException("Yahoo price history is outside the accepted freshness window");
                }
                return List.copyOf(history);
            } catch (RestClientException | JacksonException | IllegalArgumentException error) {
                lastFailure = error;
            }
        }
        throw new CompanyPriceHistoryUnavailableException("Unable to load Yahoo company price history", lastFailure);
    }

    private void cacheSuccess(String ticker, List<BottomPatternPoint> history) {
        cache.put(ticker, new CachedHistory(List.copyOf(history), clock.instant()));
        while (cache.size() > maxEntries) {
            var oldest = cache.entrySet().stream()
                    .min((left, right) -> left.getValue().loadedAt().compareTo(right.getValue().loadedAt()))
                    .orElse(null);
            if (oldest == null) return;
            cache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private boolean isFresh(CachedHistory value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(cacheTtl));
    }

    private boolean isUsableStale(CachedHistory value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(staleTtl));
    }

    private static URI chartUri(URI baseUrl, String symbol, long period1, long period2) {
        var base = baseUrl.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        var encodedSymbol = UriUtils.encodePathSegment(symbol, StandardCharsets.UTF_8);
        return URI.create(base + "/v8/finance/chart/" + encodedSymbol
                + "?period1=" + period1 + "&period2=" + period2
                + "&interval=1d&events=div,splits");
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static List<BottomPatternPoint> await(CompletableFuture<List<BottomPatternPoint>> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw error;
        }
    }

    private static Duration requireNonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    private record CachedHistory(List<BottomPatternPoint> history, Instant loadedAt) {
    }
}
