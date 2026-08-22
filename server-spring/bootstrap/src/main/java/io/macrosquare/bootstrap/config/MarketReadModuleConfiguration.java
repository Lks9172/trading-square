package io.macrosquare.bootstrap.config;

import io.macrosquare.market.adapter.out.persistence.FileMarketReadAdapter;
import io.macrosquare.market.adapter.out.persistence.FileMarketSnapshotProjectionAdapter;
import io.macrosquare.market.adapter.out.persistence.JdbcMarketReadAdapter;
import io.macrosquare.market.adapter.out.persistence.ObjectMarketSnapshotProjectionAdapter;
import io.macrosquare.market.adapter.out.persistence.SpringOwnedMarketReadAdapter;
import io.macrosquare.market.application.port.in.QueryMarketReadUseCase;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.market.application.port.out.SaveMarketSnapshotProjectionPort;
import io.macrosquare.shared.adapter.out.storage.WritableJsonEnvelopeStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.macrosquare.market.application.service.QueryMarketReadService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketDataProperties.class)
public class MarketReadModuleConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "legacy-file", matchIfMissing = true)
    FileMarketSnapshotProjectionAdapter fileMarketSnapshotProjectionAdapter(
            ObjectMapper objectMapper,
            Clock clock,
            MarketDataProperties properties
    ) {
        return new FileMarketSnapshotProjectionAdapter(
                objectMapper,
                clock,
                properties.snapshotFile(),
                properties.seedSnapshotFile(),
                properties.maximumSnapshotBytes()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    ObjectMarketSnapshotProjectionAdapter objectMarketSnapshotProjectionAdapter(
            WritableJsonEnvelopeStore store,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        return new ObjectMarketSnapshotProjectionAdapter(store, objectMapper, clock);
    }

    @Bean
    LoadMarketReadPort loadMarketReadPort(
            ObjectMapper objectMapper,
            Clock clock,
            MarketDataProperties marketDataProperties,
            PersistenceProperties persistenceProperties,
            LoadMarketSnapshotProjectionPort snapshotStore,
            org.springframework.beans.factory.ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider
    ) {
        if (persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO) {
            return new JdbcMarketReadAdapter(
                    jdbcProvider.getObject(), objectMapper, clock, snapshotStore);
        }
        var historyFile = new FileMarketReadAdapter(
                objectMapper,
                clock,
                marketDataProperties.snapshotFile(),
                marketDataProperties.historyDirectory(),
                marketDataProperties.maximumSnapshotBytes(),
                marketDataProperties.maximumHistoryFileBytes(),
                marketDataProperties.maximumHistoryFiles()
        );
        return marketDataProperties.readMode() == MarketDataProperties.ReadMode.FILE_ONLY
                ? historyFile
                : new SpringOwnedMarketReadAdapter(snapshotStore, historyFile);
    }

    @Bean
    QueryMarketReadUseCase queryMarketReadUseCase(LoadMarketReadPort marketReadPort) {
        return new QueryMarketReadService(marketReadPort);
    }
}
