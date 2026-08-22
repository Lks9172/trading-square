package io.macrosquare.market.adapter.out.policy;

import io.macrosquare.policy.domain.model.PolicyIntelligenceSnapshot;
import io.macrosquare.policy.domain.model.PolicyTone;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyIntelligenceMarketDirectionAdapterTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");

    @Test
    void mapsFreshDovishToneToTheExistingPositiveEasingConvention() {
        var adapter = new PolicyIntelligenceMarketDirectionAdapter(
                () -> snapshot(NOW.minusSeconds(86_400), 62, 75, 4),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var value = adapter.resolve().orElseThrow();

        assertEquals(2, value.direction());
        assertEquals(75, value.confidence());
    }

    @Test
    void rejectsStaleOrLowEvidenceInsteadOfOverwritingTheLastValidInput() {
        var stale = new PolicyIntelligenceMarketDirectionAdapter(
                () -> snapshot(NOW.minusSeconds(200L * 86_400), -80, 90, 6),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var weak = new PolicyIntelligenceMarketDirectionAdapter(
                () -> snapshot(NOW.minusSeconds(86_400), -80, 20, 1),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertTrue(stale.resolve().isEmpty());
        assertTrue(weak.resolve().isEmpty());
    }

    private static PolicyIntelligenceSnapshot snapshot(
            Instant asOf,
            int score,
            int confidence,
            int documents
    ) {
        return new PolicyIntelligenceSnapshot(
                asOf,
                score > 0 ? PolicyTone.DOVISH : PolicyTone.HAWKISH,
                score,
                confidence,
                documents,
                "test",
                List.of());
    }
}
