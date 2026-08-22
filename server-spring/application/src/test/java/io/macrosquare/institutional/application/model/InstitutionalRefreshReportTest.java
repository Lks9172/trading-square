package io.macrosquare.institutional.application.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstitutionalRefreshReportTest {

    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void anEmptyOrPartialRefreshIsNeverReportedAsHealthy() {
        assertFalse(new InstitutionalRefreshReport(
                START, START.plusSeconds(1), 4, 0, 0, 0, List.of()).successful());
        assertFalse(new InstitutionalRefreshReport(
                START, START.plusSeconds(1), 4, 3, 20, 10, List.of("one-manager-failed")).successful());
        assertTrue(new InstitutionalRefreshReport(
                START, START.plusSeconds(1), 4, 3, 20, 10, List.of()).successful());
    }
}
