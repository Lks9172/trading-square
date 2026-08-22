package io.macrosquare.bootstrap.config;

import io.macrosquare.company.application.model.CompanyAnalystHistoryRead;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

@ConfigurationProperties(prefix = "macrosquare.company-analyst-history")
public record CompanyAnalystHistoryProperties(
        boolean enabled,
        CompanyAnalystHistoryRead.Mode readMode,
        Path directory,
        List<String> tickers,
        int retentionPoints,
        Duration startupDelay,
        String weekdayCron,
        String weekendCron,
        String zone
) {
    public CompanyAnalystHistoryProperties {
        if (readMode == null) throw new IllegalArgumentException("readMode must not be null");
        if (directory == null || !directory.isAbsolute()) {
            throw new IllegalArgumentException("directory must be an absolute path");
        }
        tickers = List.copyOf(tickers == null ? List.of() : tickers);
        if (tickers.isEmpty()) throw new IllegalArgumentException("tickers must not be empty");
        if (retentionPoints < 1 || retentionPoints > 3650) {
            throw new IllegalArgumentException("retentionPoints must be between 1 and 3650");
        }
        if (startupDelay == null || startupDelay.isNegative()) {
            throw new IllegalArgumentException("startupDelay must not be negative");
        }
        if (weekdayCron == null || weekdayCron.isBlank()) {
            throw new IllegalArgumentException("weekdayCron must not be blank");
        }
        if (weekendCron == null || weekendCron.isBlank()) {
            throw new IllegalArgumentException("weekendCron must not be blank");
        }
        validateCron(weekdayCron, "weekdayCron");
        validateCron(weekendCron, "weekendCron");
        if (zone == null || zone.isBlank()) throw new IllegalArgumentException("zone must not be blank");
        ZoneId.of(zone);
    }

    private static void validateCron(String value, String field) {
        if ("-".equals(value)) return;
        try {
            CronExpression.parse(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(field + " is invalid", error);
        }
    }
}
