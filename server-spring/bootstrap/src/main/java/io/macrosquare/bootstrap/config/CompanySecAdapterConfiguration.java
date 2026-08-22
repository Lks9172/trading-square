package io.macrosquare.bootstrap.config;

import io.macrosquare.company.adapter.out.sec.SecCompanyFactsAdapter;
import io.macrosquare.company.adapter.out.sec.SecCompanyFilingDetailAdapter;
import io.macrosquare.company.adapter.out.sec.SecCompanyIdentityAdapter;
import io.macrosquare.company.adapter.out.sec.SecCompanySubmissionsAdapter;
import io.macrosquare.company.application.port.out.LoadCompanyFundamentalsEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanySubmissionsEvidencePort;
import io.macrosquare.company.application.port.out.ResolveCompanyIdentityPort;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
public class CompanySecAdapterConfiguration {

    @Bean("secCompanyFactsRestClient")
    RestClient secCompanyFactsRestClient(SecCompanyFactsProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    LoadCompanyFundamentalsEvidencePort loadCompanyFundamentalsEvidencePort(
            @Qualifier("secCompanyFactsRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            SecCompanyFactsProperties properties,
            @Qualifier("companyFactsRefreshExecutor") Executor refreshExecutor
    ) {
        return new SecCompanyFactsAdapter(
                restClient,
                objectMapper,
                clock,
                properties.cacheTtl(),
                properties.staleTtl(),
                refreshExecutor,
                properties.maxConcurrentFetches()
        );
    }

    @Bean("secCompanyIdentityRestClient")
    RestClient secCompanyIdentityRestClient(SecCompanyIdentityProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    ResolveCompanyIdentityPort resolveCompanyIdentityPort(
            @Qualifier("secCompanyIdentityRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            SecCompanyIdentityProperties properties,
            @Qualifier("companyIdentityRefreshExecutor") Executor refreshExecutor
    ) {
        return new SecCompanyIdentityAdapter(
                restClient,
                objectMapper,
                clock,
                properties.cacheTtl(),
                properties.staleTtl(),
                refreshExecutor
        );
    }

    @Bean("secCompanySubmissionsRestClient")
    RestClient secCompanySubmissionsRestClient(SecCompanySubmissionsProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Bean
    LoadCompanySubmissionsEvidencePort loadCompanySubmissionsEvidencePort(
            @Qualifier("secCompanySubmissionsRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            SecCompanySubmissionsProperties properties,
            @Qualifier("companySubmissionsRefreshExecutor") Executor refreshExecutor
    ) {
        return new SecCompanySubmissionsAdapter(
                restClient,
                objectMapper,
                clock,
                properties.cacheTtl(),
                properties.staleTtl(),
                refreshExecutor,
                properties.recentFilingLimit(),
                properties.maxConcurrentFetches()
        );
    }

    @Bean("secCompanyFilingRestClient")
    RestClient secCompanyFilingRestClient(SecCompanyFilingProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT,
                        "text/html,application/xhtml+xml,text/plain,application/xml,application/pdf")
                .build();
    }

    @Bean
    SecCompanyFilingDetailAdapter secCompanyFilingDetailAdapter(
            @Qualifier("secCompanyFilingRestClient") RestClient restClient,
            Clock clock,
            SecCompanyFilingProperties properties,
            ObjectProvider<ObjectStorage> objectStorage
    ) {
        return new SecCompanyFilingDetailAdapter(
                restClient,
                properties.baseUrl(),
                clock,
                properties.cacheTtl(),
                properties.staleTtl(),
                properties.interRequestDelay(),
                properties.maxIndexBytes(),
                properties.maxDocumentBytes(),
                properties.maxInlineXbrlBytes(),
                properties.maxTextCharacters(),
                properties.maxPdfPages(),
                properties.maxDetailEntries(),
                properties.maxTextEntries(),
                properties.maxConcurrentFetches(),
                objectStorage.getIfAvailable()
        );
    }
}
