package io.macrosquare.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistedProjectionPropertiesTest {

    @Test
    void validatesIndependentCutoverModesAndBounds() {
        var properties = new PersistedProjectionProperties(
                PersistedProjectionProperties.ReadMode.FILE_PREFERRED,
                PersistedProjectionProperties.ReadMode.FILE_ONLY,
                Path.of("/app/legacy-source-cache"),
                16_777_216,
                256
        );

        assertEquals(PersistedProjectionProperties.ReadMode.FILE_ONLY, properties.cryptoReadMode());
        assertThrows(IllegalArgumentException.class, () -> new PersistedProjectionProperties(
                PersistedProjectionProperties.ReadMode.FILE_ONLY,
                PersistedProjectionProperties.ReadMode.FILE_ONLY,
                Path.of("relative"), 1, 1));
    }
}
