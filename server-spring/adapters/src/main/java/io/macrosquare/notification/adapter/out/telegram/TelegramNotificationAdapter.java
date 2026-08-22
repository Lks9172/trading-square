package io.macrosquare.notification.adapter.out.telegram;

import io.macrosquare.notification.application.model.NotificationDeliveryReceipt;
import io.macrosquare.notification.application.port.out.SendNotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class TelegramNotificationAdapter implements SendNotificationPort {

    private static final int MAX_CHARS = 3800;
    private static final Logger LOGGER = LoggerFactory.getLogger(TelegramNotificationAdapter.class);

    private final RestClient restClient;
    private final String token;
    private final String chatId;
    private final int attempts;
    private final Duration retryDelay;

    public TelegramNotificationAdapter(
            RestClient restClient,
            String token,
            String chatId,
            int attempts,
            Duration retryDelay
    ) {
        this.restClient = Objects.requireNonNull(restClient);
        this.token = requireSecret(token, "token");
        this.chatId = requireSecret(chatId, "chatId");
        if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("attempts must be between 1 and 10");
        this.attempts = attempts;
        this.retryDelay = Objects.requireNonNull(retryDelay);
    }

    @Override
    public NotificationDeliveryReceipt send(String idempotencyKey, String text) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || text == null || text.isBlank()) {
            return NotificationDeliveryReceipt.failed("invalid-message");
        }
        String lastProviderId = "";
        var chunkCount = 0;
        for (var offset = 0; offset < text.length(); offset += MAX_CHARS) {
            var chunk = text.substring(offset, Math.min(text.length(), offset + MAX_CHARS));
            var receipt = sendChunk(chunk);
            if (!receipt.delivered()) return receipt;
            lastProviderId = receipt.providerMessageId();
            chunkCount++;
        }
        return NotificationDeliveryReceipt.delivered(
                lastProviderId.isBlank() ? "" : lastProviderId + ":chunks=" + chunkCount);
    }

    private NotificationDeliveryReceipt sendChunk(String text) {
        for (var attempt = 1; attempt <= attempts; attempt++) {
            try {
                var response = restClient.post()
                        .uri("https://api.telegram.org/bot{token}/sendMessage", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("chat_id", chatId, "text", text))
                        .retrieve()
                        .body(Map.class);
                if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
                    throw new IllegalStateException("Telegram returned a non-success response");
                }
                var result = response.get("result");
                var messageId = result instanceof Map<?, ?> values && values.get("message_id") != null
                        ? String.valueOf(values.get("message_id")) : "";
                return NotificationDeliveryReceipt.delivered(messageId);
            } catch (RuntimeException error) {
                if (attempt == attempts) {
                    LOGGER.error("Telegram notification delivery failed after {} attempts (errorType={})",
                            attempts, error.getClass().getSimpleName());
                    return NotificationDeliveryReceipt.failed(error.getClass().getSimpleName());
                }
                LOGGER.warn("Telegram notification delivery attempt failed (attempt={}, errorType={})",
                        attempt, error.getClass().getSimpleName());
                try {
                    Thread.sleep(retryDelay.multipliedBy(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return NotificationDeliveryReceipt.failed("interrupted");
                }
            }
        }
        return NotificationDeliveryReceipt.failed("delivery-failed");
    }

    private static String requireSecret(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
