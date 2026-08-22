package io.macrosquare.market.domain.regime;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MacroRegimePolicyTest {

    private final MacroRegimePolicy policy = new MacroRegimePolicy();

    @Test
    void anExtendedNasdaqAndDemandShockOilPriceAreNotScoredAsRiskOn() {
        var assessment = policy.evaluate(
                new MacroRegimeEvidence(
                        Map.of("WTI", 35d),
                        Map.of("NASDAQ_DISPARITY", 25d),
                        0, 2, 0),
                LocalDate.parse("2026-08-05"));

        assertEquals(-2, assessment.components().get("nasdaqDisparity"));
        assertEquals(-1, assessment.components().get("wti"));
    }

    @Test
    void sectorMomentumUsesCyclicalVersusDefensiveSpreadNotBroadMarketDirection() {
        var broadRally = sectors(5, 5);
        var cyclicalLeadership = sectors(6, 1);

        var broad = policy.evaluate(evidence(broadRally), LocalDate.parse("2026-08-05"));
        var leadership = policy.evaluate(evidence(cyclicalLeadership), LocalDate.parse("2026-08-05"));

        assertEquals(0, broad.components().get("sectorMomentum"));
        assertEquals(2, leadership.components().get("sectorMomentum"));
    }

    private static MacroRegimeEvidence evidence(Map<String, Double> derived) {
        return new MacroRegimeEvidence(Map.of(), derived, 0, 2, 0);
    }

    private static Map<String, Double> sectors(double cyclical, double defensive) {
        var values = new LinkedHashMap<String, Double>();
        for (var key : new String[]{"XLK", "XLI", "XLY", "XLC", "XLB", "XLF"}) {
            values.put("SECTOR_" + key, cyclical);
        }
        for (var key : new String[]{"XLV", "XLU", "XLP"}) {
            values.put("SECTOR_" + key, defensive);
        }
        return values;
    }
}
