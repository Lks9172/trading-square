package io.macrosquare.bootstrap.config;

import io.macrosquare.institutional.adapter.in.scheduling.InstitutionalFilingScheduler;
import io.macrosquare.institutional.adapter.out.persistence.InMemoryInstitutionalFilingRepository;
import io.macrosquare.institutional.adapter.out.persistence.InMemoryInstitutionalSecurityIdentityRepository;
import io.macrosquare.institutional.adapter.out.persistence.JdbcInstitutionalFilingRepository;
import io.macrosquare.institutional.adapter.out.persistence.JdbcInstitutionalSecurityIdentityRepository;
import io.macrosquare.institutional.adapter.out.company.CompanyAnalystScoreInstitutionalAdapter;
import io.macrosquare.institutional.adapter.out.sec.Sec13fInstitutionalFilingAdapter;
import io.macrosquare.institutional.adapter.out.sec.SecIssuerDirectoryIdentityResolverAdapter;
import io.macrosquare.institutional.application.port.in.QueryInstitutionalFlowsUseCase;
import io.macrosquare.institutional.application.port.in.RefreshInstitutionalFilingsUseCase;
import io.macrosquare.institutional.application.port.out.CollectInstitutionalFilingsPort;
import io.macrosquare.institutional.application.port.out.InstitutionalFilingRepository;
import io.macrosquare.institutional.application.port.out.InstitutionalSecurityIdentityRepository;
import io.macrosquare.institutional.application.port.out.LoadInstitutionalAnalystScorePort;
import io.macrosquare.institutional.application.port.out.ResolveInstitutionalSecurityIdentitiesPort;
import io.macrosquare.institutional.application.service.QueryInstitutionalFlowsService;
import io.macrosquare.institutional.application.service.RefreshInstitutionalFilingsService;
import io.macrosquare.institutional.domain.model.InstitutionalManager;
import io.macrosquare.institutional.domain.service.InstitutionalFlowPolicy;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
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
@EnableConfigurationProperties(InstitutionalCollectionProperties.class)
public class InstitutionalModuleConfiguration {

    @Bean("institutionalDataRestClient")
    RestClient institutionalDataRestClient(InstitutionalCollectionProperties properties) {
        return restClient(properties, properties.dataBaseUrl().toString());
    }

    @Bean("institutionalArchiveRestClient")
    RestClient institutionalArchiveRestClient(InstitutionalCollectionProperties properties) {
        return restClient(properties, properties.archiveBaseUrl().toString());
    }

    @Bean
    CollectInstitutionalFilingsPort collectInstitutionalFilingsPort(
            @Qualifier("institutionalDataRestClient") RestClient dataClient,
            @Qualifier("institutionalArchiveRestClient") RestClient archiveClient,
            ObjectMapper objectMapper,
            ObjectProvider<ObjectStorage> objectStorage,
            Clock clock,
            InstitutionalCollectionProperties properties
    ) {
        return new Sec13fInstitutionalFilingAdapter(
                dataClient, archiveClient, objectMapper, objectStorage.getIfAvailable(), clock,
                properties.interRequestDelay(), properties.maximumIndexBytes(),
                properties.maximumInformationTableBytes());
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    InstitutionalFilingRepository jdbcInstitutionalFilingRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        return new JdbcInstitutionalFilingRepository(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(InstitutionalFilingRepository.class)
    InstitutionalFilingRepository inMemoryInstitutionalFilingRepository() {
        return new InMemoryInstitutionalFilingRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    InstitutionalSecurityIdentityRepository jdbcInstitutionalSecurityIdentityRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        return new JdbcInstitutionalSecurityIdentityRepository(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(InstitutionalSecurityIdentityRepository.class)
    InstitutionalSecurityIdentityRepository inMemoryInstitutionalSecurityIdentityRepository() {
        return new InMemoryInstitutionalSecurityIdentityRepository();
    }

    @Bean
    ResolveInstitutionalSecurityIdentitiesPort resolveInstitutionalSecurityIdentitiesPort(
            @Qualifier("institutionalArchiveRestClient") RestClient secClient,
            ObjectMapper objectMapper,
            LoadResearchCatalogPort researchCatalog,
            Clock clock,
            InstitutionalCollectionProperties properties
    ) {
        return new SecIssuerDirectoryIdentityResolverAdapter(
                secClient, objectMapper, researchCatalog, clock, properties.identityDirectoryCacheTtl());
    }

    @Bean
    LoadInstitutionalAnalystScorePort loadInstitutionalAnalystScorePort(
            @Qualifier("companyAnalystConsensusFallback") LoadCompanyAnalystConsensusPort companyConsensus
    ) {
        // This GET projection can inspect up to sixty securities. It must use
        // the hourly persisted analyst snapshot rather than serially issuing
        // external Yahoo calls on the request thread.
        return new CompanyAnalystScoreInstitutionalAdapter(companyConsensus);
    }

    @Bean
    InstitutionalFlowPolicy institutionalFlowPolicy() {
        return new InstitutionalFlowPolicy();
    }

    @Bean
    RefreshInstitutionalFilingsUseCase refreshInstitutionalFilingsUseCase(
            CollectInstitutionalFilingsPort collector,
            InstitutionalFilingRepository repository,
            ResolveInstitutionalSecurityIdentitiesPort identityResolver,
            InstitutionalSecurityIdentityRepository identityRepository,
            InstitutionalCollectionProperties properties,
            Clock clock
    ) {
        var managers = properties.managers().stream()
                .map(value -> new InstitutionalManager(value.id(), value.name(), value.cik()))
                .toList();
        return new RefreshInstitutionalFilingsService(
                collector, repository, identityResolver, identityRepository,
                managers, properties.filingLimit(), clock);
    }

    @Bean
    QueryInstitutionalFlowsUseCase queryInstitutionalFlowsUseCase(
            InstitutionalFilingRepository repository,
            InstitutionalSecurityIdentityRepository identityRepository,
            LoadInstitutionalAnalystScorePort analystScores,
            InstitutionalFlowPolicy policy
    ) {
        return new QueryInstitutionalFlowsService(repository, identityRepository, analystScores, policy);
    }

    @Bean(name = "institutionalTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler institutionalTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("institutional-13f-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.institutional-collection", name = "enabled", havingValue = "true")
    InstitutionalFilingScheduler institutionalFilingScheduler(
            RefreshInstitutionalFilingsUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks,
            InstitutionalCollectionProperties properties,
            Clock clock
    ) {
        return new InstitutionalFilingScheduler(
                refresh, exclusiveTasks, clock, properties.startupFreshness());
    }

    private static RestClient restClient(
            InstitutionalCollectionProperties properties,
            String baseUrl
    ) {
        var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.APPLICATION_XML_VALUE)
                .build();
    }
}
