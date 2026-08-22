package io.macrosquare.market.domain.allocation;

import io.macrosquare.market.domain.regime.MacroRegime;
import io.macrosquare.market.domain.regime.MacroRegimeAssessment;
import io.macrosquare.market.domain.signal.CoreAssetSignal;
import io.macrosquare.market.domain.signal.CoreSignalAction;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreAllocationPolicyTest {

    @Test
    void cashSignalCannotEraseTheBondVigilanteDefensiveFloor() {
        var date = LocalDate.parse("2026-08-05");
        var regime = new MacroRegimeAssessment(MacroRegime.BOND_VIGILANTE, 65, Map.of(), date, List.of());
        var cashSell = new CoreAssetSignal(
                "CASH", CoreSignalAction.SELL, 1, 6, 6,
                10, 100, 100, List.of(), List.of(), List.of(), date, null);

        var allocation = new CoreAllocationPolicy().evaluate(
                regime, List.of(cashSell), Map.of(), Map.of(),
                "long", true, true, date);

        assertTrue(allocation.allocations().get("cash") >= 25);
    }
}
