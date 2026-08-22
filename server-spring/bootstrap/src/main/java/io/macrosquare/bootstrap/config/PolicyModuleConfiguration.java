package io.macrosquare.bootstrap.config;

import io.macrosquare.policy.adapter.in.scheduling.PolicyIntelligenceScheduler;
import io.macrosquare.market.adapter.out.policy.PolicyIntelligenceMarketDirectionAdapter;
import io.macrosquare.market.application.port.out.ResolveAutomaticPolicyDirectionPort;
import io.macrosquare.policy.adapter.out.fed.FedMonetaryPolicyAdapter;
import io.macrosquare.policy.adapter.out.official.CompositePolicyDocumentAdapter;
import io.macrosquare.policy.adapter.out.official.OfficialAgencyPolicyAdapter;
import io.macrosquare.policy.adapter.out.persistence.InMemoryPolicyCalibrationRepository;
import io.macrosquare.policy.adapter.out.persistence.JdbcPolicyCalibrationRepository;
import io.macrosquare.policy.adapter.out.persistence.InMemoryPolicyAnalysisRepository;
import io.macrosquare.policy.adapter.out.persistence.JdbcPolicyAnalysisRepository;
import io.macrosquare.policy.application.port.in.QueryPolicyIntelligenceUseCase;
import io.macrosquare.policy.application.port.in.RefreshPolicyIntelligenceUseCase;
import io.macrosquare.policy.application.port.out.CollectPolicyDocumentsPort;
import io.macrosquare.policy.application.port.out.PolicyAnalysisRepository;
import io.macrosquare.policy.application.port.out.PolicyCalibrationRepository;
import io.macrosquare.policy.application.service.QueryPolicyIntelligenceService;
import io.macrosquare.policy.application.service.RefreshPolicyIntelligenceService;
import io.macrosquare.policy.domain.service.PolicyToneAnalysisPolicy;
import io.macrosquare.policy.domain.service.PolicyConfidenceCalibrationPolicy;
import io.macrosquare.policy.domain.model.PolicyDocumentType;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PolicyCollectionProperties.class)
public class PolicyModuleConfiguration {

    @Bean("policyFedRestClient")
    RestClient policyFedRestClient(PolicyCollectionProperties properties) {
        var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_XML_VALUE + ", " + MediaType.TEXT_HTML_VALUE)
                .build();
    }

    @Bean("fedPolicyDocumentCollector")
    CollectPolicyDocumentsPort fedPolicyDocumentCollector(
            @Qualifier("policyFedRestClient") RestClient policyFedRestClient,
            ObjectProvider<ObjectStorage> objectStorage,
            Clock clock,
            PolicyCollectionProperties properties
    ) {
        return new FedMonetaryPolicyAdapter(
                policyFedRestClient,
                properties.feedUrl(),
                properties.calendarUrl(),
                properties.historicalStatementLimit(),
                objectStorage.getIfAvailable(),
                clock,
                properties.interRequestDelay(),
                properties.maximumFeedBytes(),
                properties.maximumDocumentBytes());
    }

    @Bean("treasuryPolicyDocumentCollector")
    CollectPolicyDocumentsPort treasuryPolicyDocumentCollector(
            @Qualifier("policyFedRestClient") RestClient client,
            ObjectProvider<ObjectStorage> objectStorage,
            Clock clock,
            PolicyCollectionProperties properties
    ) {
        return new OfficialAgencyPolicyAdapter(
                client, properties.treasuryListingUrl(), "home.treasury.gov", "U.S. Treasury",
                "treasury", PolicyDocumentType.TREASURY_RELEASE, "/news/press-releases/",
                java.util.Set.of(), properties.agencyDocumentLimit(), objectStorage.getIfAvailable(),
                clock, properties.interRequestDelay(), properties.maximumFeedBytes(),
                properties.maximumDocumentBytes());
    }

    @Bean("ustrPolicyDocumentCollector")
    CollectPolicyDocumentsPort ustrPolicyDocumentCollector(
            @Qualifier("policyFedRestClient") RestClient client,
            ObjectProvider<ObjectStorage> objectStorage,
            Clock clock,
            PolicyCollectionProperties properties
    ) {
        return new OfficialAgencyPolicyAdapter(
                client, properties.ustrListingUrl(), "ustr.gov", "U.S. Trade Representative",
                "ustr", PolicyDocumentType.TARIFF_ACTION, "/press-releases/",
                java.util.Set.of("tariff", "section 301", "section 232", "trade action", "duties"),
                properties.agencyDocumentLimit(), objectStorage.getIfAvailable(), clock,
                properties.interRequestDelay(), properties.maximumFeedBytes(),
                properties.maximumDocumentBytes());
    }

    @Bean
    @Primary
    CollectPolicyDocumentsPort collectPolicyDocumentsPort(
            @Qualifier("fedPolicyDocumentCollector") CollectPolicyDocumentsPort fed,
            @Qualifier("treasuryPolicyDocumentCollector") CollectPolicyDocumentsPort treasury,
            @Qualifier("ustrPolicyDocumentCollector") CollectPolicyDocumentsPort ustr
    ) {
        return new CompositePolicyDocumentAdapter(java.util.List.of(fed, treasury, ustr));
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    PolicyAnalysisRepository jdbcPolicyAnalysisRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        return new JdbcPolicyAnalysisRepository(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(PolicyAnalysisRepository.class)
    PolicyAnalysisRepository inMemoryPolicyAnalysisRepository() {
        return new InMemoryPolicyAnalysisRepository();
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    PolicyCalibrationRepository jdbcPolicyCalibrationRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        return new JdbcPolicyCalibrationRepository(jdbc, transactions);
    }

    @Bean
    @ConditionalOnMissingBean(PolicyCalibrationRepository.class)
    PolicyCalibrationRepository inMemoryPolicyCalibrationRepository() {
        return new InMemoryPolicyCalibrationRepository();
    }

    @Bean
    PolicyToneAnalysisPolicy policyToneAnalysisPolicy() {
        return new PolicyToneAnalysisPolicy();
    }

    @Bean
    PolicyConfidenceCalibrationPolicy policyConfidenceCalibrationPolicy() {
        return new PolicyConfidenceCalibrationPolicy();
    }

    @Bean
    RefreshPolicyIntelligenceUseCase refreshPolicyIntelligenceUseCase(
            CollectPolicyDocumentsPort collector,
            PolicyAnalysisRepository repository,
            PolicyCalibrationRepository calibrationRepository,
            PolicyConfidenceCalibrationPolicy calibrationPolicy,
            PolicyToneAnalysisPolicy policy,
            PolicyCollectionProperties properties,
            Clock clock
    ) {
        return new RefreshPolicyIntelligenceService(
                collector, repository, calibrationRepository, calibrationPolicy,
                policy, properties.maximumDocuments(), clock);
    }

    @Bean
    QueryPolicyIntelligenceUseCase queryPolicyIntelligenceUseCase(
            PolicyAnalysisRepository repository,
            PolicyCalibrationRepository calibrationRepository,
            PolicyConfidenceCalibrationPolicy calibrationPolicy,
            PolicyToneAnalysisPolicy policy,
            PolicyCollectionProperties properties,
            Clock clock
    ) {
        return new QueryPolicyIntelligenceService(
                repository, calibrationRepository, calibrationPolicy,
                policy, clock, properties.queryDocumentLimit());
    }

    @Bean
    ResolveAutomaticPolicyDirectionPort resolveAutomaticPolicyDirectionPort(
            QueryPolicyIntelligenceUseCase query,
            Clock clock
    ) {
        return new PolicyIntelligenceMarketDirectionAdapter(query, clock);
    }

    @Bean(name = "policyTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler policyTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("policy-fed-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.policy-collection", name = "enabled", havingValue = "true")
    PolicyIntelligenceScheduler policyIntelligenceScheduler(
            RefreshPolicyIntelligenceUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new PolicyIntelligenceScheduler(refresh, exclusiveTasks);
    }
}
