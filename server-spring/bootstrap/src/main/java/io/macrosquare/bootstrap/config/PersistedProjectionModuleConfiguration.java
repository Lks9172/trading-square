package io.macrosquare.bootstrap.config;

import io.macrosquare.shared.adapter.out.persistence.ReadOnlyJsonEnvelopeStore;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistedProjectionProperties.class)
@ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "legacy-file", matchIfMissing = true)
public class PersistedProjectionModuleConfiguration {

    @Bean
    JsonEnvelopeStore readOnlyJsonEnvelopeStore(
            ObjectMapper objectMapper,
            PersistedProjectionProperties properties
    ) {
        return new ReadOnlyJsonEnvelopeStore(
                objectMapper,
                properties.directory(),
                properties.maximumFileBytes(),
                properties.maximumCachedFiles()
        );
    }
}
