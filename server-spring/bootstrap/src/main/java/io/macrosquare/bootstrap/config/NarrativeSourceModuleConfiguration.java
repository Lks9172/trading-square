package io.macrosquare.bootstrap.config;

import io.macrosquare.research.adapter.in.scheduling.NarrativeSourceScheduler;
import io.macrosquare.research.adapter.out.persistence.InMemoryNarrativeSourceRepository;
import io.macrosquare.research.adapter.out.persistence.JdbcNarrativeSourceRepository;
import io.macrosquare.research.adapter.out.publicdata.PublicNarrativeSourceAdapter;
import io.macrosquare.research.application.port.in.RefreshNarrativeSourcesUseCase;
import io.macrosquare.research.application.port.out.CollectNarrativeSourcesPort;
import io.macrosquare.research.application.port.out.NarrativeSourceRepository;
import io.macrosquare.research.application.service.NarrativeSourceCatalog;
import io.macrosquare.research.application.service.NarrativeThemeCatalog;
import io.macrosquare.research.application.service.RefreshNarrativeSourcesService;
import io.macrosquare.research.domain.narrative.NarrativeSourcePolicy;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NarrativeSourceProperties.class)
public class NarrativeSourceModuleConfiguration {

    @Bean
    NarrativeSourcePolicy narrativeSourcePolicy() {
        return new NarrativeSourcePolicy();
    }

    @Bean
    NarrativeSourceCatalog narrativeSourceCatalog() {
        return new NarrativeSourceCatalog();
    }

    @Bean("narrativeGoogleNewsRestClient")
    RestClient narrativeGoogleNewsRestClient(NarrativeSourceProperties properties) {
        return restClient(properties, properties.googleNewsBaseUrl().toString());
    }

    @Bean("narrativeWikimediaRestClient")
    RestClient narrativeWikimediaRestClient(NarrativeSourceProperties properties) {
        return restClient(properties, properties.wikimediaBaseUrl().toString());
    }

    @Bean("narrativeYoutubeRestClient")
    RestClient narrativeYoutubeRestClient(NarrativeSourceProperties properties) {
        return restClient(properties, properties.youtubeBaseUrl().toString());
    }

    @Bean
    CollectNarrativeSourcesPort collectNarrativeSourcesPort(
            @Qualifier("narrativeGoogleNewsRestClient") RestClient googleNews,
            @Qualifier("narrativeWikimediaRestClient") RestClient wikimedia,
            @Qualifier("narrativeYoutubeRestClient") RestClient youtube,
            ObjectMapper objectMapper,
            ObjectProvider<ObjectStorage> objectStorage,
            Clock clock,
            NarrativeSourceProperties properties
    ) {
        return new PublicNarrativeSourceAdapter(
                googleNews, wikimedia, youtube, objectMapper, objectStorage.getIfAvailable(), clock,
                properties.youtubeApiKey(), properties.interRequestDelay(), properties.maximumResponseBytes());
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    NarrativeSourceRepository jdbcNarrativeSourceRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        return new JdbcNarrativeSourceRepository(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(NarrativeSourceRepository.class)
    NarrativeSourceRepository inMemoryNarrativeSourceRepository() {
        return new InMemoryNarrativeSourceRepository();
    }

    @Bean
    RefreshNarrativeSourcesUseCase refreshNarrativeSourcesUseCase(
            NarrativeThemeCatalog themes,
            CollectNarrativeSourcesPort collector,
            NarrativeSourceRepository repository,
            Clock clock
    ) {
        return new RefreshNarrativeSourcesService(themes, collector, repository, clock);
    }

    @Bean(name = "narrativeSourceTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler narrativeSourceTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("research-narrative-source-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.narrative-sources", name = "enabled", havingValue = "true")
    NarrativeSourceScheduler narrativeSourceScheduler(
            RefreshNarrativeSourcesUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new NarrativeSourceScheduler(refresh, exclusiveTasks);
    }

    private static RestClient restClient(NarrativeSourceProperties properties, String baseUrl) {
        var httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .build();
    }
}
