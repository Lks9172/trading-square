package io.macrosquare.market.adapter.in.web;

import io.macrosquare.market.application.model.MarketCollectionReport;
import io.macrosquare.market.application.port.in.InspectMarketObservationsUseCase;
import io.macrosquare.market.application.port.in.RefreshMarketObservationsUseCase;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/internal/v1/migration/market-observations")
public final class MarketObservationMigrationController {

    private final InspectMarketObservationsUseCase inspect;
    private final RefreshMarketObservationsUseCase refresh;

    public MarketObservationMigrationController(
            InspectMarketObservationsUseCase inspect,
            RefreshMarketObservationsUseCase refresh
    ) {
        this.inspect = Objects.requireNonNull(inspect);
        this.refresh = Objects.requireNonNull(refresh);
    }

    @GetMapping
    public Map<String, SourceStatus> status() {
        var result = new java.util.LinkedHashMap<String, SourceStatus>();
        inspect.latest().forEach((source, observations) -> result.put(source.name(), SourceStatus.from(observations)));
        return result;
    }

    @PostMapping("/{source}/refresh")
    public MarketCollectionReport refresh(@PathVariable String source) {
        return refresh.refresh(MarketDataSource.valueOf(source.trim().toUpperCase(Locale.ROOT)));
    }

    public record SourceStatus(int count, String newestDate, List<String> keys) {
        static SourceStatus from(List<MarketObservation> observations) {
            var newest = observations.stream().map(MarketObservation::observationDate).max(Comparator.naturalOrder());
            return new SourceStatus(
                    observations.size(),
                    newest.map(Object::toString).orElse(null),
                    observations.stream().map(MarketObservation::key).sorted().toList()
            );
        }
    }
}
