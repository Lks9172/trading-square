package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.SectorTotalReturnRefreshReport;
import io.macrosquare.market.application.model.MarketCollectionBatch;
import io.macrosquare.market.application.port.in.RefreshSectorTotalReturnHistoryUseCase;
import io.macrosquare.market.application.port.out.CollectSectorTotalReturnHistoryPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Idempotently backfills and rolls forward adjusted-close sector histories. */
public final class RefreshSectorTotalReturnHistoryService implements RefreshSectorTotalReturnHistoryUseCase {

    public static final List<String> REQUIRED_SERIES = List.of(
            "SPY_TR",
            "XLK_TR", "XLF_TR", "XLE_TR", "XLV_TR", "XLI_TR", "XLY_TR", "XLC_TR", "XLB_TR",
            "XLRE_TR", "XLU_TR", "XLP_TR", "SOXX_TR", "SMH_TR", "ITA_TR", "GRID_TR", "IGF_TR"
    );
    public static final List<String> STANDARD_ROTATION_SERIES = List.of(
            "SPY_TR",
            "XLK_TR", "XLF_TR", "XLE_TR", "XLV_TR", "XLI_TR", "XLY_TR", "XLC_TR", "XLB_TR",
            "XLRE_TR", "XLU_TR", "XLP_TR"
    );
    // Seven walk-forward years plus the longest formation/risk window. XLC,
    // the youngest standard-sector ETF, currently provides just over 2,000
    // observations, so 2,000 is both strict and attainable for all series.
    static final int MINIMUM_FULL_HISTORY_POINTS = 2_000;

    private final CollectSectorTotalReturnHistoryPort collector;
    private final MarketObservationRepository repository;
    private final Clock clock;

    public RefreshSectorTotalReturnHistoryService(
            CollectSectorTotalReturnHistoryPort collector,
            MarketObservationRepository repository,
            Clock clock
    ) {
        this.collector = Objects.requireNonNull(collector);
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SectorTotalReturnRefreshReport refresh() {
        var coverage = coverage();
        var fullBackfill = coverage.requiresFullBackfill();
        var batch = collector.collect(fullBackfill
                ? CollectSectorTotalReturnHistoryPort.HistoryWindow.FULL
                : CollectSectorTotalReturnHistoryPort.HistoryWindow.RECENT);
        // Yahoo adjusted closes can revise every pre-dividend observation when
        // a distribution becomes effective. Detect a changed overlap and
        // rebase the whole window instead of stitching two adjustment bases.
        if (!fullBackfill && hasHistoricalRevision(batch, coverage.histories())) {
            fullBackfill = true;
            batch = collector.collect(CollectSectorTotalReturnHistoryPort.HistoryWindow.FULL);
        }
        if (batch.source() != MarketDataSource.YAHOO) {
            throw new IllegalStateException("sector total-return collector must preserve YAHOO provenance");
        }
        var validationFailures = validateStandardUniverseBatch(batch, fullBackfill);
        var failures = new java.util.ArrayList<>(batch.failures());
        for (var failure : validationFailures) {
            if (failures.stream().noneMatch(existing -> existing.key().equals(failure.key()))) {
                failures.add(failure);
            }
        }
        // The standard 11-sector percentile is one cross-sectional observation.
        // Persisting only part of that group can mix dates/adjustment bases and
        // make a missing sleeve look like a valid lower-coverage ranking. Keep the
        // last complete group instead; strategic-theme gaps do not block it.
        var persistable = validationFailures.isEmpty() ? batch.observations() : List.<MarketObservation>of();
        var persisted = persistable.isEmpty() ? 0 : repository.save(persistable);
        return new SectorTotalReturnRefreshReport(
                batch.startedAt(), batch.completedAt(), fullBackfill, REQUIRED_SERIES.size(),
                batch.observations().size(), persisted, failures);
    }

    private static List<MarketCollectionBatch.Failure> validateStandardUniverseBatch(
            MarketCollectionBatch batch,
            boolean fullBackfill
    ) {
        var byKey = batch.observations().stream().collect(java.util.stream.Collectors.groupingBy(
                MarketObservation::key, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        var failures = new java.util.ArrayList<MarketCollectionBatch.Failure>();
        for (var key : STANDARD_ROTATION_SERIES) {
            var observations = byKey.getOrDefault(key, List.of());
            var minimum = fullBackfill ? MINIMUM_FULL_HISTORY_POINTS : 5;
            if (observations.size() < minimum) {
                failures.add(new MarketCollectionBatch.Failure(
                        key, "Incomplete standard-sector total-return batch"));
            }
        }
        if (!failures.isEmpty()) return List.copyOf(failures);
        var latestDates = STANDARD_ROTATION_SERIES.stream()
                .map(key -> byKey.get(key).stream().map(MarketObservation::observationDate)
                        .max(LocalDate::compareTo).orElseThrow())
                .collect(java.util.stream.Collectors.toSet());
        if (latestDates.size() != 1) {
            failures.add(new MarketCollectionBatch.Failure(
                    "STANDARD_SECTOR_UNIVERSE", "Standard-sector latest dates are not aligned"));
        }
        return List.copyOf(failures);
    }

    private Coverage coverage() {
        var today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        var histories = new LinkedHashMap<String, List<MarketObservation>>();
        boolean fullBackfill = false;
        for (var key : REQUIRED_SERIES) {
            var history = repository.loadHistory(MarketDataSource.YAHOO, key);
            histories.put(key, history);
            if (history.size() < MINIMUM_FULL_HISTORY_POINTS) {
                fullBackfill = true;
                continue;
            }
            var latest = history.getLast().observationDate();
            if (latest.isAfter(today.plusDays(1)) || latest.isBefore(today.minusDays(7))) {
                fullBackfill = true;
            }
        }
        return new Coverage(fullBackfill, Map.copyOf(histories));
    }

    private static boolean hasHistoricalRevision(
            MarketCollectionBatch batch,
            Map<String, List<MarketObservation>> existing
    ) {
        var existingValues = new LinkedHashMap<String, Map<LocalDate, Double>>();
        existing.forEach((key, observations) -> {
            var values = new LinkedHashMap<LocalDate, Double>();
            observations.forEach(value -> values.put(value.observationDate(), value.value()));
            existingValues.put(key, values);
        });
        for (var observation : batch.observations()) {
            var prior = existingValues.getOrDefault(observation.key(), Map.of())
                    .get(observation.observationDate());
            if (prior == null || prior <= 0) continue;
            if (Math.abs(observation.value() / prior - 1d) > 0.0000001d) return true;
        }
        return false;
    }

    private record Coverage(
            boolean requiresFullBackfill,
            Map<String, List<MarketObservation>> histories
    ) {
    }
}
