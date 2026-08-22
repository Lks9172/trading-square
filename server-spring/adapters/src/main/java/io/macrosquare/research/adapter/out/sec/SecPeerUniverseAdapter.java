package io.macrosquare.research.adapter.out.sec;

import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.application.port.out.LoadPeerUniversePort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Cached official SEC exchange-ticker universe. */
public final class SecPeerUniverseAdapter implements LoadPeerUniversePort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration cacheTtl;
    private volatile CachedUniverse cache;

    public SecPeerUniverseAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            Duration cacheTtl
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtl = Objects.requireNonNull(cacheTtl);
        if (cacheTtl.isNegative() || cacheTtl.isZero()) throw new IllegalArgumentException("cacheTtl must be positive");
    }

    @Override
    public List<PeerUniverseCompany> load() {
        var current = cache;
        var now = clock.instant();
        if (current != null && now.isBefore(current.loadedAt().plus(cacheTtl))) return current.values();
        var loaded = fetch();
        cache = new CachedUniverse(loaded, now);
        return loaded;
    }

    private List<PeerUniverseCompany> fetch() {
        return restClient.get().uri("/files/company_tickers.json").accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                    try (var parser = objectMapper.createParser(response.getBody())) {
                        if (parser.nextToken() != JsonToken.START_OBJECT) {
                            throw new IllegalArgumentException("SEC ticker directory must be an object");
                        }
                        var result = new ArrayList<PeerUniverseCompany>();
                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                            if (parser.currentToken() != JsonToken.PROPERTY_NAME
                                    || parser.nextToken() != JsonToken.START_OBJECT) {
                                throw new IllegalArgumentException("SEC ticker directory entry is invalid");
                            }
                            String ticker = null;
                            String cik = null;
                            String title = null;
                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                var field = parser.currentName();
                                var token = parser.nextToken();
                                switch (field) {
                                    case "ticker" -> ticker = parser.getString();
                                    case "title" -> title = parser.getString();
                                    case "cik_str" -> {
                                        var raw = token.isNumeric() ? parser.getNumberValue().toString() : parser.getString();
                                        cik = "0".repeat(Math.max(0, 10 - raw.length())) + raw;
                                    }
                                    default -> parser.skipChildren();
                                }
                            }
                            if (ticker != null && cik != null && title != null) {
                                result.add(new PeerUniverseCompany(
                                        ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-'), cik, title.trim()));
                            }
                        }
                        return List.copyOf(result);
                    }
                });
    }

    private record CachedUniverse(List<PeerUniverseCompany> values, Instant loadedAt) {
    }
}
