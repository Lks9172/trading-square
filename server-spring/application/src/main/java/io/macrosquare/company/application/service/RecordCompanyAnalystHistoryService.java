package io.macrosquare.company.application.service;

import io.macrosquare.company.application.port.in.CompanyAnalystHistoryRecordReport;
import io.macrosquare.company.application.port.in.RecordCompanyAnalystHistoryUseCase;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistorySeedPort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistoryStorePort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystUniversePort;
import io.macrosquare.company.application.port.out.SaveCompanyAnalystHistoryPort;
import io.macrosquare.company.domain.model.Ticker;
import io.macrosquare.company.domain.service.CompanyAnalystHistoryPolicy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Seeds once from immutable cutover evidence, then advances only the owned store. */
public final class RecordCompanyAnalystHistoryService implements RecordCompanyAnalystHistoryUseCase {

    private final LoadCompanyAnalystConsensusPort consensusPort;
    private final LoadCompanyAnalystHistorySeedPort seedHistoryPort;
    private final LoadCompanyAnalystHistoryStorePort storeHistoryPort;
    private final SaveCompanyAnalystHistoryPort saveHistoryPort;
    private final LoadCompanyAnalystUniversePort analystUniversePort;
    private final CompanyAnalystHistoryPolicy historyPolicy;
    private final Clock clock;
    private final List<String> tickers;
    private final int retentionPoints;

    public RecordCompanyAnalystHistoryService(
            LoadCompanyAnalystConsensusPort consensusPort,
            LoadCompanyAnalystHistorySeedPort seedHistoryPort,
            LoadCompanyAnalystHistoryStorePort storeHistoryPort,
            SaveCompanyAnalystHistoryPort saveHistoryPort,
            CompanyAnalystHistoryPolicy historyPolicy,
            Clock clock,
            List<String> tickers,
            int retentionPoints
    ) {
        this(
                consensusPort, seedHistoryPort, storeHistoryPort, saveHistoryPort,
                List::of, historyPolicy, clock, tickers, retentionPoints
        );
    }

    public RecordCompanyAnalystHistoryService(
            LoadCompanyAnalystConsensusPort consensusPort,
            LoadCompanyAnalystHistorySeedPort seedHistoryPort,
            LoadCompanyAnalystHistoryStorePort storeHistoryPort,
            SaveCompanyAnalystHistoryPort saveHistoryPort,
            LoadCompanyAnalystUniversePort analystUniversePort,
            CompanyAnalystHistoryPolicy historyPolicy,
            Clock clock,
            List<String> tickers,
            int retentionPoints
    ) {
        this.consensusPort = Objects.requireNonNull(consensusPort);
        this.seedHistoryPort = Objects.requireNonNull(seedHistoryPort);
        this.storeHistoryPort = Objects.requireNonNull(storeHistoryPort);
        this.saveHistoryPort = Objects.requireNonNull(saveHistoryPort);
        this.analystUniversePort = Objects.requireNonNull(analystUniversePort);
        this.historyPolicy = Objects.requireNonNull(historyPolicy);
        this.clock = Objects.requireNonNull(clock);
        this.tickers = normalizeTickers(tickers);
        if (retentionPoints < 1) throw new IllegalArgumentException("retentionPoints must be positive");
        this.retentionPoints = retentionPoints;
    }

    @Override
    public CompanyAnalystHistoryRecordReport recordDailyHistory() {
        var startedAt = clock.instant();
        var observationDate = LocalDate.ofInstant(startedAt, ZoneOffset.UTC);
        var failures = new ArrayList<CompanyAnalystHistoryRecordReport.Failure>();
        var written = 0;
        var seeded = 0;
        var tickers = currentTickers();

        for (var ticker : tickers) {
            try {
                var stored = storeHistoryPort.load(ticker);
                var existing = stored.orElseGet(() -> seedHistoryPort.load(ticker));
                var consensus = consensusPort.load(ticker);
                var next = historyPolicy.recordDaily(
                        existing,
                        observationDate,
                        consensus,
                        retentionPoints
                );
                saveHistoryPort.save(ticker, next, clock.instant());
                written++;
                if (stored.isEmpty()) seeded++;
            } catch (RuntimeException error) {
                failures.add(new CompanyAnalystHistoryRecordReport.Failure(
                        ticker,
                        error.getClass().getSimpleName()
                ));
            }
        }

        return new CompanyAnalystHistoryRecordReport(
                startedAt,
                clock.instant(),
                observationDate,
                tickers.size(),
                written,
                seeded,
                failures
        );
    }

    private List<String> currentTickers() {
        var combined = new ArrayList<>(tickers);
        combined.addAll(Objects.requireNonNull(analystUniversePort.loadTickers(), "analyst universe"));
        return normalizeTickers(combined);
    }

    private static List<String> normalizeTickers(List<String> values) {
        Objects.requireNonNull(values, "tickers");
        var normalized = new LinkedHashSet<String>();
        for (var value : values) {
            var ticker = new Ticker(Objects.requireNonNull(value, "ticker").replace('.', '-')).value();
            normalized.add(ticker);
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("tickers must not be empty");
        return List.copyOf(normalized);
    }
}
