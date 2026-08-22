package io.macrosquare.market.domain.observation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketObservationTest {

    @Test
    void acceptsFiniteProviderNeutralObservations() {
        var observation = new MarketObservation(
                "NASDAQ", "^IXIC", 22_791.11, LocalDate.parse("2026-07-20"), MarketDataSource.YAHOO);
        assertEquals("NASDAQ", observation.key());
    }

    @Test
    void rejectsInvalidDomainEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new MarketObservation(
                "NASDAQ", "^IXIC", Double.NaN, LocalDate.now(), MarketDataSource.YAHOO));
        assertThrows(IllegalArgumentException.class, () -> new MarketObservation(
                "", "^IXIC", 1, LocalDate.now(), MarketDataSource.YAHOO));
    }
}
