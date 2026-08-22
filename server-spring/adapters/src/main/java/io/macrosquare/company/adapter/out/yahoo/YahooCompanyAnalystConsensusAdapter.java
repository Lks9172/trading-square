package io.macrosquare.company.adapter.out.yahoo;

import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Direct Yahoo recommendation and EPS-estimate collector. The seven core
 * tickers retain the coalesced batch contract while every other supported
 * company is fetched and cached on demand, so the expanded research universe
 * is not silently assigned missing analyst evidence.
 */
public final class YahooCompanyAnalystConsensusAdapter implements LoadCompanyAnalystConsensusPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(YahooCompanyAnalystConsensusAdapter.class);
    private static final List<String> MEGACAP = List.of(
            "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA"
    );
    private static final Set<String> MEGACAP_SET = Set.copyOf(MEGACAP);
    private static final Map<String, String> SYMBOL_OVERRIDES = Map.of("MMC", "MRSH");
    // Funds do not expose company EPS/recommendation modules, while CTRA is a
    // retired security. Treat them as structurally unavailable instead of
    // reporting the same expected Yahoo failure on every hourly collection.
    private static final Set<String> NON_ANALYST_SECURITIES = Set.of("CTRA", "GLD", "IBIT");
    private static final CompanyAnalystConsensus UNAVAILABLE = new CompanyAnalystConsensus(null, null);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final YahooFinanceAuthSessionProvider authSessionProvider;
    private final LoadCompanyAnalystConsensusPort persistedFallback;
    private final URI quoteSummaryBaseUrl;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration staleTtl;
    private final Duration interTickerDelay;
    private final int minimumSuccessfulTickers;
    private final AtomicReference<CompletableFuture<ConsensusSnapshot>> inFlight = new AtomicReference<>();
    private final ConcurrentMap<String, CachedTicker> tickerCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<CompanyAnalystConsensus>> tickerInFlight =
            new ConcurrentHashMap<>();
    private volatile CachedSnapshot cached;

    public YahooCompanyAnalystConsensusAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            YahooFinanceAuthSessionProvider authSessionProvider,
            LoadCompanyAnalystConsensusPort persistedFallback,
            URI quoteSummaryBaseUrl,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Duration interTickerDelay,
            int minimumSuccessfulTickers
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.authSessionProvider = Objects.requireNonNull(authSessionProvider);
        this.persistedFallback = Objects.requireNonNull(persistedFallback);
        this.quoteSummaryBaseUrl = Objects.requireNonNull(quoteSummaryBaseUrl, "quoteSummaryBaseUrl");
        if (!quoteSummaryBaseUrl.isAbsolute()) {
            throw new IllegalArgumentException("quoteSummaryBaseUrl must be absolute");
        }
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtl = requireNonNegative(cacheTtl, "cacheTtl");
        this.staleTtl = Objects.requireNonNull(staleTtl, "staleTtl");
        if (staleTtl.compareTo(cacheTtl) < 0) {
            throw new IllegalArgumentException("staleTtl must be greater than or equal to cacheTtl");
        }
        this.interTickerDelay = requireNonNegative(interTickerDelay, "interTickerDelay");
        if (minimumSuccessfulTickers < 1 || minimumSuccessfulTickers > MEGACAP.size()) {
            throw new IllegalArgumentException("minimumSuccessfulTickers is outside the batch size");
        }
        this.minimumSuccessfulTickers = minimumSuccessfulTickers;
    }

    @Override
    public CompanyAnalystConsensus load(String normalizedTicker) {
        var ticker = normalizeTicker(normalizedTicker);
        if (NON_ANALYST_SECURITIES.contains(ticker)) return UNAVAILABLE;
        if (!MEGACAP_SET.contains(ticker)) return loadTicker(ticker);
        var snapshot = loadSnapshot();
        if (snapshot != null) return snapshot.perTicker().getOrDefault(ticker, UNAVAILABLE);
        return loadPersistedFallback(ticker);
    }

    private CompanyAnalystConsensus loadTicker(String ticker) {
        var current = tickerCache.get(ticker);
        var now = clock.instant();
        if (isFresh(current, now)) return current.consensus();

        var pending = new CompletableFuture<CompanyAnalystConsensus>();
        var existing = tickerInFlight.putIfAbsent(ticker, pending);
        if (existing != null) return awaitTicker(existing);
        try {
            current = tickerCache.get(ticker);
            now = clock.instant();
            if (isFresh(current, now)) {
                pending.complete(current.consensus());
                return current.consensus();
            }

            var loaded = fetchOne(ticker).orElse(null);
            if (loaded != null) {
                tickerCache.put(ticker, new CachedTicker(loaded, clock.instant()));
                pending.complete(loaded);
                return loaded;
            }
            if (isUsableStale(current, clock.instant())) {
                pending.complete(current.consensus());
                return current.consensus();
            }
            var fallback = loadPersistedFallback(ticker);
            pending.complete(fallback);
            return fallback;
        } catch (RuntimeException error) {
            pending.completeExceptionally(error);
            throw error;
        } finally {
            tickerInFlight.remove(ticker, pending);
        }
    }

    private ConsensusSnapshot loadSnapshot() {
        var current = cached;
        var now = clock.instant();
        if (isFresh(current, now)) return current.snapshot();

        var pending = new CompletableFuture<ConsensusSnapshot>();
        while (!inFlight.compareAndSet(null, pending)) {
            var existing = inFlight.get();
            if (existing != null) return await(existing);
        }
        try {
            current = cached;
            now = clock.instant();
            if (isFresh(current, now)) {
                pending.complete(current.snapshot());
                return current.snapshot();
            }

            ConsensusSnapshot loaded = null;
            try {
                loaded = fetchLiveBatch();
            } catch (RuntimeException error) {
                LOGGER.warn("Unable to collect the Yahoo analyst batch: {}", error.getClass().getSimpleName());
            }
            if (loaded != null) {
                cached = new CachedSnapshot(loaded, clock.instant());
                pending.complete(loaded);
                return loaded;
            }
            if (isUsableStale(current, clock.instant())) {
                pending.complete(current.snapshot());
                return current.snapshot();
            }
            pending.complete(null);
            return null;
        } catch (RuntimeException error) {
            pending.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.compareAndSet(pending, null);
        }
    }

    private ConsensusSnapshot fetchLiveBatch() {
        var perTicker = new LinkedHashMap<String, CompanyAnalystConsensus>();
        for (var ticker : MEGACAP) {
            fetchOne(ticker).ifPresent(value -> perTicker.put(ticker, value));
            pauseBetweenTickers();
        }
        if (perTicker.size() < minimumSuccessfulTickers) return null;
        return new ConsensusSnapshot(Map.copyOf(perTicker));
    }

    private Optional<CompanyAnalystConsensus> fetchOne(String ticker) {
        var auth = authSessionProvider.current();
        if (auth.isEmpty()) return Optional.empty();
        var providerTicker = providerTicker(ticker);
        try {
            var consensus = restClient.get()
                    .uri(quoteSummaryUri(providerTicker, auth.get().crumb()))
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.COOKIE, auth.get().cookie())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {
                            authSessionProvider.invalidate(auth.get());
                            throw new YahooUnauthorizedException();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                        try (var parser = objectMapper.createParser(response.getBody())) {
                            return YahooCompanyAnalystConsensusMapper.map(
                                    objectMapper.readTree(parser), providerTicker);
                        }
                    });
            return Optional.ofNullable(consensus);
        } catch (RuntimeException error) {
            LOGGER.warn("Yahoo analyst fetch failed for {}: {}", ticker, error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private CompanyAnalystConsensus loadPersistedFallback(String ticker) {
        try {
            return persistedFallback.load(ticker);
        } catch (RuntimeException error) {
            LOGGER.warn("Persisted analyst fallback is unavailable for {}: {}", ticker, error.getClass().getSimpleName());
            return UNAVAILABLE;
        }
    }

    private void pauseBetweenTickers() {
        if (interTickerDelay.isZero()) return;
        try {
            Thread.sleep(interTickerDelay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Yahoo analyst pacing", error);
        }
    }

    private URI quoteSummaryUri(String providerTicker, String crumb) {
        var base = quoteSummaryBaseUrl.toString();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base
                + "/v10/finance/quoteSummary/"
                + UriUtils.encodePathSegment(providerTicker, StandardCharsets.UTF_8)
                + "?modules=price,recommendationTrend,financialData,earningsTrend&crumb="
                + UriUtils.encodeQueryParam(crumb, StandardCharsets.UTF_8));
    }

    private static String providerTicker(String ticker) {
        return SYMBOL_OVERRIDES.getOrDefault(ticker, ticker);
    }

    private boolean isFresh(CachedSnapshot value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(cacheTtl));
    }

    private boolean isUsableStale(CachedSnapshot value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(staleTtl));
    }

    private boolean isFresh(CachedTicker value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(cacheTtl));
    }

    private boolean isUsableStale(CachedTicker value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(staleTtl));
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static ConsensusSnapshot await(CompletableFuture<ConsensusSnapshot> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw error;
        }
    }

    private static CompanyAnalystConsensus awaitTicker(CompletableFuture<CompanyAnalystConsensus> future) {
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

    private record ConsensusSnapshot(Map<String, CompanyAnalystConsensus> perTicker) {
        private ConsensusSnapshot {
            perTicker = Map.copyOf(perTicker);
        }
    }

    private record CachedSnapshot(ConsensusSnapshot snapshot, Instant loadedAt) {
    }

    private record CachedTicker(CompanyAnalystConsensus consensus, Instant loadedAt) {
    }

    private static final class YahooUnauthorizedException extends RuntimeException {
    }
}
