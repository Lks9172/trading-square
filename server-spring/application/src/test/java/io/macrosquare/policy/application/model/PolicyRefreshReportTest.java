package io.macrosquare.policy.application.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRefreshReportTest {

    private static final Instant START = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void requiresEveryCollectedDocumentToBePersistedOrFailed() {
        assertThrows(IllegalArgumentException.class, () ->
                new PolicyRefreshReport(START, START.plusSeconds(1), 2, 1, List.of()));
    }

    @Test
    void anEmptyProviderResponseIsNotAHealthyRefresh() {
        assertFalse(new PolicyRefreshReport(
                START, START.plusSeconds(1), 0, 0, List.of()).successful());
        assertTrue(new PolicyRefreshReport(
                START, START.plusSeconds(1), 2, 2, List.of()).successful());
    }
}
