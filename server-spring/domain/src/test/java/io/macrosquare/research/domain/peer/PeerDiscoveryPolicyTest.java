package io.macrosquare.research.domain.peer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PeerDiscoveryPolicyTest {

    private static final LocalDate AS_OF = LocalDate.parse("2026-07-21");

    @Test
    void ranksExactAndBroaderSicPeersAndExcludesRetiredCompanies() {
        var target = taxonomy("AAA", 7372, "technology", null);
        var exact = taxonomy("BBB", 7372, "technology", null);
        var industry = taxonomy("CCC", 7373, "technology", null);
        var major = taxonomy("DDD", 7311, "communication-services", null);
        var sector = taxonomy("EEE", 3571, "technology", null);
        var retired = taxonomy("FFF", 7372, "technology", LocalDate.parse("2025-12-31"));

        var result = new PeerDiscoveryPolicy().discover(
                target, List.of(sector, retired, major, exact, industry), AS_OF, 10);

        assertEquals(List.of("BBB", "CCC", "DDD", "EEE"),
                result.peers().stream().map(PeerMatch::ticker).toList());
        assertEquals(List.of(100, 85, 70, 45),
                result.peers().stream().map(PeerMatch::similarityScore).toList());
        assertEquals(4, result.candidateCount());
    }

    @Test
    void mapsRealEstateBeforeTheBroaderFinancialSicRange() {
        var policy = new SicSectorPolicy();

        assertEquals("real-estate", policy.classify(6798));
        assertEquals("financials", policy.classify(6211));
        assertEquals("energy", policy.classify(1311));
        assertEquals("technology", policy.classify(7372));
    }

    private static PeerTaxonomy taxonomy(String ticker, int sic, String sector, LocalDate validTo) {
        return new PeerTaxonomy(
                ticker, "0000000001", ticker + " Company", sic, "Description", sector,
                LocalDate.parse("2020-01-01"), validTo);
    }
}
