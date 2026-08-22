package io.macrosquare.bootstrap.config;

import io.macrosquare.bootstrap.health.OptionalIntegrationInfoContributor;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NarrativeSourceProperties.class, DartProperties.class})
public class OptionalIntegrationInfoConfiguration {

    @Bean
    InfoContributor optionalIntegrationInfoContributor(
            NarrativeSourceProperties narrativeSources,
            DartProperties dart
    ) {
        return new OptionalIntegrationInfoContributor(narrativeSources, dart);
    }
}
