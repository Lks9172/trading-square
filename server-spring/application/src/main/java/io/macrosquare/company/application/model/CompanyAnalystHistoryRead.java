package io.macrosquare.company.application.model;

import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.company.domain.model.Ticker;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Analyst-history selection plus optional cutover-seed comparison metadata. */
public record CompanyAnalystHistoryRead(
        String ticker,
        List<CompanyAnalystHistoryPoint> history,
        Mode mode,
        Source selectedSource,
        SourceState seedState,
        SourceState storeState,
        boolean comparisonPerformed,
        List<String> differences,
        Integer seedPointCount,
        Integer storePointCount,
        LocalDate seedLatestDate,
        LocalDate storeLatestDate
) {
    public CompanyAnalystHistoryRead {
        ticker = new Ticker(Objects.requireNonNull(ticker, "ticker").replace('.', '-')).value();
        history = List.copyOf(Objects.requireNonNull(history, "history"));
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(selectedSource, "selectedSource");
        Objects.requireNonNull(seedState, "seedState");
        Objects.requireNonNull(storeState, "storeState");
        differences = List.copyOf(Objects.requireNonNull(differences, "differences"));
        validateCount(seedPointCount, "seedPointCount");
        validateCount(storePointCount, "storePointCount");

        if (selectedSource == Source.STORE && storeState != SourceState.AVAILABLE) {
            throw new IllegalArgumentException("selected store history must be available");
        }
        if (selectedSource != Source.STORE && seedState != SourceState.AVAILABLE) {
            throw new IllegalArgumentException("selected seed history must be available");
        }
        if (comparisonPerformed
                && (seedState != SourceState.AVAILABLE || storeState != SourceState.AVAILABLE)) {
            throw new IllegalArgumentException("comparison requires both history sources");
        }

        var selectedCount = selectedSource == Source.STORE ? storePointCount : seedPointCount;
        if (selectedCount == null || selectedCount != history.size()) {
            throw new IllegalArgumentException("selected history point count is inconsistent");
        }
    }

    public boolean matched() {
        return differences.isEmpty();
    }

    private static void validateCount(Integer value, String field) {
        if (value != null && value < 0) throw new IllegalArgumentException(field + " must not be negative");
    }

    public enum Mode {
        SEED_ONLY,
        DUAL_COMPARE,
        STORE_PREFERRED
    }

    public enum Source {
        SEED,
        STORE,
        SEED_FALLBACK
    }

    public enum SourceState {
        NOT_EXPECTED,
        NOT_READ,
        AVAILABLE,
        MISSING,
        UNAVAILABLE
    }
}
