package io.macrosquare.research.adapter.out.sec;

import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.application.model.PeerTaxonomyUnavailableException;
import io.macrosquare.research.application.port.out.CollectPeerTaxonomyPort;
import io.macrosquare.research.domain.peer.PeerTaxonomy;
import io.macrosquare.research.domain.peer.SicSectorPolicy;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Reads SIC metadata from official SEC submissions without leaking JSON into the domain. */
public final class SecPeerTaxonomyAdapter implements CollectPeerTaxonomyPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final SicSectorPolicy sectorPolicy;
    private final ObjectStorage objectStorage;
    private final Clock clock;
    private final long maximumBytes;
    private final long delayNanos;
    private final AtomicLong nextRequestNanos = new AtomicLong();

    public SecPeerTaxonomyAdapter(
            RestClient restClient,
            ObjectMapper objectMapper,
            SicSectorPolicy sectorPolicy,
            ObjectStorage objectStorage,
            Clock clock,
            Duration interRequestDelay,
            long maximumBytes
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.sectorPolicy = Objects.requireNonNull(sectorPolicy);
        this.objectStorage = objectStorage;
        this.clock = Objects.requireNonNull(clock);
        this.delayNanos = Objects.requireNonNull(interRequestDelay).toNanos();
        if (delayNanos < 0 || maximumBytes <= 0 || maximumBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("SEC taxonomy boundaries are invalid");
        }
        this.maximumBytes = maximumBytes;
    }

    @Override
    public PeerTaxonomy collect(PeerUniverseCompany company, LocalDate observedOn) {
        pace();
        var bytes = restClient.get().uri("/submissions/CIK" + company.cik() + ".json")
                .accept(MediaType.APPLICATION_JSON).exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) throw response.createException();
                    try {
                        return bounded(response.getBody());
                    } catch (IOException error) {
                        throw new IllegalArgumentException("SEC submissions response exceeded boundary", error);
                    }
                });
        archive(company, observedOn, bytes);
        try (var parser = objectMapper.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IllegalArgumentException("SEC submissions is invalid");
            Integer sic = null;
            String description = null;
            String name = company.companyName();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                var field = parser.currentName();
                var token = parser.nextToken();
                switch (field) {
                    case "sic" -> {
                        var raw = token.isNumeric() ? parser.getNumberValue().toString() : parser.getString();
                        if (raw != null && !raw.isBlank()) sic = Integer.parseInt(raw);
                    }
                    case "sicDescription" -> description = parser.getString();
                    case "name" -> name = parser.getString();
                    default -> parser.skipChildren();
                }
            }
            if (sic == null || description == null || description.isBlank()) {
                throw new PeerTaxonomyUnavailableException("SEC submissions omitted SIC taxonomy");
            }
            return new PeerTaxonomy(
                    company.ticker(), company.cik(), name, sic, description,
                    sectorPolicy.classify(sic), observedOn, null);
        } catch (tools.jackson.core.JacksonException error) {
            throw new IllegalArgumentException("Unable to parse SEC SIC taxonomy", error);
        }
    }

    private void archive(PeerUniverseCompany company, LocalDate observedOn, byte[] bytes) {
        if (objectStorage == null) return;
        objectStorage.put(
                "source-documents/sec-taxonomy/" + company.cik() + "/" + observedOn + ".json",
                bytes, MediaType.APPLICATION_JSON_VALUE,
                Map.of("ticker", company.ticker(), "cik", company.cik(),
                        "source-url", "https://data.sec.gov/submissions/CIK" + company.cik() + ".json",
                        "collected-at", clock.instant().toString()));
    }

    private byte[] bounded(InputStream input) throws IOException {
        if (input == null) throw new IOException("empty SEC submissions response");
        var bytes = input.readNBytes((int) maximumBytes + 1);
        if (bytes.length > maximumBytes) throw new IOException("SEC submissions response is too large");
        return bytes;
    }

    private void pace() {
        if (delayNanos <= 0) return;
        while (true) {
            var observed = nextRequestNanos.get();
            var now = System.nanoTime();
            var reserved = Math.max(observed, now);
            if (!nextRequestNanos.compareAndSet(observed, reserved + delayNanos)) continue;
            var wait = reserved - now;
            if (wait <= 0) return;
            try {
                Thread.sleep(Duration.ofNanos(wait));
                return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while pacing SEC taxonomy requests", error);
            }
        }
    }
}
