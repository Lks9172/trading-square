package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.model.CompanyMarketQuote;
import io.macrosquare.company.application.port.out.CompanyMarketQuoteUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyMarketQuotePort;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Read-only Yahoo chart adapter matching the legacy query1/query2 company
 * quote contract. It has no startup, persistence, scheduler, or alert side
 * effects.
 */
public final class YahooCompanyMarketQuoteAdapter implements LoadCompanyMarketQuotePort {

    private static final Logger LOGGER = LoggerFactory.getLogger(YahooCompanyMarketQuoteAdapter.class);
    private static final int DEFAULT_MAX_ENTRIES = 128;
    private static final int DEFAULT_MAX_CONCURRENT_FETCHES = 8;
    private static final Map<String, String> SYMBOL_OVERRIDES = Map.of("MMC", "MRSH");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<URI> baseUrls;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration staleTtl;
    private final Executor refreshExecutor;
    private final int maxEntries;
    private final Semaphore fetchPermits;
    private final ConcurrentHashMap<String, CachedQuote> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<CompanyMarketQuote>> inFlight =
            new ConcurrentHashMap<>();

    public YahooCompanyMarketQuoteAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            List<URI> baseUrls,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor
    ) {
        this(
                restClient, objectMapper, baseUrls, clock, cacheTtl, staleTtl, refreshExecutor,
                DEFAULT_MAX_ENTRIES, DEFAULT_MAX_CONCURRENT_FETCHES
        );
    }

    public YahooCompanyMarketQuoteAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            List<URI> baseUrls,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor,
            int maxConcurrentFetches
    ) {
        this(
                restClient, objectMapper, baseUrls, clock, cacheTtl, staleTtl, refreshExecutor,
                DEFAULT_MAX_ENTRIES, maxConcurrentFetches
        );
    }

    YahooCompanyMarketQuoteAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            List<URI> baseUrls,
            Clock clock,
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
        this.cacheTtl = requireNonNegative(cacheTtl, "cacheTtl");
        this.staleTtl = Objects.requireNonNull(staleTtl);
        if (staleTtl.compareTo(cacheTtl) < 0) {
            throw new IllegalArgumentException("staleTtl must be greater than or equal to cacheTtl");
        }
        this.refreshExecutor = Objects.requireNonNull(refreshExecutor);
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        if (maxConcurrentFetches < 1) {
            throw new IllegalArgumentException("maxConcurrentFetches must be positive");
        }
        this.maxEntries = maxEntries;
        this.fetchPermits = new Semaphore(maxConcurrentFetches, true);
    }

    @Override
    public CompanyMarketQuote load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        var current = cache.get(ticker);
        var now = clock.instant();
        if (isFresh(current, now)) return current.quote();
        if (isUsableStale(current, now)) {
            refreshInBackground(ticker);
            return current.quote();
        }
        return loadSynchronously(ticker);
    }

    private CompanyMarketQuote loadSynchronously(String ticker) {
        var pending = new CompletableFuture<CompanyMarketQuote>();
        var existing = inFlight.putIfAbsent(ticker, pending);
        if (existing != null) return await(existing);
        try {
            var current = cache.get(ticker);
            if (isFresh(current, clock.instant())) {
                pending.complete(current.quote());
                return current.quote();
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
        var pending = new CompletableFuture<CompanyMarketQuote>();
        if (inFlight.putIfAbsent(ticker, pending) != null) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    var loaded = fetch(ticker);
                    cacheSuccess(ticker, loaded);
                    pending.complete(loaded);
                } catch (RuntimeException error) {
                    pending.completeExceptionally(error);
                    LOGGER.warn("Unable to refresh stale Yahoo quote for {}; retaining prior quote", ticker, error);
                } finally {
                    inFlight.remove(ticker, pending);
                }
            });
        } catch (RuntimeException error) {
            inFlight.remove(ticker, pending);
            pending.completeExceptionally(error);
            LOGGER.warn("Unable to schedule Yahoo quote refresh for {}", ticker, error);
        }
    }

    private CompanyMarketQuote fetch(String ticker) {
        var acquired = false;
        try {
            fetchPermits.acquire();
            acquired = true;
            return fetchWithFallback(ticker);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CompanyMarketQuoteUnavailableException("Interrupted while waiting to load Yahoo quote", error);
        } finally {
            if (acquired) fetchPermits.release();
        }
    }

    private CompanyMarketQuote fetchWithFallback(String ticker) {
        RuntimeException lastFailure = null;
        var symbol = SYMBOL_OVERRIDES.getOrDefault(ticker, ticker);
        for (var baseUrl : baseUrls) {
            try {
                var quote = restClient.get()
                        .uri(chartUri(baseUrl, symbol))
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange((request, response) -> {
                            if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                            try (var parser = objectMapper.createParser(response.getBody())) {
                                return YahooCompanyMarketQuoteMapper.map(parser, symbol);
                            }
                        });
                if (quote == null || !quote.available()) {
                    throw new IllegalArgumentException("Yahoo quote response was empty");
                }
                return quote;
            } catch (RestClientException | JacksonException | IllegalArgumentException error) {
                lastFailure = error;
            }
        }
        throw new CompanyMarketQuoteUnavailableException("Unable to load Yahoo company quote", lastFailure);
    }

    private void cacheSuccess(String ticker, CompanyMarketQuote quote) {
        cache.put(ticker, new CachedQuote(quote, clock.instant()));
        while (cache.size() > maxEntries) {
            var oldest = cache.entrySet().stream()
                    .min((left, right) -> left.getValue().loadedAt().compareTo(right.getValue().loadedAt()))
                    .orElse(null);
            if (oldest == null) return;
            cache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private boolean isFresh(CachedQuote value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(cacheTtl));
    }

    private boolean isUsableStale(CachedQuote value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(staleTtl));
    }

    private static URI chartUri(URI baseUrl, String symbol) {
        var base = baseUrl.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        var encodedSymbol = UriUtils.encodePathSegment(symbol, StandardCharsets.UTF_8);
        return URI.create(base + "/v8/finance/chart/" + encodedSymbol + "?range=5d&interval=1d");
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static CompanyMarketQuote await(CompletableFuture<CompanyMarketQuote> future) {
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

    private record CachedQuote(CompanyMarketQuote quote, Instant loadedAt) {
    }
}
