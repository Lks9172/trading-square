package io.macrosquare.bootstrap.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistencePropertiesTest {

    @Test
    void acceptsBoundedExclusiveTaskConcurrency() {
        var properties = new PersistenceProperties(
                PersistenceProperties.Mode.POSTGRES_MINIO, false, 4);

        assertEquals(4, properties.exclusiveTaskMaxConcurrency());
    }

    @Test
    void rejectsMissingModeAndUnsafeConcurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> new PersistenceProperties(null, false, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistenceProperties(PersistenceProperties.Mode.POSTGRES_MINIO, false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PersistenceProperties(PersistenceProperties.Mode.POSTGRES_MINIO, false, 33));
    }
}
