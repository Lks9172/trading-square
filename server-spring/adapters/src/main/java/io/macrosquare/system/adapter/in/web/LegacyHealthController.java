package io.macrosquare.system.adapter.in.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

@RestController
@RequestMapping("/api")
public final class LegacyHealthController {

    private final Clock clock;
    private final long cacheTtlMs;

    public LegacyHealthController(
            Clock clock,
            @Value("${macrosquare.snapshot.cache-ttl-ms:300000}") long cacheTtlMs
    ) {
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtlMs = cacheTtlMs;
    }

    @GetMapping("/health")
    public LegacyHealthResponse health() {
        return new LegacyHealthResponse(
                "ok",
                Instant.now(clock).toString(),
                cacheTtlMs,
                null
        );
    }

    public record LegacyHealthResponse(
            String status,
            String timestamp,
            long cacheTtlMs,
            String lastRefreshAt
    ) {
    }
}
