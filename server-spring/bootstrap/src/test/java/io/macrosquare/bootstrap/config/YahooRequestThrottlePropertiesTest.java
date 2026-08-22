package io.macrosquare.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YahooRequestThrottlePropertiesTest {

    @Test
    void acceptsPositivePacingBounds() {
        var properties = new YahooRequestThrottleProperties(
                Duration.ofMillis(350), Duration.ofSeconds(30));

        assertEquals(Duration.ofMillis(350), properties.minimumInterval());
        assertEquals(Duration.ofSeconds(30), properties.rateLimitBackoff());
    }

    @Test
    void rejectsDisabledPacingOrCooldown() {
        assertThrows(IllegalArgumentException.class, () ->
                new YahooRequestThrottleProperties(Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class, () ->
                new YahooRequestThrottleProperties(Duration.ofMillis(350), Duration.ZERO));
    }
}
