package io.macrosquare.bootstrap.config;

import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanyAnalystHistoryPropertiesTest {

    @Test
    void acceptsAnIsolatedAbsoluteStoreConfiguration() {
        var properties = properties(Path.of("/tmp/analyst-history"), "Asia/Seoul");
        assertEquals(CompanyAnalystHistoryRead.Mode.DUAL_COMPARE, properties.readMode());
        assertEquals(7, properties.tickers().size());
        assertEquals(365, properties.retentionPoints());
    }

    @Test
    void rejectsRelativePathsAndInvalidZones() {
        assertThrows(IllegalArgumentException.class, () -> properties(Path.of("relative"), "Asia/Seoul"));
        assertThrows(Exception.class, () -> properties(Path.of("/tmp/analyst-history"), "not-a-zone"));
    }

    @Test
    void rejectsANodeFiveFieldCronBeforeRuntimeSchedulingStarts() {
        assertThrows(IllegalArgumentException.class, () -> new CompanyAnalystHistoryProperties(
                true,
                CompanyAnalystHistoryRead.Mode.DUAL_COMPARE,
                Path.of("/tmp/analyst-history"),
                List.of("NVDA"),
                365,
                Duration.ofSeconds(30),
                "15 * * * 1-5",
                "0 15 */4 * * 0,6",
                "Asia/Seoul"
        ));
    }

    private static CompanyAnalystHistoryProperties properties(Path directory, String zone) {
        return new CompanyAnalystHistoryProperties(
                true,
                CompanyAnalystHistoryRead.Mode.DUAL_COMPARE,
                directory,
                List.of("AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "TSLA"),
                365,
                Duration.ofSeconds(30),
                "0 15 * * * 1-5",
                "0 15 */4 * * 0,6",
                zone
        );
    }
}
