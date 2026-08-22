package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "macrosquare.notifications")
public record NotificationProperties(
        boolean enabled,
        String telegramBotToken,
        String telegramChatId,
        Path dataDirectory,
        Duration connectTimeout,
        Duration readTimeout,
        Duration startupDelay,
        Duration postStartupRecalculationDelay,
        int sendAttempts,
        Duration retryDelay,
        int scanConcurrency,
        int outboxBatchSize,
        Duration outboxLeaseDuration,
        Duration outboxRetryBaseDelay,
        int outboxMaximumAttempts,
        Duration outboxRetention
) {
    public NotificationProperties {
        if (dataDirectory == null || !dataDirectory.isAbsolute()) {
            throw new IllegalArgumentException("notification dataDirectory must be absolute");
        }
        dataDirectory = dataDirectory.normalize();
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                || startupDelay == null || startupDelay.isNegative()
                || postStartupRecalculationDelay == null || postStartupRecalculationDelay.isNegative()
                || postStartupRecalculationDelay.isZero()
                || retryDelay == null || retryDelay.isNegative()
                || outboxLeaseDuration == null || outboxLeaseDuration.isNegative() || outboxLeaseDuration.isZero()
                || outboxRetryBaseDelay == null || outboxRetryBaseDelay.isNegative()
                || outboxRetryBaseDelay.isZero()
                || outboxRetention == null || outboxRetention.isNegative() || outboxRetention.isZero()) {
            throw new IllegalArgumentException("notification durations are invalid");
        }
        if (sendAttempts < 1 || sendAttempts > 10) throw new IllegalArgumentException("sendAttempts is invalid");
        if (scanConcurrency < 1 || scanConcurrency > 32) throw new IllegalArgumentException("scanConcurrency is invalid");
        if (outboxBatchSize < 1 || outboxBatchSize > 100) {
            throw new IllegalArgumentException("outboxBatchSize is invalid");
        }
        if (outboxMaximumAttempts < 1 || outboxMaximumAttempts > 100) {
            throw new IllegalArgumentException("outboxMaximumAttempts is invalid");
        }
        if (outboxRetention.compareTo(Duration.ofDays(1)) < 0
                || outboxRetention.compareTo(Duration.ofDays(3650)) > 0) {
            throw new IllegalArgumentException("outboxRetention must be between 1 and 3650 days");
        }
    }
}
