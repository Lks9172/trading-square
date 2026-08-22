package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceProperties.class)
public class SystemConfiguration {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
