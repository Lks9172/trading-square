package io.macrosquare.bootstrap.config;

import io.macrosquare.disclosure.adapter.in.scheduling.DartDisclosureScheduler;
import io.macrosquare.disclosure.adapter.out.opendart.OpenDartAdapter;
import io.macrosquare.disclosure.adapter.out.persistence.InMemoryDartRepository;
import io.macrosquare.disclosure.adapter.out.persistence.JdbcDartRepository;
import io.macrosquare.disclosure.application.port.in.QueryDartCompanyUseCase;
import io.macrosquare.disclosure.application.port.in.RefreshDartUseCase;
import io.macrosquare.disclosure.application.port.out.DartRepository;
import io.macrosquare.disclosure.application.service.QueryDartCompanyService;
import io.macrosquare.disclosure.application.service.RefreshDartService;
import io.macrosquare.disclosure.domain.service.DartEventClassificationPolicy;
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
@EnableConfigurationProperties(DartProperties.class)
public class DartModuleConfiguration {

    @Bean("openDartRestClient")
    RestClient openDartRestClient(DartProperties properties) {
        var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(properties.baseUrl().toString()).requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    DartEventClassificationPolicy dartEventClassificationPolicy() {
        return new DartEventClassificationPolicy();
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.dart", name = "enabled", havingValue = "true")
    OpenDartAdapter openDartAdapter(
            @Qualifier("openDartRestClient") RestClient client,
            ObjectMapper objectMapper,
            DartEventClassificationPolicy classifier,
            ObjectProvider<ObjectStorage> objectStorage,
            DartProperties properties,
            Clock clock
    ) {
        return new OpenDartAdapter(
                client, objectMapper, classifier, objectStorage.getIfAvailable(), properties.apiKey(), clock,
                properties.interRequestDelay(), properties.maximumCompressedBytes(),
                properties.maximumUncompressedBytes());
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    DartRepository jdbcDartRepository(NamedParameterJdbcTemplate jdbc, TransactionOperations transactions) {
        return new JdbcDartRepository(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(DartRepository.class)
    DartRepository inMemoryDartRepository() {
        return new InMemoryDartRepository();
    }

    @Bean
    QueryDartCompanyUseCase queryDartCompanyUseCase(
            DartRepository repository,
            DartProperties properties
    ) {
        return new QueryDartCompanyService(
                repository,
                properties.enabled(),
                !properties.apiKey().isBlank());
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.dart", name = "enabled", havingValue = "true")
    RefreshDartUseCase refreshDartUseCase(
            OpenDartAdapter adapter,
            DartRepository repository,
            DartProperties properties,
            Clock clock
    ) {
        return new RefreshDartService(
                adapter, adapter, adapter, repository, properties.stockCodes(), clock,
                properties.lookbackDays(), properties.directoryRefreshTtl());
    }

    @Bean(name = "dartTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler dartTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("opendart-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.dart", name = "enabled", havingValue = "true")
    DartDisclosureScheduler dartDisclosureScheduler(
            RefreshDartUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new DartDisclosureScheduler(refresh, exclusiveTasks);
    }
}
