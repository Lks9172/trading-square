package io.macrosquare.company.adapter.out.sec;

import io.macrosquare.company.application.model.CompanyIdentity;
import io.macrosquare.company.application.port.in.CompanyTickerNotFoundException;
import io.macrosquare.company.application.port.out.CompanyIdentityUnavailableException;
import io.macrosquare.company.application.port.out.ResolveCompanyIdentityPort;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-only SEC ticker directory adapter with one success-only, stale-while-
 * revalidate snapshot. It performs no startup fetch and no persistence write.
 */
public final class SecCompanyIdentityAdapter implements ResolveCompanyIdentityPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecCompanyIdentityAdapter.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration staleTtl;
    private final Executor refreshExecutor;
    private final AtomicReference<CompletableFuture<Map<String, CompanyIdentity>>> inFlight =
            new AtomicReference<>();
    private volatile CachedDirectory cache;

    public SecCompanyIdentityAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration cacheTtl,
            Duration staleTtl,
            Executor refreshExecutor
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
    }

    @Override
    public CompanyIdentity resolve(String normalizedTicker) {
        var ticker = SecCompanyIdentityMapper.normalizeTicker(normalizedTicker);
        var current = cache;
        var now = clock.instant();
        if (isFresh(current, now)) return find(current.identities(), ticker);
        if (isUsableStale(current, now)) {
            refreshInBackground();
            return find(current.identities(), ticker);
        }
        return find(loadSynchronously(), ticker);
    }

    private Map<String, CompanyIdentity> loadSynchronously() {
        while (true) {
            var existing = inFlight.get();
            if (existing != null) return await(existing);

            var pending = new CompletableFuture<Map<String, CompanyIdentity>>();
            if (!inFlight.compareAndSet(null, pending)) continue;
            try {
                var current = cache;
                if (isFresh(current, clock.instant())) {
                    pending.complete(current.identities());
                    return current.identities();
                }
                var loaded = fetch();
                cache = new CachedDirectory(loaded, clock.instant());
                pending.complete(loaded);
                return loaded;
            } catch (RuntimeException error) {
                pending.completeExceptionally(error);
                throw error;
            } finally {
                inFlight.compareAndSet(pending, null);
            }
        }
    }

    private void refreshInBackground() {
        var pending = new CompletableFuture<Map<String, CompanyIdentity>>();
        if (!inFlight.compareAndSet(null, pending)) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    var loaded = fetch();
                    cache = new CachedDirectory(loaded, clock.instant());
                    pending.complete(loaded);
                } catch (RuntimeException error) {
                    pending.completeExceptionally(error);
                    LOGGER.warn("Unable to refresh stale SEC ticker directory; retaining prior directory", error);
                } finally {
                    inFlight.compareAndSet(pending, null);
                }
            });
        } catch (RuntimeException error) {
            inFlight.compareAndSet(pending, null);
            pending.completeExceptionally(error);
            LOGGER.warn("Unable to schedule SEC ticker directory refresh", error);
        }
    }

    private Map<String, CompanyIdentity> fetch() {
        try {
            var result = restClient.get()
                    .uri("/files/company_tickers.json")
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                        try (var parser = objectMapper.createParser(response.getBody())) {
                            return SecCompanyIdentityMapper.map(parser);
                        }
                    });
            if (result == null) {
                throw new IllegalArgumentException("SEC ticker directory response was empty");
            }
            return result;
        } catch (RestClientException | JacksonException | IllegalArgumentException error) {
            throw new CompanyIdentityUnavailableException("Unable to load SEC ticker directory", error);
        }
    }

    private static CompanyIdentity find(Map<String, CompanyIdentity> identities, String ticker) {
        var identity = identities.get(ticker);
        if (identity == null) throw new CompanyTickerNotFoundException(ticker);
        return identity;
    }

    private boolean isFresh(CachedDirectory value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(cacheTtl));
    }

    private boolean isUsableStale(CachedDirectory value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(staleTtl));
    }

    private static Map<String, CompanyIdentity> await(
            CompletableFuture<Map<String, CompanyIdentity>> future
    ) {
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

    private record CachedDirectory(Map<String, CompanyIdentity> identities, Instant loadedAt) {
    }
}
