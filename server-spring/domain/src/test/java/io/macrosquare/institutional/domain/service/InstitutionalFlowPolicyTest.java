package io.macrosquare.institutional.domain.service;

import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalFlowAction;
import io.macrosquare.institutional.domain.model.InstitutionalHolding;
import io.macrosquare.institutional.domain.model.InstitutionalManager;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstitutionalFlowPolicyTest {

    @Test
    void comparesTwoQuartersAndBuildsCrossManagerConsensus() {
        var first = new InstitutionalManager("first", "First Fund", "0000000001");
        var second = new InstitutionalManager("second", "Second Fund", "0000000002");
        var filings = List.of(
                filing(first, "0000000001-26-000002", "2026-03-31", List.of(
                        holding("APPLE", "Apple", 12_000_000),
                        holding("NEWONE", "New Position", 3_000_000)
                )),
                filing(first, "0000000001-25-000001", "2025-12-31", List.of(
                        holding("APPLE", "Apple", 8_000_000),
                        holding("EXITED", "Exited Position", 2_000_000)
                )),
                filing(second, "0000000002-26-000002", "2026-03-31", List.of(
                        holding("APPLE", "Apple", 5_000_000)
                )),
                filing(second, "0000000002-25-000001", "2025-12-31", List.of(
                        holding("APPLE", "Apple", 7_000_000)
                ))
        );

        var snapshot = new InstitutionalFlowPolicy().evaluate(filings);

        assertEquals(2, snapshot.managerCount());
        assertEquals(1, snapshot.sharedPositionCount());
        assertEquals("Apple", snapshot.consensus().getFirst().issuer());
        assertEquals(17_000_000, snapshot.consensus().getFirst().totalValueUsd());
        assertEquals(2_000_000, snapshot.consensus().getFirst().netValueDeltaUsd());
        assertEquals(2_000_000, snapshot.consensus().getFirst().estimatedNetFlowUsd(), 1e-6);
        var firstFlow = snapshot.managers().stream()
                .filter(value -> value.manager().id().equals("first"))
                .findFirst().orElseThrow();
        assertEquals(1, firstFlow.newPositions());
        assertEquals(1, firstFlow.increasedPositions());
        assertEquals(1, firstFlow.exitedPositions());
        assertTrue(firstFlow.topSells().stream().anyMatch(value -> value.action() == InstitutionalFlowAction.EXIT));
    }

    @Test
    void enrichesPointInTimeIdentityAndSeparatesAnalystOpinionFromReportedMoney() {
        var first = new InstitutionalManager("first", "First Fund", "0000000001");
        var second = new InstitutionalManager("second", "Second Fund", "0000000002");
        var filings = List.of(
                filing(first, "0000000001-26-000002", "2026-03-31", List.of(holding("APPLE", "Apple", 5_000_000))),
                filing(first, "0000000001-25-000001", "2025-12-31", List.of(holding("APPLE", "Apple", 10_000_000))),
                filing(second, "0000000002-26-000002", "2026-03-31", List.of(holding("APPLE", "Apple", 4_000_000))),
                filing(second, "0000000002-25-000001", "2025-12-31", List.of(holding("APPLE", "Apple", 8_000_000)))
        );
        var identity = new InstitutionalSecurityIdentity(
                "APPLE", "AAPL", "0000320193", "Apple", "technology",
                LocalDate.parse("2025-12-31"), null, 100, "TEST");

        var snapshot = new InstitutionalFlowPolicy().evaluate(
                filings, Map.of("APPLE", identity), Map.of("AAPL", 1.5));

        assertEquals(1, snapshot.mappedPositionCount());
        assertEquals(0, snapshot.unmappedPositionCount());
        assertEquals("AAPL", snapshot.consensus().getFirst().identity().ticker());
        assertEquals(1, snapshot.divergences().size());
        assertEquals("ANALYSTS_AHEAD_OF_MONEY", snapshot.divergences().getFirst().signal());
        assertTrue(snapshot.divergences().getFirst().institutionalFlowScore() < 0);
    }

    private static InstitutionalFiling filing(
            InstitutionalManager manager,
            String accession,
            String reportPeriod,
            List<InstitutionalHolding> holdings
    ) {
        var report = LocalDate.parse(reportPeriod);
        return new InstitutionalFiling(
                manager, accession, report.plusDays(40), report,
                "https://www.sec.gov/example.xml", "", holdings);
    }

    private static InstitutionalHolding holding(String cusip, String issuer, double value) {
        return new InstitutionalHolding(cusip, issuer, "COM", "", value, value / 100);
    }
}
