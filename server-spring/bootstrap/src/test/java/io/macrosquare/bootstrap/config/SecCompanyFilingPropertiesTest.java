package io.macrosquare.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecCompanyFilingPropertiesTest {

    @Test
    void acceptsBoundedReadOnlyArchiveConfiguration() {
        var properties = properties(3, Duration.ofMillis(150));

        assertEquals(3, properties.attachmentFilingLimit());
        assertEquals(Duration.ofMillis(150), properties.interRequestDelay());
        assertEquals(120, properties.maxPdfPages());
        assertEquals(32 * 1024 * 1024, properties.maxDocumentBytes());
        assertEquals(32 * 1024 * 1024, properties.maxInlineXbrlBytes());
    }

    @Test
    void rejectsInsecureOriginsAndUnboundedAttachmentScans() {
        assertThrows(IllegalArgumentException.class, () -> new SecCompanyFilingProperties(
                URI.create("http://www.sec.test"), "agent", Duration.ofSeconds(3), Duration.ofSeconds(20),
                Duration.ofHours(6), Duration.ofHours(24), Duration.ZERO,
                1000, 1000, 1000, 1000, 120, 10, 10, 1, 10, 3, 20
        ));
        assertThrows(IllegalArgumentException.class, () -> properties(11, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SecCompanyFilingProperties(
                URI.create("https://www.sec.test"), "agent", Duration.ofSeconds(3), Duration.ofSeconds(20),
                Duration.ofHours(6), Duration.ofHours(24), Duration.ZERO,
                1000, 1000, 1000, 1000, 201, 10, 10, 1, 10, 3, 20
        ));
        assertThrows(IllegalArgumentException.class, () -> new SecCompanyFilingProperties(
                URI.create("https://www.sec.test"), "agent", Duration.ofSeconds(3), Duration.ofSeconds(20),
                Duration.ofHours(6), Duration.ofHours(24), Duration.ZERO,
                1000, 1000, 32 * 1024 * 1024 + 1, 1000, 120, 10, 10, 1, 10, 3, 20
        ));
        assertThrows(IllegalArgumentException.class, () -> new SecCompanyFilingProperties(
                URI.create("https://www.sec.test"), "agent", Duration.ofSeconds(3), Duration.ofSeconds(20),
                Duration.ofHours(6), Duration.ofHours(24), Duration.ZERO,
                1000, 32 * 1024 * 1024 + 1, 1000, 1000, 120, 10, 10, 1, 10, 3, 20
        ));
    }

    private static SecCompanyFilingProperties properties(int attachmentLimit, Duration delay) {
        return new SecCompanyFilingProperties(
                URI.create("https://www.sec.test"),
                "MacroSquare research contact test@example.com",
                Duration.ofSeconds(3),
                Duration.ofSeconds(20),
                Duration.ofHours(6),
                Duration.ofHours(24),
                delay,
                2_097_152,
                33_554_432,
                33_554_432,
                30_000,
                120,
                128,
                512,
                1,
                10,
                attachmentLimit,
                20
        );
    }
}
