package io.macrosquare.bootstrap.config;

import io.macrosquare.company.adapter.out.persistence.FileCompanyReadAdapter;
import io.macrosquare.company.adapter.out.persistence.ObjectCompanyReadAdapter;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;


@Configuration(proxyBeanMethods = false)
public class CompanyReadAdapterConfiguration {

    @Bean
    LoadCompanyReadPort loadCompanyReadPort(
            ObjectMapper objectMapper,
            CompanyReadProperties companyReadProperties,
            PersistenceProperties persistenceProperties,
            JsonEnvelopeStore projectionStore
    ) {
        if (persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO) {
            return new ObjectCompanyReadAdapter(projectionStore);
        }
        return new FileCompanyReadAdapter(
                objectMapper,
                companyReadProperties.sourceCacheDirectory(),
                companyReadProperties.maximumFileBytes(),
                companyReadProperties.maximumCachedFiles()
        );
    }
}
