package io.macrosquare.company.application.port.in;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record CompanyAnalystHistoryRecordReport(
        Instant startedAt,
        Instant completedAt,
        LocalDate observationDate,
        int attempted,
        int written,
        int seededFromLegacy,
        List<Failure> failures
) {
    public CompanyAnalystHistoryRecordReport {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        Objects.requireNonNull(observationDate, "observationDate");
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (attempted < 0 || written < 0 || seededFromLegacy < 0) {
            throw new IllegalArgumentException("history record counts must not be negative");
        }
        if (written > attempted || seededFromLegacy > written) {
            throw new IllegalArgumentException("history record counts are inconsistent");
        }
        if (written + failures.size() != attempted) {
            throw new IllegalArgumentException("every attempted ticker must be written or failed");
        }
    }

    public boolean successful() {
        return failures.isEmpty() && written == attempted;
    }

    public record Failure(String ticker, String reason) {
        public Failure {
            if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
        }
    }
}
