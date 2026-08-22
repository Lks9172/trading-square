package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.application.port.out.CompanyFundamentalsUnavailableException;
import io.macrosquare.company.application.port.out.LoadCompanyFundamentalsEvidencePort;
import io.macrosquare.company.domain.model.CompanyFundamentalsEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/**
 * Read-only SEC Company Facts adapter for company fundamentals enrichment.
 */
public final class SecCompanyFactsAdapter implements LoadCompanyFundamentalsEvidencePort {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecCompanyFactsAdapter.class);
    // The production research universe currently contains 276 active names.
    // A 128-entry cache thrashed on every full refresh and re-downloaded the
    // same multi-megabyte SEC documents before their four-hour TTL expired.
    private static final int DEFAULT_MAX_ENTRIES = 512;
    private static final int DEFAULT_MAX_CONCURRENT_FETCHES = 2;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration staleTtl;
    private final Executor refreshExecutor;
    private final int maxEntries;
    private final Semaphore fetchPermits;
    private final ConcurrentHashMap<String, CachedValue> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<CompanyFundamentalsEvidence>> inFlight =
            new ConcurrentHashMap<>();

    public SecCompanyFactsAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor
    ) {
        this(
                restClient,
                objectMapper,
                clock,
                cacheTtl,
                staleTtl,
                refreshExecutor,
                DEFAULT_MAX_ENTRIES,
                DEFAULT_MAX_CONCURRENT_FETCHES
        );
    }

    public SecCompanyFactsAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor,
            int maxConcurrentFetches
    ) {
        this(
                restClient,
                objectMapper,
                clock,
                cacheTtl,
                staleTtl,
                refreshExecutor,
                DEFAULT_MAX_ENTRIES,
                maxConcurrentFetches
        );
    }

    SecCompanyFactsAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor,
            int maxEntries,
            int maxConcurrentFetches
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
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
    public CompanyFundamentalsEvidence load(String cik) {
        var normalizedCik = normalizeCik(cik);
        var current = cache.get(normalizedCik);
        var now = clock.instant();
        if (isFresh(current, now)) return current.value();
        if (isUsableStale(current, now)) {
            refreshInBackground(normalizedCik);
            return current.value();
        }
        return loadSynchronously(normalizedCik);
    }

    private CompanyFundamentalsEvidence loadSynchronously(String cik) {
        var pending = new CompletableFuture<CompanyFundamentalsEvidence>();
        var existing = inFlight.putIfAbsent(cik, pending);
        if (existing != null) return await(existing);
        try {
            var current = cache.get(cik);
            if (isFresh(current, clock.instant())) {
                pending.complete(current.value());
                return current.value();
            }
            var loaded = fetch(cik);
            cacheSuccess(cik, loaded);
            pending.complete(loaded);
            return loaded;
        } catch (RuntimeException error) {
            pending.completeExceptionally(error);
            throw error;
        } finally {
            inFlight.remove(cik, pending);
        }
    }

    private void refreshInBackground(String cik) {
        var pending = new CompletableFuture<CompanyFundamentalsEvidence>();
        if (inFlight.putIfAbsent(cik, pending) != null) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    var loaded = fetch(cik);
                    cacheSuccess(cik, loaded);
                    pending.complete(loaded);
                } catch (RuntimeException error) {
                    pending.completeExceptionally(error);
                    LOGGER.warn("Unable to refresh stale SEC company facts for CIK {}; retaining prior facts", cik, error);
                } finally {
                    inFlight.remove(cik, pending);
                }
            });
        } catch (RuntimeException error) {
            inFlight.remove(cik, pending);
            pending.completeExceptionally(error);
            LOGGER.warn("Unable to schedule SEC company facts refresh for CIK {}", cik, error);
        }
    }

    private CompanyFundamentalsEvidence fetch(String cik) {
        var acquired = false;
        try {
            fetchPermits.acquire();
            acquired = true;
            var root = restClient.get()
                    .uri("/api/xbrl/companyfacts/CIK{cik}.json", cik)
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                        try (var parser = objectMapper.createParser(response.getBody())) {
                            return SecCompanyFactsMapper.map(parser, cik);
                        }
                    });
            if (root == null) {
                throw new CompanyFundamentalsUnavailableException("SEC company facts response was empty");
            }
            return root;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CompanyFundamentalsUnavailableException("Interrupted while waiting to load SEC company facts", error);
        } catch (CompanyFundamentalsUnavailableException error) {
            throw error;
        } catch (RestClientException | JacksonException | IllegalArgumentException error) {
            throw new CompanyFundamentalsUnavailableException("Unable to load or normalize SEC company facts", error);
        } finally {
            if (acquired) fetchPermits.release();
        }
    }

    private void cacheSuccess(String cik, CompanyFundamentalsEvidence value) {
        cache.put(cik, new CachedValue(value, clock.instant()));
        while (cache.size() > maxEntries) {
            var oldest = cache.entrySet().stream()
                    .min((left, right) -> left.getValue().loadedAt().compareTo(right.getValue().loadedAt()))
                    .orElse(null);
            if (oldest == null) return;
            cache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private boolean isFresh(CachedValue value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(cacheTtl));
    }

    private boolean isUsableStale(CachedValue value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(staleTtl));
    }

    private static CompanyFundamentalsEvidence await(CompletableFuture<CompanyFundamentalsEvidence> future) {
        try {
            return future.join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw error;
        }
    }

    private static String normalizeCik(String cik) {
        if (cik == null) throw new IllegalArgumentException("cik is required");
        var digits = cik.replaceAll("\\D+", "");
        if (digits.isEmpty() || digits.length() > 10) throw new IllegalArgumentException("invalid CIK");
        return "0".repeat(10 - digits.length()) + digits;
    }

    private static Duration requireNonNegative(Duration value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isNegative()) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    private record CachedValue(CompanyFundamentalsEvidence value, Instant loadedAt) {
    }
}
