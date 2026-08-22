package io.macrosquare.company.adapter.out.persistence;

import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Non-production fallback used by the file profile and isolated tests. */
public final class InMemoryCompanyResearchSummaryRepository implements CompanyResearchSummaryRepository {

    private final ConcurrentHashMap<String, CompanyResearchSummarySnapshot> values = new ConcurrentHashMap<>();

    @Override
    public Optional<CompanyResearchSummarySnapshot> find(String normalizedTicker) {
        return Optional.ofNullable(values.get(normalize(normalizedTicker)));
    }

    @Override
    public Map<String, CompanyResearchSummarySnapshot> findAll() {
        return Map.copyOf(new LinkedHashMap<>(values));
    }

    @Override
    public void save(CompanyResearchSummarySnapshot snapshot) {
        values.put(snapshot.ticker(), snapshot);
    }

    private static String normalize(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }
}
