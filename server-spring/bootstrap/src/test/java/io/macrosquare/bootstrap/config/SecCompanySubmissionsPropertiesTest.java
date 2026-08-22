package io.macrosquare.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecCompanySubmissionsPropertiesTest {

    @Test
    void acceptsBoundedReadOnlySecConfiguration() {
        var properties = properties(Duration.ofHours(4), Duration.ofHours(24), 200, 10, 2);

        assertEquals(URI.create("https://data.sec.gov"), properties.baseUrl());
        assertEquals(200, properties.recentFilingLimit());
        assertEquals(10, properties.parityFilingLimit());
        assertEquals(2, properties.maxConcurrentFetches());
    }

    @Test
    void rejectsInvalidTtlAndFilingBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(4), Duration.ofHours(3), 20, 10, 2));
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(4), Duration.ofHours(24), 20, 21, 2));
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(4), Duration.ofHours(24), 0, 1, 2));
    }

    @Test
    void rejectsAnUndeclaredClientIdentityAndUnboundedConcurrency() {
        assertThrows(IllegalArgumentException.class, () -> new SecCompanySubmissionsProperties(
                URI.create("https://data.sec.gov"), " ", Duration.ofSeconds(3), Duration.ofSeconds(20),
                Duration.ofHours(4), Duration.ofHours(24), 20, 10, 2
        ));
        assertThrows(IllegalArgumentException.class,
                () -> properties(Duration.ofHours(4), Duration.ofHours(24), 20, 10, 0));
    }

    private static SecCompanySubmissionsProperties properties(
            Duration cacheTtl,
            Duration staleTtl,
            int recentFilingLimit,
            int parityFilingLimit,
            int maxConcurrentFetches
    ) {
        return new SecCompanySubmissionsProperties(
                URI.create("https://data.sec.gov"),
                "MacroSquare research contact macrosquare@example.com",
                Duration.ofSeconds(3),
                Duration.ofSeconds(20),
                cacheTtl,
                staleTtl,
                recentFilingLimit,
                parityFilingLimit,
                maxConcurrentFetches
        );
    }
}
