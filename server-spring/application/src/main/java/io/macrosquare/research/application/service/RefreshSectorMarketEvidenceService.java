package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.SectorMarketEvidenceRefreshReport;
import io.macrosquare.research.application.model.SectorMarketEvidenceRefreshReport.Failure;
import io.macrosquare.research.application.port.in.RefreshSectorMarketEvidenceUseCase;
import io.macrosquare.research.application.port.out.LoadOfficialSectorFundHistoryPort;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.LoadSectorConstituentPriceHistoryPort;
import io.macrosquare.research.application.port.out.SectorMarketEvidenceRepository;
import io.macrosquare.research.domain.rotation.SectorConstituentPriceSeries;
import io.macrosquare.research.domain.rotation.SectorFundFlowPolicy;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthPolicy;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Refreshes current sector evidence without coupling the Research domain to providers or Company types. */
public final class RefreshSectorMarketEvidenceService implements RefreshSectorMarketEvidenceUseCase {

    private static final Set<String> STANDARD_SECTORS = Set.of(
            "SECTOR_XLK", "SECTOR_XLF", "SECTOR_XLE", "SECTOR_XLV", "SECTOR_XLI",
            "SECTOR_XLY", "SECTOR_XLC", "SECTOR_XLB", "SECTOR_XLRE", "SECTOR_XLU", "SECTOR_XLP");

    private final LoadResearchCatalogPort catalog;
    private final LoadOfficialSectorFundHistoryPort fundHistory;
    private final LoadSectorConstituentPriceHistoryPort priceHistory;
    private final SectorMarketEvidenceRepository repository;
    private final SectorFundFlowPolicy fundFlowPolicy;
    private final SectorPriceBreadthPolicy priceBreadthPolicy;
    private final Clock clock;

    public RefreshSectorMarketEvidenceService(
            LoadResearchCatalogPort catalog,
            LoadOfficialSectorFundHistoryPort fundHistory,
            LoadSectorConstituentPriceHistoryPort priceHistory,
            SectorMarketEvidenceRepository repository,
            SectorFundFlowPolicy fundFlowPolicy,
            SectorPriceBreadthPolicy priceBreadthPolicy,
            Clock clock
    ) {
        this.catalog = Objects.requireNonNull(catalog);
        this.fundHistory = Objects.requireNonNull(fundHistory);
        this.priceHistory = Objects.requireNonNull(priceHistory);
        this.repository = Objects.requireNonNull(repository);
        this.fundFlowPolicy = Objects.requireNonNull(fundFlowPolicy);
        this.priceBreadthPolicy = Objects.requireNonNull(priceBreadthPolicy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public SectorMarketEvidenceRefreshReport refresh() {
        var startedAt = clock.instant();
        var asOfDate = LocalDate.now(clock);
        var failures = new ArrayList<Failure>();
        var flowWritten = 0;
        var breadthWritten = 0;
        var sectors = catalog.loadSectors().sectors().stream()
                .filter(value -> STANDARD_SECTORS.contains(value.sectorKey()))
                .toList();
        var distinctSectorKeys = sectors.stream().map(value -> value.sectorKey()).distinct().count();
        if (sectors.size() != STANDARD_SECTORS.size() || distinctSectorKeys != STANDARD_SECTORS.size()) {
            throw new IllegalStateException("standard sector evidence universe must contain exactly 11 unique sectors");
        }
        for (var sector : sectors) {
            var fundTicker = fundTicker(sector.sectorKey());
            try {
                var result = fundFlowPolicy.evaluate(fundHistory.load(fundTicker));
                if (result.isPresent()) {
                    repository.saveFundFlow(sector.sectorKey(), fundTicker, result.orElseThrow(), clock.instant());
                    flowWritten++;
                } else {
                    failures.add(new Failure(sector.sectorKey(), "FUND_FLOW", "fewer than 21 valid fund history points"));
                }
            } catch (RuntimeException error) {
                failures.add(new Failure(sector.sectorKey(), "FUND_FLOW", errorType(error)));
            }

            var prices = new ArrayList<SectorConstituentPriceSeries>();
            for (var ticker : sector.tickers()) {
                try {
                    prices.add(priceHistory.load(ticker));
                } catch (RuntimeException error) {
                    prices.add(new SectorConstituentPriceSeries(ticker, List.of()));
                }
            }
            try {
                var result = priceBreadthPolicy.evaluate(asOfDate, prices);
                if (result.isPresent()) {
                    repository.savePriceBreadth(sector.sectorKey(), result.orElseThrow(), clock.instant());
                    breadthWritten++;
                } else {
                    failures.add(new Failure(sector.sectorKey(), "PRICE_BREADTH", "coverage/date/history gate not met"));
                }
            } catch (RuntimeException error) {
                failures.add(new Failure(sector.sectorKey(), "PRICE_BREADTH", errorType(error)));
            }
        }
        return new SectorMarketEvidenceRefreshReport(
                startedAt, clock.instant(), sectors.size(), flowWritten, breadthWritten, failures);
    }

    private static String fundTicker(String sectorKey) {
        var ticker = sectorKey.substring("SECTOR_".length()).toUpperCase(Locale.ROOT);
        if (!ticker.matches("XL[A-Z]{1,2}")) throw new IllegalArgumentException("unsupported sector ETF key");
        return ticker;
    }

    private static String errorType(RuntimeException error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        var message = root.getMessage();
        if (message == null || message.isBlank()) return root.getClass().getSimpleName();
        var compact = message.replaceAll("\\s+", " ").trim();
        if (compact.length() > 240) compact = compact.substring(0, 237) + "...";
        return root.getClass().getSimpleName() + ": " + compact;
    }
}
