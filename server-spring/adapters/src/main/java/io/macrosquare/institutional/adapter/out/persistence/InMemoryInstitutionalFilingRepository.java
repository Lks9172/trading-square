package io.macrosquare.institutional.adapter.out.persistence;

import io.macrosquare.institutional.application.port.out.InstitutionalFilingRepository;
import io.macrosquare.institutional.domain.model.InstitutionalFiling;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.util.Optional;

/** Non-production fallback used when the Spring-owned PostgreSQL mode is disabled. */
public final class InMemoryInstitutionalFilingRepository implements InstitutionalFilingRepository {

    private final ConcurrentHashMap<String, InstitutionalFiling> filings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> latestCollectedByManager = new ConcurrentHashMap<>();

    @Override
    public int save(List<InstitutionalFiling> values) {
        values.forEach(value -> filings.put(value.accessionNumber(), value));
        var collectedAt = Instant.now();
        values.forEach(value -> latestCollectedByManager.put(value.manager().cik(), collectedAt));
        return values.size();
    }

    @Override
    public List<InstitutionalFiling> loadLatestPerManager(int filingLimit) {
        var grouped = new LinkedHashMap<String, List<InstitutionalFiling>>();
        filings.values().stream().collect(java.util.stream.Collectors.groupingBy(
                value -> value.manager().cik(), LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .forEach((key, value) -> grouped.put(key, value));
        return grouped.values().stream()
                .flatMap(values -> values.stream()
                        .sorted(Comparator.comparing(InstitutionalFiling::reportPeriod).reversed())
                        .limit(filingLimit))
                .toList();
    }

    @Override
    public Optional<Instant> latestCollectedAt(List<String> managerCiks) {
        if (managerCiks == null || managerCiks.isEmpty()) return Optional.empty();
        var values = managerCiks.stream().distinct().map(latestCollectedByManager::get).toList();
        if (values.stream().anyMatch(java.util.Objects::isNull)) return Optional.empty();
        return values.stream().min(Instant::compareTo);
    }
}
