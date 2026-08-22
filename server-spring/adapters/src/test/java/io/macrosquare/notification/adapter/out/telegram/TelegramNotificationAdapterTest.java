package io.macrosquare.notification.adapter.out.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TelegramNotificationAdapterTest {

    @Test
    void returnsTheProviderMessageIdAfterTelegramAcceptsTheMessage() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"ok\":true,\"result\":{\"message_id\":42}}",
                        MediaType.APPLICATION_JSON));
        var adapter = new TelegramNotificationAdapter(
                builder.build(), "test-token", "test-chat", 1, Duration.ZERO);

        var receipt = adapter.send("a".repeat(64), "hello");

        assertTrue(receipt.delivered());
        assertEquals("42:chunks=1", receipt.providerMessageId());
        server.verify();
    }

    @Test
    void returnsAFailedReceiptSoTheOutboxCanRetry() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.telegram.org/bottest-token/sendMessage"))
                .andRespond(withServerError());
        var adapter = new TelegramNotificationAdapter(
                builder.build(), "test-token", "test-chat", 1, Duration.ZERO);

        var receipt = adapter.send("b".repeat(64), "hello");

        assertFalse(receipt.delivered());
        server.verify();
    }
}
