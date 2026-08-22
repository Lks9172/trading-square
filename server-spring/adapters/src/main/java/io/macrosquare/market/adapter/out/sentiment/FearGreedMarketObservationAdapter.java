package io.macrosquare.market.adapter.out.sentiment;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** CNN Fear & Greed collector with alternative.me as an explicit live fallback. */
public final class FearGreedMarketObservationAdapter implements CollectMarketObservationsPort {

    private final RestClient restClient;
    private final URI cnnUrl;
    private final URI alternativeUrl;
    private final Clock clock;

    public FearGreedMarketObservationAdapter(
            RestClient restClient,
            URI cnnUrl,
            URI alternativeUrl,
            Clock clock
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.cnnUrl = absolute(cnnUrl, "cnnUrl");
        this.alternativeUrl = absolute(alternativeUrl, "alternativeUrl");
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public MarketDataSource source() {
        return MarketDataSource.FEAR_GREED;
    }

    @Override
    public MarketCollectionBatch collect() {
        var startedAt = clock.instant();
        var failures = new ArrayList<MarketCollectionBatch.Failure>();
        try {
            var observation = fetchCnn();
            return batch(startedAt, List.of(observation), failures);
        } catch (RuntimeException error) {
            failures.add(new MarketCollectionBatch.Failure("CNN", safeReason(error)));
        }
        try {
            var observation = fetchAlternative();
            return batch(startedAt, List.of(observation), failures);
        } catch (RuntimeException error) {
            failures.add(new MarketCollectionBatch.Failure("ALTERNATIVE_ME", safeReason(error)));
            return batch(startedAt, List.of(), failures);
        }
    }

    private MarketObservation fetchCnn() {
        var root = get(cnnUrl);
        var score = score(requiredNumber(root.at("/fear_and_greed/score"), "CNN score"));
        var timestamp = requiredText(root.at("/fear_and_greed/timestamp"), "CNN timestamp");
        var date = currentDate(parseDate(timestamp), "CNN timestamp");
        return new MarketObservation(
                "FEAR_GREED",
                "CNN:FEAR_GREED",
                Math.round(score),
                date,
                source()
        );
    }

    private MarketObservation fetchAlternative() {
        var root = get(alternativeUrl);
        var item = root.at("/data/0");
        if (!item.isObject()) throw new IllegalArgumentException("alternative.me item is missing");
        var score = score(Double.parseDouble(requiredText(item.get("value"), "alternative.me value")));
        var epoch = Long.parseLong(requiredText(item.get("timestamp"), "alternative.me timestamp"));
        var date = currentDate(
                Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate(),
                "alternative.me timestamp"
        );
        return new MarketObservation(
                "CRYPTO_FEAR_GREED",
                "ALTERNATIVE_ME:CRYPTO_FEAR_GREED",
                Math.round(score),
                date,
                source()
        );
    }

    private LocalDate currentDate(LocalDate date, String field) {
        var today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (date.isAfter(today) || date.isBefore(today.minusDays(7))) {
            throw new IllegalArgumentException(field + " is outside the accepted window");
        }
        return date;
    }

    private static double score(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException("fear-and-greed score must be between 0 and 100");
        }
        return value;
    }

    private JsonNode get(URI uri) {
        var body = restClient.get().uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve().body(JsonNode.class);
        if (body == null || !body.isObject()) throw new IllegalArgumentException("provider response is empty");
        return body;
    }

    private MarketCollectionBatch batch(
            Instant startedAt,
            List<MarketObservation> observations,
            List<MarketCollectionBatch.Failure> failures
    ) {
        return new MarketCollectionBatch(source(), startedAt, clock.instant(), observations, failures);
    }

    private LocalDate parseDate(String value) {
        try {
            return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value.substring(0, Math.min(10, value.length())));
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("CNN timestamp is malformed", error);
            }
        }
    }

    private static double requiredNumber(JsonNode node, String field) {
        if (node == null || !node.isNumber()) throw new IllegalArgumentException(field + " is missing");
        var value = node.asDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw new IllegalArgumentException(field + " is missing");
        }
        return node.stringValue();
    }

    private static URI absolute(URI uri, String field) {
        if (uri == null || !uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(field + " must be an absolute HTTPS URI");
        }
        return uri;
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return error instanceof IllegalArgumentException ? "Malformed provider response" : error.getClass().getSimpleName();
    }
}
