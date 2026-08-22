package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import io.macrosquare.company.application.port.in.ResolveCompanyAnalystHistoryUseCase;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistorySeedPort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistoryStorePort;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.company.domain.model.Ticker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Mode.DUAL_COMPARE;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Mode.SEED_ONLY;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Source.SEED;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Source.SEED_FALLBACK;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.Source.STORE;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.AVAILABLE;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.MISSING;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.NOT_EXPECTED;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.NOT_READ;
import static io.macrosquare.company.application.model.CompanyAnalystHistoryRead.SourceState.UNAVAILABLE;

/**
 * Selects the application-owned store and uses immutable cutover evidence only
 * for first-seed fallback or optional parity diagnostics.
 */
public final class ResolveCompanyAnalystHistoryService implements ResolveCompanyAnalystHistoryUseCase {

    private static final String STORE_MISSING = "analystHistory.storeMissing";
    private static final String STORE_UNAVAILABLE = "analystHistory.storeUnavailable";
    private static final String SEED_UNAVAILABLE = "analystHistory.seedUnavailable";
    private static final String POINT_COUNT = "analystHistory.pointCount";
    private static final String POINTS = "analystHistory.points";

    private final LoadCompanyAnalystHistorySeedPort seedHistoryPort;
    private final LoadCompanyAnalystHistoryStorePort storeHistoryPort;
    private final CompanyAnalystHistoryRead.Mode mode;
    private final Set<String> managedTickers;

    public ResolveCompanyAnalystHistoryService(
            LoadCompanyAnalystHistorySeedPort seedHistoryPort,
            LoadCompanyAnalystHistoryStorePort storeHistoryPort,
            CompanyAnalystHistoryRead.Mode mode,
            List<String> managedTickers
    ) {
        this.seedHistoryPort = Objects.requireNonNull(seedHistoryPort);
        this.storeHistoryPort = Objects.requireNonNull(storeHistoryPort);
        this.mode = Objects.requireNonNull(mode);
        this.managedTickers = normalizeTickers(managedTickers);
    }

    @Override
    public CompanyAnalystHistoryRead resolve(String ticker) {
        var normalizedTicker = normalizeTicker(ticker);
        if (mode == SEED_ONLY) return seedOnly(normalizedTicker, NOT_READ);
        if (!managedTickers.contains(normalizedTicker)) return seedOnly(normalizedTicker, NOT_EXPECTED);
        if (mode == DUAL_COMPARE) return dualCompare(normalizedTicker);
        return storePreferred(normalizedTicker);
    }

    private CompanyAnalystHistoryRead dualCompare(String ticker) {
        var seed = seedHistoryPort.load(ticker);
        final java.util.Optional<List<CompanyAnalystHistoryPoint>> store;
        try {
            store = storeHistoryPort.load(ticker);
        } catch (RuntimeException error) {
            return read(ticker, seed, SEED, AVAILABLE, UNAVAILABLE, false,
                    List.of(STORE_UNAVAILABLE), seed, null);
        }
        if (store.isEmpty()) {
            return read(ticker, seed, SEED, AVAILABLE, MISSING, false,
                    List.of(STORE_MISSING), seed, null);
        }
        var storeHistory = store.orElseThrow();
        return read(ticker, seed, SEED, AVAILABLE, AVAILABLE, true,
                compare(seed, storeHistory), seed, storeHistory);
    }

    private CompanyAnalystHistoryRead storePreferred(String ticker) {
        final java.util.Optional<List<CompanyAnalystHistoryPoint>> store;
        try {
            store = storeHistoryPort.load(ticker);
        } catch (RuntimeException error) {
            var seed = seedHistoryPort.load(ticker);
            return read(ticker, seed, SEED_FALLBACK, AVAILABLE, UNAVAILABLE, false,
                    List.of(STORE_UNAVAILABLE), seed, null);
        }

        if (store.isEmpty()) {
            var seed = seedHistoryPort.load(ticker);
            return read(ticker, seed, SEED_FALLBACK, AVAILABLE, MISSING, false,
                    List.of(STORE_MISSING), seed, null);
        }

        var storeHistory = store.orElseThrow();
        final List<CompanyAnalystHistoryPoint> seed;
        try {
            seed = seedHistoryPort.load(ticker);
        } catch (RuntimeException error) {
            return read(ticker, storeHistory, STORE, UNAVAILABLE, AVAILABLE, false,
                    List.of(SEED_UNAVAILABLE), null, storeHistory);
        }
        return read(ticker, storeHistory, STORE, AVAILABLE, AVAILABLE, true,
                compare(seed, storeHistory), seed, storeHistory);
    }

    private CompanyAnalystHistoryRead seedOnly(
            String ticker,
            CompanyAnalystHistoryRead.SourceState storeState
    ) {
        var seed = seedHistoryPort.load(ticker);
        return read(ticker, seed, SEED, AVAILABLE, storeState, false,
                List.of(), seed, null);
    }

    private CompanyAnalystHistoryRead read(
            String ticker,
            List<CompanyAnalystHistoryPoint> selected,
            CompanyAnalystHistoryRead.Source selectedSource,
            CompanyAnalystHistoryRead.SourceState seedState,
            CompanyAnalystHistoryRead.SourceState storeState,
            boolean comparisonPerformed,
            List<String> differences,
            List<CompanyAnalystHistoryPoint> seed,
            List<CompanyAnalystHistoryPoint> store
    ) {
        return new CompanyAnalystHistoryRead(
                ticker,
                selected,
                mode,
                selectedSource,
                seedState,
                storeState,
                comparisonPerformed,
                differences,
                seed == null ? null : seed.size(),
                store == null ? null : store.size(),
                latestDate(seed),
                latestDate(store)
        );
    }

    private static List<String> compare(
            List<CompanyAnalystHistoryPoint> seed,
            List<CompanyAnalystHistoryPoint> store
    ) {
        var differences = new ArrayList<String>();
        if (seed.size() != store.size()) differences.add(POINT_COUNT);
        if (!seed.equals(store)) differences.add(POINTS);
        return List.copyOf(differences);
    }

    private static LocalDate latestDate(List<CompanyAnalystHistoryPoint> history) {
        if (history == null || history.isEmpty()) return null;
        return history.stream().map(CompanyAnalystHistoryPoint::date).max(LocalDate::compareTo).orElse(null);
    }

    private static Set<String> normalizeTickers(List<String> tickers) {
        Objects.requireNonNull(tickers, "managedTickers");
        var normalized = new LinkedHashSet<String>();
        for (var ticker : tickers) normalized.add(normalizeTicker(ticker));
        return Set.copyOf(normalized);
    }

    private static String normalizeTicker(String ticker) {
        return new Ticker(Objects.requireNonNull(ticker, "ticker").replace('.', '-')).value();
    }
}
