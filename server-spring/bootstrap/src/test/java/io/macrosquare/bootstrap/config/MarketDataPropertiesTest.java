package io.macrosquare.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketDataPropertiesTest {

    @Test
    void acceptsBoundedAbsolutePaths() {
        var properties = new MarketDataProperties(
                MarketDataProperties.ReadMode.FILE_PREFERRED,
                Path.of("/data/snapshot.json"),
                Path.of("/seed/snapshot.json"),
                Path.of("/data/history"),
                1024,
                2048,
                100,
                Duration.ofMinutes(5)
        );

        assertEquals(MarketDataProperties.ReadMode.FILE_PREFERRED, properties.readMode());
    }

    @Test
    void rejectsRelativeOrUnboundedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new MarketDataProperties(
                MarketDataProperties.ReadMode.FILE_ONLY,
                Path.of("snapshot.json"),
                Path.of("/seed/snapshot.json"),
                Path.of("/data/history"),
                1024,
                2048,
                100,
                Duration.ofMinutes(5)
        ));
        assertThrows(IllegalArgumentException.class, () -> new MarketDataProperties(
                MarketDataProperties.ReadMode.FILE_ONLY,
                Path.of("/data/snapshot.json"),
                Path.of("/seed/snapshot.json"),
                Path.of("/data/history"),
                0,
                2048,
                100,
                Duration.ofMinutes(5)
        ));
    }
}
