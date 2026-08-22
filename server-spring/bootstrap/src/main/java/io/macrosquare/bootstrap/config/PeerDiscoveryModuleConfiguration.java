package io.macrosquare.bootstrap.config;

import io.macrosquare.research.adapter.in.scheduling.PeerTaxonomyScheduler;
import io.macrosquare.research.adapter.out.catalog.ResearchCatalogPriorityPeerAdapter;
import io.macrosquare.research.adapter.out.persistence.InMemoryPeerTaxonomyRepository;
import io.macrosquare.research.adapter.out.persistence.JdbcPeerTaxonomyRepository;
import io.macrosquare.research.adapter.out.sec.SecPeerTaxonomyAdapter;
import io.macrosquare.research.adapter.out.sec.SecPeerUniverseAdapter;
import io.macrosquare.research.application.port.in.QueryDynamicPeersUseCase;
import io.macrosquare.research.application.port.in.RefreshPeerTaxonomyUseCase;
import io.macrosquare.research.application.port.out.CollectPeerTaxonomyPort;
import io.macrosquare.research.application.port.out.LoadPeerUniversePort;
import io.macrosquare.research.application.port.out.LoadPriorityPeerTickersPort;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.PeerTaxonomyRepository;
import io.macrosquare.research.application.service.QueryDynamicPeersService;
import io.macrosquare.research.application.service.RefreshPeerTaxonomyService;
import io.macrosquare.research.domain.peer.PeerDiscoveryPolicy;
import io.macrosquare.research.domain.peer.SicSectorPolicy;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PeerDiscoveryProperties.class)
public class PeerDiscoveryModuleConfiguration {

    @Bean("peerSecDataRestClient")
    RestClient peerSecDataRestClient(PeerDiscoveryProperties properties) {
        return restClient(properties, properties.secDataBaseUrl().toString());
    }

    @Bean("peerSecArchiveRestClient")
    RestClient peerSecArchiveRestClient(PeerDiscoveryProperties properties) {
        return restClient(properties, properties.secArchiveBaseUrl().toString());
    }

    @Bean
    SicSectorPolicy sicSectorPolicy() {
        return new SicSectorPolicy();
    }

    @Bean
    PeerDiscoveryPolicy peerDiscoveryPolicy() {
        return new PeerDiscoveryPolicy();
    }

    @Bean
    LoadPeerUniversePort loadPeerUniversePort(
            @Qualifier("peerSecArchiveRestClient") RestClient client,
            ObjectMapper objectMapper,
            Clock clock,
            PeerDiscoveryProperties properties
    ) {
        return new SecPeerUniverseAdapter(client, objectMapper, clock, properties.universeCacheTtl());
    }

    @Bean
    CollectPeerTaxonomyPort collectPeerTaxonomyPort(
            @Qualifier("peerSecDataRestClient") RestClient client,
            ObjectMapper objectMapper,
            SicSectorPolicy sectors,
            ObjectProvider<ObjectStorage> objectStorage,
            Clock clock,
            PeerDiscoveryProperties properties
    ) {
        return new SecPeerTaxonomyAdapter(
                client, objectMapper, sectors, objectStorage.getIfAvailable(), clock,
                properties.interRequestDelay(), properties.maximumSubmissionsBytes());
    }

    @Bean
    LoadPriorityPeerTickersPort loadPriorityPeerTickersPort(LoadResearchCatalogPort catalog) {
        return new ResearchCatalogPriorityPeerAdapter(catalog);
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    PeerTaxonomyRepository jdbcPeerTaxonomyRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        return new JdbcPeerTaxonomyRepository(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(PeerTaxonomyRepository.class)
    PeerTaxonomyRepository inMemoryPeerTaxonomyRepository() {
        return new InMemoryPeerTaxonomyRepository();
    }

    @Bean
    RefreshPeerTaxonomyUseCase refreshPeerTaxonomyUseCase(
            LoadPeerUniversePort universe,
            LoadPriorityPeerTickersPort priority,
            CollectPeerTaxonomyPort collector,
            PeerTaxonomyRepository repository,
            Clock clock,
            PeerDiscoveryProperties properties
    ) {
        return new RefreshPeerTaxonomyService(
                universe, priority, collector, repository, clock, properties.batchSize(),
                properties.taxonomyRefreshTtl(), properties.missingGrace());
    }

    @Bean
    QueryDynamicPeersUseCase queryDynamicPeersUseCase(
            PeerTaxonomyRepository repository,
            PeerDiscoveryPolicy policy,
            Clock clock
    ) {
        return new QueryDynamicPeersService(repository, policy, clock);
    }

    @Bean(name = "peerTaxonomyTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler peerTaxonomyTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("research-peer-taxonomy-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.peer-discovery", name = "enabled", havingValue = "true")
    PeerTaxonomyScheduler peerTaxonomyScheduler(
            RefreshPeerTaxonomyUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new PeerTaxonomyScheduler(refresh, exclusiveTasks);
    }

    private static RestClient restClient(PeerDiscoveryProperties properties, String baseUrl) {
        var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
