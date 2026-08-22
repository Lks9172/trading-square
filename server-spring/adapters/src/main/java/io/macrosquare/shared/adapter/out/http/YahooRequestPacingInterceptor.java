package io.macrosquare.shared.adapter.out.http;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.Objects;

/** Applies the shared Yahoo pacing policy to every configured Yahoo client. */
public final class YahooRequestPacingInterceptor implements ClientHttpRequestInterceptor {

    private final YahooRequestThrottle throttle;

    public YahooRequestPacingInterceptor(YahooRequestThrottle throttle) {
        this.throttle = Objects.requireNonNull(throttle);
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request,
            byte[] body,
            ClientHttpRequestExecution execution
    ) throws IOException {
        throttle.awaitPermit();
        var response = execution.execute(request, body);
        if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
            throttle.onRateLimited();
        }
        return response;
    }
}
