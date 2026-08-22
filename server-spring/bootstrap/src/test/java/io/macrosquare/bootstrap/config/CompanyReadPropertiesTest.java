package io.macrosquare.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanyReadPropertiesTest {

    @Test
    void acceptsAnAbsoluteBoundedFileConfiguration() {
        var properties = new CompanyReadProperties(
                CompanyReadProperties.ReadMode.FILE_PREFERRED,
                Path.of("/app/legacy-source-cache"),
                8_388_608,
                1024,
                Duration.ofMinutes(15)
        );

        assertEquals(CompanyReadProperties.ReadMode.FILE_PREFERRED, properties.readMode());
    }

    @Test
    void rejectsRelativeOrUnboundedConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new CompanyReadProperties(
                CompanyReadProperties.ReadMode.FILE_ONLY, Path.of("relative"), 1, 1, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new CompanyReadProperties(
                CompanyReadProperties.ReadMode.FILE_ONLY, Path.of("/cache"), 0, 1, Duration.ofMinutes(1)));
    }
}
