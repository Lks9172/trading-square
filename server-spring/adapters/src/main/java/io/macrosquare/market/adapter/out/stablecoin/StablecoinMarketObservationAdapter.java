package io.macrosquare.market.adapter.out.stablecoin;

import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/** DeFiLlama USD-pegged stablecoin market-cap collector. */
public final class StablecoinMarketObservationAdapter implements CollectMarketObservationsPort {

    private final RestClient restClient;
    private final URI url;
    private final Clock clock;

    public StablecoinMarketObservationAdapter(RestClient restClient, URI url, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient);
        if (url == null || !url.isAbsolute() || !"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("stablecoin URL must be an absolute HTTPS URI");
        }
        this.url = url;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public MarketDataSource source() {
        return MarketDataSource.STABLECOIN;
    }

    @Override
    public MarketCollectionBatch collect() {
        var startedAt = clock.instant();
        try {
            var body = restClient.get().uri(url).accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(JsonNode.class);
            var assets = body == null ? null : body.get("peggedAssets");
            if (assets == null || !assets.isArray() || assets.isEmpty()) {
                throw new IllegalArgumentException("peggedAssets is missing");
            }
            var total = BigDecimal.ZERO;
            for (var asset : assets) {
                var value = asset.at("/circulating/peggedUSD");
                if (value != null && value.isNumber()) {
                    var amount = value.decimalValue();
                    if (amount.signum() > 0) total = total.add(amount);
                }
            }
            if (total.signum() <= 0) throw new IllegalArgumentException("stablecoin total is invalid");
            var billions = total.divide(BigDecimal.valueOf(1_000_000_000L), 2, RoundingMode.HALF_UP).doubleValue();
            var observation = new MarketObservation(
                    "STABLECOIN_MCAP",
                    "DEFILLAMA:STABLECOINS",
                    billions,
                    LocalDate.now(clock.withZone(ZoneOffset.UTC)),
                    source()
            );
            return new MarketCollectionBatch(source(), startedAt, clock.instant(), List.of(observation), List.of());
        } catch (RuntimeException error) {
            return new MarketCollectionBatch(source(), startedAt, clock.instant(), List.of(), List.of(
                    new MarketCollectionBatch.Failure("STABLECOIN_MCAP", safeReason(error))
            ));
        }
    }

    private static String safeReason(RuntimeException error) {
        if (error instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        return error instanceof IllegalArgumentException ? "Malformed provider response" : error.getClass().getSimpleName();
    }
}
