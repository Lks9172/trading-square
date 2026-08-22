package io.macrosquare.shared.adapter.out.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YahooRequestThrottleTest {

    @Test
    void spacesRequestsAcrossAllCallers() {
        var now = new AtomicLong(1);
        var slept = new AtomicLong();
        var throttle = new YahooRequestThrottle(
                Duration.ofMillis(350), Duration.ofSeconds(30), now::get,
                nanos -> {
                    slept.addAndGet(nanos);
                    now.addAndGet(nanos);
                }
        );

        throttle.awaitPermit();
        throttle.awaitPermit();

        assertEquals(Duration.ofMillis(350).toNanos(), slept.get());
    }

    @Test
    void failsFastDuringProviderCooldownAndAllowsAProbeAfterwards() {
        var now = new AtomicLong(1);
        var throttle = new YahooRequestThrottle(
                Duration.ofMillis(350), Duration.ofSeconds(30), now::get, now::addAndGet
        );

        throttle.onRateLimited();
        assertThrows(YahooRequestThrottle.YahooRateLimitOpenException.class, throttle::awaitPermit);

        now.addAndGet(Duration.ofSeconds(30).toNanos());
        throttle.awaitPermit();
    }
}
