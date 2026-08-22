package io.macrosquare.bootstrap.config;

import io.macrosquare.market.adapter.in.scheduling.MarketObservationCollectionScheduler;
import io.macrosquare.market.adapter.in.scheduling.MarketHistorySeedRunner;
import io.macrosquare.market.adapter.in.scheduling.MarketSnapshotRefreshScheduler;
import io.macrosquare.market.adapter.in.scheduling.SectorTotalReturnHistoryScheduler;
import io.macrosquare.market.adapter.out.fred.FredMarketObservationAdapter;
import io.macrosquare.market.adapter.out.execution.ExecutionPlanBridgeAdapter;
import io.macrosquare.market.adapter.out.krx.NaverKrxInvestorFlowAdapter;
import io.macrosquare.market.adapter.out.persistence.ObjectMarketHistorySeedAdapter;
import io.macrosquare.market.adapter.out.persistence.FileMarketSnapshotProjectionAdapter;
import io.macrosquare.market.adapter.out.persistence.FileMarketObservationRepository;
import io.macrosquare.market.adapter.out.persistence.InMemoryMarketCollectionStatusRepository;
import io.macrosquare.market.adapter.out.sentiment.FearGreedMarketObservationAdapter;
import io.macrosquare.market.adapter.out.research.ResearchTopdownBridgeAdapter;
import io.macrosquare.market.adapter.out.sentiment.SentimentMarketObservationAdapter;
import io.macrosquare.market.adapter.out.stablecoin.StablecoinMarketObservationAdapter;
import io.macrosquare.market.adapter.out.yahoo.YahooMarketObservationAdapter;
import io.macrosquare.market.adapter.out.yahoo.YahooSectorTotalReturnHistoryAdapter;
import io.macrosquare.market.application.port.in.InspectMarketObservationsUseCase;
import io.macrosquare.market.application.port.in.RefreshMarketObservationsUseCase;
import io.macrosquare.market.application.port.in.SeedMarketHistoryUseCase;
import io.macrosquare.market.application.port.in.RefreshMarketSnapshotUseCase;
import io.macrosquare.market.application.port.in.RefreshSectorTotalReturnHistoryUseCase;
import io.macrosquare.market.application.port.in.PersonalizeMarketSnapshotUseCase;
import io.macrosquare.market.application.port.in.QueryMarketCorrelationUseCase;
import io.macrosquare.market.application.port.out.CollectMarketObservationsPort;
import io.macrosquare.market.application.port.out.BuildCurrentExecutionPlansPort;
import io.macrosquare.market.application.port.out.EvaluateCurrentTopdownPort;
import io.macrosquare.market.application.port.out.LoadMarketHistorySeedPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.application.port.out.MarketCollectionStatusRepository;
import io.macrosquare.market.application.port.out.ResolveAutomaticPolicyDirectionPort;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.market.application.port.out.SaveMarketSnapshotProjectionPort;
import io.macrosquare.market.application.service.InspectMarketObservationsService;
import io.macrosquare.market.application.service.RefreshMarketObservationsService;
import io.macrosquare.market.application.service.SeedMarketHistoryService;
import io.macrosquare.market.application.service.RefreshMarketSnapshotService;
import io.macrosquare.market.application.service.RefreshSectorTotalReturnHistoryService;
import io.macrosquare.market.application.service.PersonalizeMarketSnapshotService;
import io.macrosquare.market.application.service.QueryMarketCorrelationService;
import io.macrosquare.market.domain.allocation.CoreAllocationPolicy;
import io.macrosquare.market.domain.indicator.CoreDerivedIndicatorPolicy;
import io.macrosquare.technical.domain.MacdSignalPolicy;
import io.macrosquare.market.domain.correlation.MarketCorrelationPolicy;
import io.macrosquare.market.domain.regime.MacroRegimePolicy;
import io.macrosquare.market.domain.signal.CoreAssetSignalPolicy;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketInputFreshnessPolicy;
import io.macrosquare.execution.domain.service.CurrentExecutionPlanPolicy;
import io.macrosquare.research.application.port.in.EvaluateCurrentSectorRotationUseCase;
import io.macrosquare.research.application.port.in.CaptureSectorRotationSnapshotUseCase;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.macrosquare.shared.adapter.out.http.YahooRequestPacingInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MarketCollectionProperties.class)
public class MarketCollectionModuleConfiguration {

    @Bean(name = "fredMarketCollectorExecutor", destroyMethod = "shutdownNow")
    ExecutorService fredMarketCollectorExecutor(MarketCollectionProperties properties) {
        return Executors.newFixedThreadPool(
                properties.fredConcurrency(), Thread.ofVirtual().name("fred-collector-", 0).factory());
    }

    @Bean(name = "yahooMarketCollectorExecutor", destroyMethod = "shutdownNow")
    ExecutorService yahooMarketCollectorExecutor(MarketCollectionProperties properties) {
        return Executors.newFixedThreadPool(
                properties.yahooConcurrency(), Thread.ofVirtual().name("yahoo-market-collector-", 0).factory());
    }

    @Bean(name = "supplementalMarketCollectorExecutor", destroyMethod = "shutdownNow")
    ExecutorService supplementalMarketCollectorExecutor(MarketCollectionProperties properties) {
        return Executors.newFixedThreadPool(
                properties.supplementalConcurrency(), Thread.ofVirtual().name("supplemental-market-collector-", 0).factory());
    }

    @Bean("fredMarketRestClient")
    RestClient fredMarketRestClient(MarketCollectionProperties properties) {
        return restClient(properties, properties.fredReadTimeout(), properties.fredBaseUrl().toString());
    }

    @Bean("yahooMarketRestClient")
    RestClient yahooMarketRestClient(
            MarketCollectionProperties properties,
            YahooRequestPacingInterceptor pacingInterceptor
    ) {
        return restClient(properties, properties.yahooReadTimeout(), null, pacingInterceptor);
    }

    @Bean("supplementalMarketRestClient")
    RestClient supplementalMarketRestClient(MarketCollectionProperties properties) {
        return restClient(properties, properties.supplementalReadTimeout(), null);
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "file", matchIfMissing = true)
    FileMarketObservationRepository marketObservationRepository(
            ObjectMapper objectMapper,
            Clock clock,
            MarketCollectionProperties properties
    ) {
        return new FileMarketObservationRepository(
                objectMapper,
                clock,
                properties.directory(),
                properties.maximumHistoryPoints(),
                properties.maximumFileBytes()
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "macrosquare.persistence",
            name = "mode",
            havingValue = "file",
            matchIfMissing = true
    )
    InMemoryMarketCollectionStatusRepository inMemoryMarketCollectionStatusRepository() {
        return new InMemoryMarketCollectionStatusRepository();
    }

    @Bean
    FredMarketObservationAdapter fredMarketObservationAdapter(
            @Qualifier("fredMarketRestClient") RestClient restClient,
            Clock clock,
            MarketCollectionProperties properties,
            @Qualifier("fredMarketCollectorExecutor") ExecutorService executor
    ) {
        return new FredMarketObservationAdapter(restClient, clock, properties.fredApiKey(), executor);
    }

    @Bean
    YahooMarketObservationAdapter yahooMarketObservationAdapter(
            @Qualifier("yahooMarketRestClient") RestClient restClient,
            Clock clock,
            MarketCollectionProperties properties,
            @Qualifier("yahooMarketCollectorExecutor") ExecutorService executor
    ) {
        return new YahooMarketObservationAdapter(restClient, properties.yahooBaseUrls(), clock, executor);
    }

    @Bean
    YahooSectorTotalReturnHistoryAdapter yahooSectorTotalReturnHistoryAdapter(
            @Qualifier("yahooMarketRestClient") RestClient restClient,
            Clock clock,
            MarketCollectionProperties properties,
            @Qualifier("yahooMarketCollectorExecutor") ExecutorService executor
    ) {
        return new YahooSectorTotalReturnHistoryAdapter(
                restClient, properties.yahooBaseUrls(), clock, executor);
    }

    @Bean
    FearGreedMarketObservationAdapter fearGreedMarketObservationAdapter(
            @Qualifier("supplementalMarketRestClient") RestClient restClient,
            Clock clock,
            MarketCollectionProperties properties
    ) {
        return new FearGreedMarketObservationAdapter(
                restClient, properties.cnnFearGreedUrl(), properties.alternativeFearGreedUrl(), clock);
    }

    @Bean
    SentimentMarketObservationAdapter sentimentMarketObservationAdapter(
            @Qualifier("supplementalMarketRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            MarketCollectionProperties properties,
            @Qualifier("supplementalMarketCollectorExecutor") ExecutorService executor
    ) {
        return new SentimentMarketObservationAdapter(
                restClient,
                objectMapper,
                properties.cboeDelayedQuotesBaseUrl(),
                properties.aaiiFeedUrl(),
                properties.naaimExposureUrl(),
                clock,
                executor
        );
    }

    @Bean
    StablecoinMarketObservationAdapter stablecoinMarketObservationAdapter(
            @Qualifier("supplementalMarketRestClient") RestClient restClient,
            Clock clock,
            MarketCollectionProperties properties
    ) {
        return new StablecoinMarketObservationAdapter(restClient, properties.stablecoinUrl(), clock);
    }

    @Bean
    NaverKrxInvestorFlowAdapter naverKrxInvestorFlowAdapter(
            @Qualifier("supplementalMarketRestClient") RestClient restClient,
            Clock clock,
            MarketCollectionProperties properties
    ) {
        return new NaverKrxInvestorFlowAdapter(restClient, properties.krxInvestorFlowUrl(), clock);
    }

    @Bean
    RefreshMarketObservationsUseCase refreshMarketObservationsUseCase(
            FredMarketObservationAdapter fred,
            YahooMarketObservationAdapter yahoo,
            FearGreedMarketObservationAdapter fearGreed,
            SentimentMarketObservationAdapter sentiment,
            StablecoinMarketObservationAdapter stablecoin,
            NaverKrxInvestorFlowAdapter krx,
            MarketObservationRepository repository
    ) {
        return new RefreshMarketObservationsService(
                List.<CollectMarketObservationsPort>of(fred, yahoo, fearGreed, sentiment, stablecoin, krx), repository);
    }

    @Bean
    InspectMarketObservationsUseCase inspectMarketObservationsUseCase(MarketObservationRepository repository) {
        return new InspectMarketObservationsService(repository);
    }

    @Bean
    RefreshSectorTotalReturnHistoryUseCase refreshSectorTotalReturnHistoryUseCase(
            YahooSectorTotalReturnHistoryAdapter collector,
            MarketObservationRepository repository,
            Clock clock
    ) {
        return new RefreshSectorTotalReturnHistoryService(collector, repository, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.market-collection", name = "history-seed-enabled", havingValue = "true")
    LoadMarketHistorySeedPort loadMarketHistorySeedPort(
            ObjectMapper objectMapper,
            MarketCollectionProperties properties,
            PersistenceProperties persistenceProperties,
            ObjectProvider<ObjectStorage> objectStorage
    ) {
        var providerCodes = Map.of(
                MarketDataSource.FRED, FredMarketObservationAdapter.SERIES,
                MarketDataSource.YAHOO, YahooMarketObservationAdapter.SYMBOLS
        );
        if (persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO) {
            return new ObjectMarketHistorySeedAdapter(
                    objectStorage.getObject(), objectMapper, properties.maximumSeedFileBytes(),
                    properties.maximumHistoryPoints(), providerCodes);
        }
        throw new IllegalStateException("Market history seed requires postgres-minio persistence");
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.market-collection", name = "history-seed-enabled", havingValue = "true")
    SeedMarketHistoryUseCase seedMarketHistoryUseCase(
            LoadMarketHistorySeedPort seedPort,
            MarketObservationRepository repository,
            Clock clock
    ) {
        return new SeedMarketHistoryService(seedPort, repository, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.market-collection", name = "history-seed-enabled", havingValue = "true")
    MarketHistorySeedRunner marketHistorySeedRunner(SeedMarketHistoryUseCase seed) {
        return new MarketHistorySeedRunner(seed);
    }

    @Bean
    CoreDerivedIndicatorPolicy coreDerivedIndicatorPolicy(MacdSignalPolicy macdSignalPolicy) {
        return new CoreDerivedIndicatorPolicy(macdSignalPolicy);
    }

    @Bean
    MarketInputFreshnessPolicy marketInputFreshnessPolicy() {
        return new MarketInputFreshnessPolicy();
    }

    @Bean
    MacroRegimePolicy macroRegimePolicy() {
        return new MacroRegimePolicy();
    }

    @Bean
    CoreAssetSignalPolicy coreAssetSignalPolicy() {
        return new CoreAssetSignalPolicy();
    }

    @Bean
    CoreAllocationPolicy coreAllocationPolicy() {
        return new CoreAllocationPolicy();
    }

    @Bean
    CurrentExecutionPlanPolicy currentExecutionPlanPolicy() {
        return new CurrentExecutionPlanPolicy();
    }

    @Bean
    BuildCurrentExecutionPlansPort buildCurrentExecutionPlansPort(CurrentExecutionPlanPolicy policy) {
        return new ExecutionPlanBridgeAdapter(policy);
    }

    @Bean
    EvaluateCurrentTopdownPort evaluateCurrentTopdownPort(
            EvaluateCurrentSectorRotationUseCase currentSectorRotation,
            CaptureSectorRotationSnapshotUseCase captureSectorRotationSnapshot
    ) {
        return new ResearchTopdownBridgeAdapter(currentSectorRotation, captureSectorRotationSnapshot);
    }

    @Bean
    MarketCorrelationPolicy marketCorrelationPolicy() {
        return new MarketCorrelationPolicy();
    }

    @Bean
    QueryMarketCorrelationUseCase queryMarketCorrelationUseCase(
            MarketObservationRepository repository,
            MarketCorrelationPolicy policy,
            Clock clock
    ) {
        return new QueryMarketCorrelationService(repository, policy, clock);
    }

    @Bean
    RefreshMarketSnapshotUseCase refreshMarketSnapshotUseCase(
            LoadMarketSnapshotProjectionPort snapshotReader,
            SaveMarketSnapshotProjectionPort snapshotWriter,
            MarketObservationRepository repository,
            CoreDerivedIndicatorPolicy derivedPolicy,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            ResolveAutomaticPolicyDirectionPort automaticPolicyDirection,
            MarketInputFreshnessPolicy freshnessPolicy,
            EvaluateCurrentTopdownPort currentTopdown,
            BuildCurrentExecutionPlansPort currentExecutionPlans,
            MarketCollectionStatusRepository collectionStatuses,
            Clock clock,
            MarketDataProperties marketDataProperties,
            MarketCollectionProperties properties
    ) {
        return new RefreshMarketSnapshotService(
                snapshotReader, snapshotWriter, repository, derivedPolicy, regimePolicy,
                signalPolicy, allocationPolicy, clock, marketDataProperties.cacheTtl(),
                automaticPolicyDirection, freshnessPolicy, currentTopdown, currentExecutionPlans,
                collectionStatuses, Map.of(
                        MarketDataSource.FRED, maximumSilence(properties.fredFixedDelay()),
                        MarketDataSource.YAHOO, maximumSilence(properties.yahooFixedDelay()),
                        MarketDataSource.FEAR_GREED, maximumSilence(properties.fearGreedFixedDelay()),
                        MarketDataSource.SENTIMENT, maximumSilence(properties.sentimentFixedDelay()),
                        MarketDataSource.STABLECOIN, maximumSilence(properties.stablecoinFixedDelay()),
                        MarketDataSource.KRX, maximumSilence(properties.krxFixedDelay())
                ));
    }

    @Bean
    PersonalizeMarketSnapshotUseCase personalizeMarketSnapshotUseCase(
            LoadMarketSnapshotProjectionPort snapshotStore,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            MarketInputFreshnessPolicy freshnessPolicy,
            BuildCurrentExecutionPlansPort currentExecutionPlans,
            Clock clock
    ) {
        return new PersonalizeMarketSnapshotService(
                snapshotStore, regimePolicy, signalPolicy, allocationPolicy, clock, freshnessPolicy,
                currentExecutionPlans);
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.market-collection", name = "snapshot-refresh-enabled", havingValue = "true")
    MarketSnapshotRefreshScheduler marketSnapshotRefreshScheduler(
            RefreshMarketSnapshotUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new MarketSnapshotRefreshScheduler(refresh, exclusiveTasks);
    }

    @Bean(name = "marketObservationTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler marketObservationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("market-observation-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.market-collection", name = "enabled", havingValue = "true")
    MarketObservationCollectionScheduler marketObservationCollectionScheduler(
            RefreshMarketObservationsUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks,
            MarketCollectionStatusRepository collectionStatuses,
            Clock clock
    ) {
        return new MarketObservationCollectionScheduler(refresh, exclusiveTasks, collectionStatuses, clock);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "macrosquare.market-collection",
            name = {"enabled", "sector-total-return-enabled"},
            havingValue = "true"
    )
    SectorTotalReturnHistoryScheduler sectorTotalReturnHistoryScheduler(
            RefreshSectorTotalReturnHistoryUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new SectorTotalReturnHistoryScheduler(refresh, exclusiveTasks);
    }

    private static RestClient restClient(
            MarketCollectionProperties properties,
            java.time.Duration readTimeout,
            String baseUrl
    ) {
        return restClient(properties, readTimeout, baseUrl, null);
    }

    private static RestClient restClient(
            MarketCollectionProperties properties,
            java.time.Duration readTimeout,
            String baseUrl,
            org.springframework.http.client.ClientHttpRequestInterceptor interceptor
    ) {
        var httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        var builder = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (interceptor != null) builder.requestInterceptor(interceptor);
        if (baseUrl != null) builder.baseUrl(baseUrl);
        return builder.build();
    }

    private static java.time.Duration maximumSilence(java.time.Duration fixedDelay) {
        return fixedDelay.multipliedBy(2).plusMinutes(5);
    }
}
