package io.macrosquare.shared.adapter.out.http;

import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Process-wide Yahoo request pacer and short circuit breaker.
 *
 * <p>Yahoo's public endpoints apply an IP-wide burst limit. Independent
 * company and market adapters therefore must not enforce concurrency in
 * isolation: all Yahoo GETs share this gate. A 429 opens a bounded cooldown;
 * requests during that cooldown fail fast so hundreds of queued company
 * refreshes cannot continuously extend the provider ban.</p>
 */
public final class YahooRequestThrottle {

    private final long minimumIntervalNanos;
    private final long rateLimitBackoffNanos;
    private final LongSupplier nanoTime;
    private final NanoSleeper sleeper;
    private final ReentrantLock fairLock = new ReentrantLock(true);
    private long nextPermitNanos;
    private long blockedUntilNanos;

    public YahooRequestThrottle(Duration minimumInterval, Duration rateLimitBackoff) {
        this(minimumInterval, rateLimitBackoff, System::nanoTime, LockSupport::parkNanos);
    }

    YahooRequestThrottle(
            Duration minimumInterval,
            Duration rateLimitBackoff,
            LongSupplier nanoTime,
            NanoSleeper sleeper
    ) {
        Objects.requireNonNull(minimumInterval, "minimumInterval");
        Objects.requireNonNull(rateLimitBackoff, "rateLimitBackoff");
        if (minimumInterval.isNegative() || minimumInterval.isZero()) {
            throw new IllegalArgumentException("minimumInterval must be positive");
        }
        if (rateLimitBackoff.isNegative() || rateLimitBackoff.isZero()) {
            throw new IllegalArgumentException("rateLimitBackoff must be positive");
        }
        this.minimumIntervalNanos = minimumInterval.toNanos();
        this.rateLimitBackoffNanos = rateLimitBackoff.toNanos();
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.sleeper = Objects.requireNonNull(sleeper);
    }

    public void awaitPermit() {
        fairLock.lock();
        try {
            while (true) {
                var now = nanoTime.getAsLong();
                if (now < blockedUntilNanos) {
                    throw new YahooRateLimitOpenException("Yahoo request cooldown is active");
                }
                var waitNanos = nextPermitNanos - now;
                if (waitNanos <= 0) {
                    nextPermitNanos = saturatedAdd(now, minimumIntervalNanos);
                    return;
                }
                sleeper.sleep(waitNanos);
                if (Thread.currentThread().isInterrupted()) {
                    throw new YahooRateLimitOpenException("Interrupted while pacing Yahoo request");
                }
            }
        } finally {
            fairLock.unlock();
        }
    }

    public void onRateLimited() {
        fairLock.lock();
        try {
            var candidate = saturatedAdd(nanoTime.getAsLong(), rateLimitBackoffNanos);
            blockedUntilNanos = Math.max(blockedUntilNanos, candidate);
        } finally {
            fairLock.unlock();
        }
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    @FunctionalInterface
    interface NanoSleeper {
        void sleep(long nanos);
    }

    public static final class YahooRateLimitOpenException extends RestClientException {
        public YahooRateLimitOpenException(String message) {
            super(message);
        }
    }
}
