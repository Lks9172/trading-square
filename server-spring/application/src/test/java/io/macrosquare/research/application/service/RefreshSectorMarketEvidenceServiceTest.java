package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.CurrentSectorMarketEvidence;
import io.macrosquare.research.application.model.ResearchCatalogModels;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.SectorMarketEvidenceRepository;
import io.macrosquare.research.domain.rotation.SectorConstituentPriceSeries;
import io.macrosquare.research.domain.rotation.SectorFundFlowEvidence;
import io.macrosquare.research.domain.rotation.SectorFundFlowPolicy;
import io.macrosquare.research.domain.rotation.SectorFundHistoryPoint;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthEvidence;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthPolicy;
import io.macrosquare.research.domain.rotation.SectorPricePoint;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefreshSectorMarketEvidenceServiceTest {

    @Test
    void refreshesOfficialFlowAndTrackedBreadthIndependently() {
        var sectorKeys = List.of(
                "SECTOR_XLK", "SECTOR_XLF", "SECTOR_XLE", "SECTOR_XLV", "SECTOR_XLI",
                "SECTOR_XLY", "SECTOR_XLC", "SECTOR_XLB", "SECTOR_XLRE", "SECTOR_XLU", "SECTOR_XLP");
        var catalog = new LoadResearchCatalogPort() {
            @Override public ResearchCatalogModels.ThemeCatalog loadThemes() {
                return new ResearchCatalogModels.ThemeCatalog(List.of());
            }
            @Override public ResearchCatalogModels.SectorCatalog loadSectors() {
                return new ResearchCatalogModels.SectorCatalog(sectorKeys.stream().map(key -> {
                    var tickers = java.util.stream.IntStream.range(0, 10)
                            .mapToObj(index -> key.substring("SECTOR_".length()) + "T" + index).toList();
                    return new ResearchCatalogModels.Sector(
                            key, key, key, key, tickers, null, null, null, List.of());
                }).toList(), null);
            }
            @Override public ResearchCatalogModels.ThemeDetail loadTheme(String id, String sort, String companySort) {
                throw new UnsupportedOperationException();
            }
            @Override public ResearchCatalogModels.SectorDetail loadSector(String id) {
                throw new UnsupportedOperationException();
            }
        };
        var flowSaved = new AtomicReference<SectorFundFlowEvidence>();
        var breadthSaved = new AtomicReference<SectorPriceBreadthEvidence>();
        var repository = new SectorMarketEvidenceRepository() {
            @Override public void saveFundFlow(String key, String ticker, SectorFundFlowEvidence evidence,
                                                Instant at) { flowSaved.set(evidence); }
            @Override public void savePriceBreadth(String key, SectorPriceBreadthEvidence evidence,
                                                    Instant at) { breadthSaved.set(evidence); }
            @Override public CurrentSectorMarketEvidence loadCurrent(String key, LocalDate date, int age) {
                return new CurrentSectorMarketEvidence(null, null);
            }
        };
        var clock = Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC);
        var service = new RefreshSectorMarketEvidenceService(
                catalog,
                ignored -> fundHistory(),
                ticker -> priceHistory(ticker, LocalDate.of(2026, 8, 8)),
                repository,
                new SectorFundFlowPolicy(),
                new SectorPriceBreadthPolicy(),
                clock);

        var report = service.refresh();

        assertTrue(report.successful());
        assertEquals(11, report.attemptedSectors());
        assertEquals(11, report.fundFlowWritten());
        assertEquals(11, report.priceBreadthWritten());
        assertTrue(flowSaved.get().score() > 50);
        assertEquals(100, breadthSaved.get().score());
    }

    private static List<SectorFundHistoryPoint> fundHistory() {
        var result = new ArrayList<SectorFundHistoryPoint>();
        for (var index = 0; index < 21; index++) {
            var nav = 100d + index;
            var shares = 1_000_000d + index * 10_000d;
            result.add(new SectorFundHistoryPoint(
                    LocalDate.of(2026, 7, 1).plusDays(index), nav, shares, nav * shares));
        }
        return result;
    }

    private static SectorConstituentPriceSeries priceHistory(String ticker, LocalDate latest) {
        var points = new ArrayList<SectorPricePoint>();
        for (var index = 0; index < 220; index++) {
            points.add(new SectorPricePoint(latest.minusDays(219L - index), 100d + index));
        }
        return new SectorConstituentPriceSeries(ticker, points);
    }
}
