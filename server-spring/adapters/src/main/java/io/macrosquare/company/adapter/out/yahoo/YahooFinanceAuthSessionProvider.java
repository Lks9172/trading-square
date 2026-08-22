package io.macrosquare.company.adapter.out.yahoo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/** Acquires and caches Yahoo's cookie/crumb pair without ever logging either secret value. */
public final class YahooFinanceAuthSessionProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(YahooFinanceAuthSessionProvider.class);

    private final RestClient restClient;
    private final URI cookieUrl;
    private final URI crumbUrl;
    private final Clock clock;
    private final Duration cacheTtl;
    private final ReentrantLock sessionLock = new ReentrantLock(true);
    private volatile CachedSession cached;

    public YahooFinanceAuthSessionProvider(
            RestClient restClient,
            URI cookieUrl,
            URI crumbUrl,
            Clock clock,
            Duration cacheTtl
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.cookieUrl = requireAbsolute(cookieUrl, "cookieUrl");
        this.crumbUrl = requireAbsolute(crumbUrl, "crumbUrl");
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtl = Objects.requireNonNull(cacheTtl, "cacheTtl");
        if (cacheTtl.isNegative()) throw new IllegalArgumentException("cacheTtl must not be negative");
    }

    Optional<AuthSession> current() {
        var current = cached;
        if (isFresh(current, clock.instant())) return Optional.of(current.session());
        sessionLock.lock();
        try {
            current = cached;
            if (isFresh(current, clock.instant())) return Optional.of(current.session());
            var loaded = acquire();
            loaded.ifPresent(session -> cached = new CachedSession(session, clock.instant()));
            return loaded;
        } finally {
            sessionLock.unlock();
        }
    }

    void invalidate(AuthSession rejected) {
        if (rejected == null) return;
        sessionLock.lock();
        try {
            if (cached != null && cached.session().equals(rejected)) cached = null;
        } finally {
            sessionLock.unlock();
        }
    }

    private Optional<AuthSession> acquire() {
        try {
            var bootstrap = restClient.get()
                    .uri(cookieUrl)
                    .exchange((request, response) -> {
                        var values = response.getHeaders().get(HttpHeaders.SET_COOKIE);
                        return new CookieBootstrap(
                                response.getStatusCode().value(),
                                values == null ? List.<String>of() : List.copyOf(values)
                        );
                    });
            var cookie = bootstrap == null ? "" : bootstrap.setCookieHeaders().stream()
                    .map(YahooFinanceAuthSessionProvider::cookiePair)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
            if (cookie.isBlank()) {
                LOGGER.warn(
                        "Yahoo cookie bootstrap returned no usable Set-Cookie header (status={})",
                        bootstrap == null ? "unknown" : bootstrap.statusCode()
                );
                return Optional.empty();
            }

            var crumbResponse = restClient.get()
                    .uri(crumbUrl)
                    .header(HttpHeaders.COOKIE, cookie)
                    .accept(MediaType.TEXT_PLAIN)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return new CrumbBootstrap(response.getStatusCode().value(), "");
                        }
                        try {
                            return new CrumbBootstrap(
                                    response.getStatusCode().value(),
                                    new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8).trim()
                            );
                        } catch (IOException error) {
                            throw new UncheckedIOException(error);
                        }
                    });
            var crumb = crumbResponse == null ? "" : crumbResponse.crumb();
            if (crumb.isBlank()) {
                LOGGER.warn(
                        "Yahoo crumb bootstrap returned an empty value (status={})",
                        crumbResponse == null ? "unknown" : crumbResponse.statusCode()
                );
                return Optional.empty();
            }
            return Optional.of(new AuthSession(crumb, cookie));
        } catch (RuntimeException error) {
            LOGGER.warn("Unable to acquire Yahoo authentication session: {}", error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private boolean isFresh(CachedSession value, Instant now) {
        return value != null && now.isBefore(value.loadedAt().plus(cacheTtl));
    }

    private static String cookiePair(String header) {
        if (header == null) return "";
        var separator = header.indexOf(';');
        return (separator < 0 ? header : header.substring(0, separator)).trim();
    }

    private static URI requireAbsolute(URI value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.isAbsolute()) throw new IllegalArgumentException(field + " must be absolute");
        return value;
    }

    record AuthSession(String crumb, String cookie) {
        AuthSession {
            if (crumb == null || crumb.isBlank()) throw new IllegalArgumentException("crumb is required");
            if (cookie == null || cookie.isBlank()) throw new IllegalArgumentException("cookie is required");
        }

        @Override
        public String toString() {
            return "AuthSession[redacted]";
        }
    }

    private record CachedSession(AuthSession session, Instant loadedAt) {
    }

    private record CookieBootstrap(int statusCode, List<String> setCookieHeaders) {
    }

    private record CrumbBootstrap(int statusCode, String crumb) {
    }
}
