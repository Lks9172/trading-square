package io.macrosquare.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketCollectionPropertiesTest {

    @Test
    void validatesIsolatedCollectorConfiguration() {
        var properties = properties(Path.of("/app/spring-data/market-observations"), 2, 8);
        assertEquals(5000, properties.maximumHistoryPoints());
        assertThrows(IllegalArgumentException.class, () -> properties(Path.of("relative"), 2, 8));
        assertThrows(IllegalArgumentException.class,
                () -> properties(Path.of("/app/spring-data/market-observations"), 5, 8));
    }

    private static MarketCollectionProperties properties(Path path, int fredConcurrency, int yahooConcurrency) {
        return new MarketCollectionProperties(
                true, true, true, path, Path.of("/app/legacy-history"), 5000, 8_388_608, 16_777_216,
                URI.create("https://api.stlouisfed.org"), "key",
                List.of(URI.create("https://query1.finance.yahoo.com")),
                URI.create("https://production.dataviz.cnn.io/index/fearandgreed/graphdata"),
                URI.create("https://api.alternative.me/fng/?limit=1"),
                URI.create("https://cdn.cboe.com/api/global/delayed_quotes/"),
                URI.create("https://insights.aaii.com/feed"),
                URI.create("https://naaim.org/programs/naaim-exposure-index/"),
                URI.create("https://stablecoins.llama.fi/stablecoins"),
                URI.create("https://finance.naver.com/sise/investorDealTrendDay.nhn"),
                "MacroSquare/1.0",
                Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(45), Duration.ofHours(6), Duration.ofMinutes(15),
                Duration.ofHours(1), Duration.ofHours(6), Duration.ofHours(6),
                Duration.ofMinutes(30),
                Duration.ofSeconds(75), Duration.ofMinutes(5),
                fredConcurrency, yahooConcurrency, 4
        );
    }
}
