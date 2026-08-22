package io.macrosquare.bootstrap.config;

import io.macrosquare.company.adapter.out.persistence.FileCompanyAnalystHistoryStoreAdapter;
import io.macrosquare.company.adapter.out.persistence.ObjectCompanyAnalystConsensusAdapter;
import io.macrosquare.company.adapter.out.persistence.ObjectCompanyAnalystHistorySeedAdapter;
import io.macrosquare.company.adapter.out.research.ResearchCatalogCompanyAnalystUniverseAdapter;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.macrosquare.company.adapter.in.scheduling.CompanyAnalystHistoryScheduler;
import io.macrosquare.company.adapter.out.yahoo.YahooCompanyMarketQuoteAdapter;
import io.macrosquare.company.adapter.out.yahoo.YahooCompanyMarketCapitalizationAdapter;
import io.macrosquare.company.adapter.out.yahoo.YahooCompanyPriceHistoryAdapter;
import io.macrosquare.company.adapter.out.yahoo.YahooCompanyAnalystConsensusAdapter;
import io.macrosquare.company.adapter.out.yahoo.YahooFinanceAuthSessionProvider;
import io.macrosquare.company.application.port.in.RecordCompanyAnalystHistoryUseCase;
import io.macrosquare.company.application.port.in.ResolveCompanyAnalystHistoryUseCase;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistorySeedPort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystHistoryStorePort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystUniversePort;
import io.macrosquare.company.application.port.out.LoadCompanyMarketQuotePort;
import io.macrosquare.company.application.port.out.LoadCompanyMarketCapitalizationPort;
import io.macrosquare.company.application.port.out.LoadCompanyPriceHistoryPort;
import io.macrosquare.company.application.port.out.SaveCompanyAnalystHistoryPort;
import io.macrosquare.company.application.service.RecordCompanyAnalystHistoryService;
import io.macrosquare.company.application.service.ResolveCompanyAnalystHistoryService;
import io.macrosquare.company.domain.service.CompanyAnalystHistoryPolicy;
import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.shared.adapter.out.http.YahooRequestPacingInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
public class CompanyYahooAdapterConfiguration {

    @Bean("yahooCompanyQuoteRestClient")
    RestClient yahooCompanyQuoteRestClient(
            YahooCompanyQuoteProperties properties,
            YahooRequestPacingInterceptor pacingInterceptor
    ) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(pacingInterceptor)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    LoadCompanyMarketQuotePort loadCompanyMarketQuotePort(
            @Qualifier("yahooCompanyQuoteRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            YahooCompanyQuoteProperties properties,
            @Qualifier("companyQuoteRefreshExecutor") Executor refreshExecutor
    ) {
        return new YahooCompanyMarketQuoteAdapter(
                restClient,
                objectMapper,
                properties.baseUrls(),
                clock,
                properties.cacheTtl(),
                properties.staleTtl(),
                refreshExecutor,
                properties.maxConcurrentFetches()
        );
    }

    @Bean
    LoadCompanyMarketCapitalizationPort loadCompanyMarketCapitalizationPort(
            @Qualifier("yahooCompanyQuoteRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            YahooCompanyQuoteProperties properties
    ) {
        return new YahooCompanyMarketCapitalizationAdapter(
                restClient,
                objectMapper,
                properties.baseUrls(),
                clock,
                properties.cacheTtl(),
                properties.staleTtl(),
                properties.maxConcurrentFetches()
        );
    }

    @Bean("yahooCompanyPriceHistoryRestClient")
    RestClient yahooCompanyPriceHistoryRestClient(
            YahooCompanyPriceHistoryProperties properties,
            YahooRequestPacingInterceptor pacingInterceptor
    ) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(pacingInterceptor)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    LoadCompanyPriceHistoryPort loadCompanyPriceHistoryPort(
            @Qualifier("yahooCompanyPriceHistoryRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            YahooCompanyPriceHistoryProperties properties,
            @Qualifier("companyPriceHistoryRefreshExecutor") Executor refreshExecutor
    ) {
        return new YahooCompanyPriceHistoryAdapter(
                restClient,
                objectMapper,
                properties.baseUrls(),
                clock,
                properties.lookbackDays(),
                properties.cacheTtl(),
                properties.staleTtl(),
                refreshExecutor,
                properties.maxConcurrentFetches()
        );
    }

    @Bean("yahooCompanyAnalystRestClient")
    RestClient yahooCompanyAnalystRestClient(
            CompanyAnalystProperties properties,
            YahooRequestPacingInterceptor pacingInterceptor
    ) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .requestInterceptor(pacingInterceptor)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    YahooFinanceAuthSessionProvider yahooFinanceAuthSessionProvider(
            @Qualifier("yahooCompanyAnalystRestClient") RestClient restClient,
            Clock clock,
            CompanyAnalystProperties properties
    ) {
        return new YahooFinanceAuthSessionProvider(
                restClient,
                properties.cookieUrl(),
                properties.crumbUrl(),
                clock,
                properties.authCacheTtl()
        );
    }

    @Bean("companyAnalystConsensusFallback")
    LoadCompanyAnalystConsensusPort companyAnalystConsensusFallback(
            ObjectMapper objectMapper,
            Clock clock,
            CompanyAnalystProperties properties,
            PersistenceProperties persistenceProperties,
            ObjectProvider<ObjectStorage> objectStorage
    ) {
        if (persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO) {
            return new ObjectCompanyAnalystConsensusAdapter(
                    objectStorage.getObject(), objectMapper, clock, properties.consensusStaleTtl());
        }
        return ticker -> new CompanyAnalystConsensus(null, null);
    }

    @Bean("directCompanyAnalystConsensusPort")
    LoadCompanyAnalystConsensusPort loadCompanyAnalystConsensusPort(
            @Qualifier("yahooCompanyAnalystRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            YahooFinanceAuthSessionProvider authSessionProvider,
            @Qualifier("companyAnalystConsensusFallback") LoadCompanyAnalystConsensusPort persistedFallback,
            Clock clock,
            CompanyAnalystProperties properties
    ) {
        return new YahooCompanyAnalystConsensusAdapter(
                restClient,
                objectMapper,
                authSessionProvider,
                persistedFallback,
                properties.quoteSummaryBaseUrl(),
                clock,
                properties.consensusCacheTtl(),
                properties.consensusStaleTtl(),
                properties.interTickerDelay(),
                properties.minimumSuccessfulTickers()
        );
    }

    @Bean
    LoadCompanyAnalystHistorySeedPort loadCompanyAnalystHistorySeedPort(
            ObjectMapper objectMapper,
            CompanyAnalystProperties properties,
            PersistenceProperties persistenceProperties,
            ObjectProvider<ObjectStorage> objectStorage
    ) {
        if (persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO) {
            return new ObjectCompanyAnalystHistorySeedAdapter(objectStorage.getObject(), objectMapper);
        }
        return ticker -> List.of();
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "file", matchIfMissing = true)
    FileCompanyAnalystHistoryStoreAdapter companyAnalystHistoryStoreAdapter(
            ObjectMapper objectMapper,
            CompanyAnalystHistoryProperties historyProperties
    ) {
        var store = historyProperties.directory().toAbsolutePath().normalize();
        return new FileCompanyAnalystHistoryStoreAdapter(objectMapper, store);
    }

    @Bean
    LoadCompanyAnalystUniversePort loadCompanyAnalystUniversePort(
            LoadResearchCatalogPort researchCatalog
    ) {
        return new ResearchCatalogCompanyAnalystUniverseAdapter(researchCatalog);
    }

    @Bean
    RecordCompanyAnalystHistoryUseCase recordCompanyAnalystHistoryUseCase(
            @Qualifier("directCompanyAnalystConsensusPort") LoadCompanyAnalystConsensusPort consensusPort,
            LoadCompanyAnalystHistorySeedPort seedHistoryPort,
            LoadCompanyAnalystHistoryStorePort loadHistoryStore,
            SaveCompanyAnalystHistoryPort saveHistoryStore,
            LoadCompanyAnalystUniversePort analystUniverse,
            CompanyAnalystHistoryPolicy historyPolicy,
            Clock clock,
            CompanyAnalystHistoryProperties properties
    ) {
        return new RecordCompanyAnalystHistoryService(
                consensusPort,
                seedHistoryPort,
                loadHistoryStore,
                saveHistoryStore,
                analystUniverse,
                historyPolicy,
                clock,
                properties.tickers(),
                properties.retentionPoints()
        );
    }

    @Bean
    ResolveCompanyAnalystHistoryUseCase resolveCompanyAnalystHistoryUseCase(
            LoadCompanyAnalystHistorySeedPort seedHistoryPort,
            LoadCompanyAnalystHistoryStorePort storeHistoryAdapter,
            CompanyAnalystHistoryProperties properties
    ) {
        return new ResolveCompanyAnalystHistoryService(
                seedHistoryPort,
                storeHistoryAdapter,
                properties.readMode(),
                properties.tickers()
        );
    }

    @Bean(name = "companyAnalystHistoryTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnProperty(
            prefix = "macrosquare.company-analyst-history",
            name = "enabled",
            havingValue = "true"
    )
    ThreadPoolTaskScheduler companyAnalystHistoryTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("analyst-history-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "macrosquare.company-analyst-history",
            name = "enabled",
            havingValue = "true"
    )
    CompanyAnalystHistoryScheduler companyAnalystHistoryScheduler(
            RecordCompanyAnalystHistoryUseCase useCase,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new CompanyAnalystHistoryScheduler(useCase, exclusiveTasks);
    }
}
