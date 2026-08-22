package io.macrosquare.research.adapter.out.company;

import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.research.application.model.CurrentCompanyMetric;
import io.macrosquare.research.application.port.out.LoadCurrentCompanyMetricsPort;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Anti-corruption adapter from company read models to research catalog metrics. */
public final class CompanyResearchSummaryMetricsAdapter implements LoadCurrentCompanyMetricsPort {

    private static final Duration MAXIMUM_AGE = Duration.ofHours(2);
    private final CompanyResearchSummaryRepository repository;
    private final Clock clock;

    public CompanyResearchSummaryMetricsAdapter(CompanyResearchSummaryRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public CompanyResearchSummaryMetricsAdapter(
            CompanyResearchSummaryRepository repository,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Map<String, CurrentCompanyMetric> loadAll() {
        var result = new LinkedHashMap<String, CurrentCompanyMetric>();
        repository.findAll().forEach((ticker, value) -> {
            var now = clock.instant();
            var comparable = value.scoreComparableAt(now, MAXIMUM_AGE);
            var priceSignalsCurrent = value.priceSignalsCurrentAt(now, MAXIMUM_AGE);
            result.put(ticker, new CurrentCompanyMetric(
                ticker,
                comparable ? value.marketCap() : null,
                comparable ? value.totalScore() : null,
                comparable ? value.qualityScore() : null,
                comparable ? value.buyScore() : null,
                comparable ? value.buyLabel() : null,
                comparable ? value.appealScore() : null,
                comparable ? value.crowdingScore() : null,
                comparable ? value.revenueGrowthYoY() : null,
                comparable ? value.operatingMargin() : null,
                comparable ? value.evToSales() : null,
                priceSignalsCurrent ? value.priceBottomScore() : null,
                priceSignalsCurrent ? value.volumeConfirmationScore() : null,
                priceSignalsCurrent ? value.failureRiskScore() : null,
                priceSignalsCurrent ? value.confirmedBottomScore() : null,
                priceSignalsCurrent ? value.confirmedBottomState() : null,
                comparable && value.valuationEligible(),
                value.updatedAt()
            ));
        });
        return Map.copyOf(result);
    }
}
