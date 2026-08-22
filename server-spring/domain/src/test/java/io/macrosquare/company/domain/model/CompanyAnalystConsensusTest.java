package io.macrosquare.company.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanyAnalystConsensusTest {

    @Test
    void acceptsFiniteCurrentConsensusOrUnavailableValues() {
        var available = new CompanyAnalystConsensus(1.098, 49.06);
        var unavailable = new CompanyAnalystConsensus(null, null);

        assertEquals(1.098, available.analystScore());
        assertEquals(49.06, available.upsidePct());
        assertNull(unavailable.analystScore());
        assertNull(unavailable.upsidePct());
    }

    @Test
    void rejectsScoresOutsideTheLegacyMinusTwoToTwoRange() {
        assertThrows(IllegalArgumentException.class, () -> new CompanyAnalystConsensus(2.001, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new CompanyAnalystConsensus(-2.001, 10.0));
    }

    @Test
    void rejectsNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> new CompanyAnalystConsensus(Double.NaN, 10.0));
        assertThrows(IllegalArgumentException.class, () -> new CompanyAnalystConsensus(1.0, Double.POSITIVE_INFINITY));
    }
}
