package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.SearchResult;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.SummaryResult;
import io.macrosquare.company.application.port.in.EnrichCompanyResearchUseCase;
import io.macrosquare.company.application.port.in.QueryCompanyReadUseCase;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.time.Clock;
import java.time.Duration;

public final class QueryCompanyReadService implements QueryCompanyReadUseCase {

    static final int MAX_SEARCH_LIMIT = 12;
    static final int MAX_SUMMARY_TICKERS = 20;
    static final Duration MAXIMUM_SUMMARY_AGE = Duration.ofHours(2);

    private final LoadCompanyReadPort companyReadPort;
    private final EnrichCompanyResearchUseCase enrichCompanyResearch;
    private final CompanyResearchSummaryRepository summaryRepository;
    private final Clock clock;

    public QueryCompanyReadService(LoadCompanyReadPort companyReadPort) {
        this(companyReadPort, (ticker, baseline) -> baseline, null, Clock.systemUTC());
    }

    public QueryCompanyReadService(
            LoadCompanyReadPort companyReadPort,
            EnrichCompanyResearchUseCase enrichCompanyResearch
    ) {
        this(companyReadPort, enrichCompanyResearch, null, Clock.systemUTC());
    }

    public QueryCompanyReadService(
            LoadCompanyReadPort companyReadPort,
            EnrichCompanyResearchUseCase enrichCompanyResearch,
            CompanyResearchSummaryRepository summaryRepository
    ) {
        this(companyReadPort, enrichCompanyResearch, summaryRepository, Clock.systemUTC());
    }

    public QueryCompanyReadService(
            LoadCompanyReadPort companyReadPort,
            EnrichCompanyResearchUseCase enrichCompanyResearch,
            CompanyResearchSummaryRepository summaryRepository,
            Clock clock
    ) {
        this.companyReadPort = Objects.requireNonNull(companyReadPort);
        this.enrichCompanyResearch = Objects.requireNonNull(enrichCompanyResearch);
        this.summaryRepository = summaryRepository;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SearchResult search(String query, int requestedLimit) {
        var normalizedQuery = normalizeSearchQuery(query);
        if (normalizedQuery.isEmpty()) return new SearchResult(List.of());
        var limit = Math.max(1, Math.min(MAX_SEARCH_LIMIT, requestedLimit));
        return companyReadPort.search(normalizedQuery, limit);
    }

    @Override
    public SummaryResult summaries(List<String> tickers) {
        var normalized = normalizeTickers(tickers);
        if (normalized.isEmpty()) return new SummaryResult(List.of());
        var captured = companyReadPort.summaries(normalized);
        if (summaryRepository == null) return captured;
        var current = summaryRepository.findAll();
        return new SummaryResult(captured.items().stream().map(item -> {
            var value = current.get(item.ticker().toUpperCase(Locale.ROOT).replace('.', '-'));
            if (value == null) {
                return new io.macrosquare.company.application.model.CompanyReadModels.Summary(
                        item.ticker(), item.name(), null, null, null, null, null, null,
                        null, null, null, null, null, null, null);
            }
            var now = clock.instant();
            var comparable = value.scoreComparableAt(now, MAXIMUM_SUMMARY_AGE);
            var priceSignalsCurrent = value.priceSignalsCurrentAt(now, MAXIMUM_SUMMARY_AGE);
            return new io.macrosquare.company.application.model.CompanyReadModels.Summary(
                    item.ticker(), item.name(), comparable ? value.totalScore() : null,
                    comparable ? value.buyScore() : null, comparable ? value.buyLabel() : null,
                    decimal(comparable ? value.revenueGrowthYoY() : null),
                    decimal(comparable ? value.operatingMargin() : null),
                    decimal(comparable ? value.evToSales() : null),
                    comparable ? value.crowdingScore() : null,
                    comparable ? value.appealScore() : null,
                    priceSignalsCurrent ? bottomState(value.confirmedBottomState()) : null,
                    null,
                    priceSignalsCurrent ? value.priceBottomScore() : null,
                    priceSignalsCurrent ? value.volumeConfirmationScore() : null,
                    priceSignalsCurrent ? value.failureRiskScore() : null);
        }).toList());
    }

    @Override
    public Research detail(String ticker) {
        var normalized = normalizeDetailTicker(ticker);
        var baseline = companyReadPort.detail(normalized);
        return enrichCompanyResearch.enrich(normalized, baseline);
    }

    private static String normalizeSearchQuery(String query) {
        if (query == null) return "";
        return query.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private static List<String> normalizeTickers(List<String> tickers) {
        if (tickers == null || tickers.isEmpty()) return List.of();
        var distinct = new LinkedHashSet<String>();
        for (var ticker : tickers) {
            if (ticker == null) continue;
            var normalized = ticker.trim().toUpperCase(Locale.ROOT);
            if (!normalized.isEmpty()) distinct.add(normalized);
            if (distinct.size() == MAX_SUMMARY_TICKERS) break;
        }
        return List.copyOf(new ArrayList<>(distinct));
    }

    private static String normalizeDetailTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        var normalized = ticker.trim().toUpperCase(Locale.ROOT);
        return "MMC".equals(normalized) ? "MRSH" : normalized;
    }

    private static java.math.BigDecimal decimal(Double value) {
        return value == null ? null : java.math.BigDecimal.valueOf(value);
    }

    private static String bottomState(String value) {
        if (value == null) return null;
        return switch (value) {
            case "CONVICTION" -> "확신";
            case "CANDIDATE" -> "후보";
            case "UNMET" -> "미충족";
            default -> value;
        };
    }
}
