package io.macrosquare.bootstrap.config;

import io.macrosquare.crypto.adapter.out.persistence.FileCryptoResearchAdapter;
import io.macrosquare.crypto.adapter.out.market.MarketObservationCryptoSeriesAdapter;
import io.macrosquare.crypto.application.port.in.EnrichCryptoResearchUseCase;
import io.macrosquare.crypto.application.port.in.QueryCryptoResearchUseCase;
import io.macrosquare.crypto.application.port.out.LoadCryptoResearchPort;
import io.macrosquare.crypto.application.service.QueryCryptoResearchService;
import io.macrosquare.crypto.application.service.EnrichCryptoResearchService;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class CryptoModuleConfiguration {

    @Bean
    LoadCryptoResearchPort loadCryptoResearchPort(
            JsonEnvelopeStore projectionStore
    ) {
        return new FileCryptoResearchAdapter(projectionStore);
    }

    @Bean
    MarketObservationCryptoSeriesAdapter marketObservationCryptoSeriesAdapter(
            MarketObservationRepository repository
    ) {
        return new MarketObservationCryptoSeriesAdapter(repository);
    }

    @Bean
    EnrichCryptoResearchUseCase enrichCryptoResearchUseCase(
            MarketObservationCryptoSeriesAdapter marketSeries,
            Clock clock
    ) {
        return new EnrichCryptoResearchService(marketSeries, clock);
    }

    @Bean
    QueryCryptoResearchUseCase queryCryptoResearchUseCase(
            LoadCryptoResearchPort researchPort,
            EnrichCryptoResearchUseCase enrichment
    ) {
        return new QueryCryptoResearchService(researchPort, enrichment);
    }
}
