package io.macrosquare.research.application.port.out;

import io.macrosquare.research.application.model.CurrentSectorMarketEvidence;
import io.macrosquare.research.domain.rotation.SectorFundFlowEvidence;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthEvidence;

import java.time.Instant;
import java.time.LocalDate;

public interface SectorMarketEvidenceRepository {
    void saveFundFlow(String sectorKey, String fundTicker, SectorFundFlowEvidence evidence, Instant collectedAt);

    void savePriceBreadth(String sectorKey, SectorPriceBreadthEvidence evidence, Instant collectedAt);

    CurrentSectorMarketEvidence loadCurrent(String sectorKey, LocalDate asOfDate, int maxAgeDays);

    static SectorMarketEvidenceRepository unavailable() {
        return new SectorMarketEvidenceRepository() {
            @Override public void saveFundFlow(String key, String ticker, SectorFundFlowEvidence evidence, Instant at) { }
            @Override public void savePriceBreadth(String key, SectorPriceBreadthEvidence evidence, Instant at) { }
            @Override public CurrentSectorMarketEvidence loadCurrent(String key, LocalDate date, int age) {
                return new CurrentSectorMarketEvidence(null, null);
            }
        };
    }
}
