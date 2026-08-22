package io.macrosquare.shared.adapter.out.observability;

import io.macrosquare.shared.application.port.out.OperationalEventSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** Converts intentional fallback behavior into bounded metrics and structured logs. */
public final class MicrometerOperationalEventAdapter implements OperationalEventSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(MicrometerOperationalEventAdapter.class);
    private static final int MAX_REFERENCE_CHARACTERS = 96;

    private final MeterRegistry registry;

    public MicrometerOperationalEventAdapter(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public void degraded(String component, String operation, String reference, Throwable cause) {
        var safeComponent = metricValue(component, "unknown");
        var safeOperation = metricValue(operation, "unknown");
        var errorType = cause == null ? "Unknown" : cause.getClass().getSimpleName();
        Counter.builder("macrosquare.degraded.operations")
                .description("Operations that intentionally returned a last-valid or partial value")
                .tag("component", safeComponent)
                .tag("operation", safeOperation)
                .tag("error", errorType)
                .register(registry)
                .increment();
        LOGGER.warn(
                "Operation degraded to a last-valid value (component={}, operation={}, reference={}, errorType={})",
                safeComponent,
                safeOperation,
                logReference(reference),
                errorType,
                cause
        );
    }

    private static String metricValue(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        var normalized = value.replaceAll("[^a-zA-Z0-9_.-]", "_");
        return normalized.substring(0, Math.min(48, normalized.length()));
    }

    private static String logReference(String value) {
        if (value == null || value.isBlank()) return "none";
        var normalized = value.replaceAll("[\\r\\n\\t]", "_");
        return normalized.substring(0, Math.min(MAX_REFERENCE_CHARACTERS, normalized.length()));
    }
}
