package io.macrosquare.compatibility.adapter.out.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupplementalApiJsonMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validatesTheProductionSectorBacktestContractRatherThanAnInventedYearsField() throws Exception {
        var productionShape = objectMapper.readTree("""
                {
                  "dateRange":{"from":"2021-01-01","to":"2026-01-01"},
                  "methodology":{"rebalance":"monthly"},
                  "summary":{"oneMonth":{"top1HitRate":50}},
                  "recentSamples":[]
                }
                """);
        var incomplete = objectMapper.readTree("{\"years\":5}");

        assertDoesNotThrow(() -> SupplementalApiJsonMapper.document(
                productionShape, SupplementalApiJsonMapper.Contract.SECTOR_BACKTEST));
        assertThrows(IllegalArgumentException.class, () -> SupplementalApiJsonMapper.document(
                incomplete, SupplementalApiJsonMapper.Contract.SECTOR_BACKTEST));
    }
}
