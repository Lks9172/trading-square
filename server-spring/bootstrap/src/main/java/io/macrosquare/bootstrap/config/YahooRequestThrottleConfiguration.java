package io.macrosquare.bootstrap.config;

import io.macrosquare.shared.adapter.out.http.YahooRequestPacingInterceptor;
import io.macrosquare.shared.adapter.out.http.YahooRequestThrottle;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(YahooRequestThrottleProperties.class)
public class YahooRequestThrottleConfiguration {

    @Bean
    YahooRequestThrottle yahooRequestThrottle(YahooRequestThrottleProperties properties) {
        return new YahooRequestThrottle(properties.minimumInterval(), properties.rateLimitBackoff());
    }

    @Bean
    YahooRequestPacingInterceptor yahooRequestPacingInterceptor(YahooRequestThrottle throttle) {
        return new YahooRequestPacingInterceptor(throttle);
    }
}
