package io.macrosquare.bootstrap.config;

import io.macrosquare.compatibility.adapter.out.earnings.NasdaqEarningsSupplementalApiAdapter;
import io.macrosquare.compatibility.adapter.out.earnings.ResearchCatalogEarningsUniverseAdapter;
import io.macrosquare.compatibility.adapter.out.persistence.FileSupplementalApiAdapter;
import io.macrosquare.compatibility.application.port.in.QuerySupplementalApiUseCase;
import io.macrosquare.compatibility.application.port.out.LoadSupplementalApiPort;
import io.macrosquare.compatibility.application.port.out.LoadEarningsUniversePort;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.compatibility.application.service.QuerySupplementalApiService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EarningsCalendarProperties.class)
public class SupplementalApiModuleConfiguration {

    @Bean("fileSupplementalApiPort")
    LoadSupplementalApiPort fileSupplementalApiPort(
            JsonEnvelopeStore projectionStore,
            ObjectMapper objectMapper,
            CompanyResearchSummaryRepository companySummaries,
            Clock clock
    ) {
        return new FileSupplementalApiAdapter(projectionStore, objectMapper, companySummaries, clock);
    }

    @Bean(name = "earningsCalendarExecutor", destroyMethod = "shutdownNow")
    ExecutorService earningsCalendarExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofVirtual().name("earnings-calendar-", 0).factory());
    }

    @Bean("earningsCalendarRestClient")
    RestClient earningsCalendarRestClient(EarningsCalendarProperties properties) {
        var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE + ", text/plain, */*")
                .defaultHeader(HttpHeaders.REFERER, "https://www.nasdaq.com/")
                .build();
    }

    @Bean
    LoadEarningsUniversePort loadEarningsUniversePort(LoadResearchCatalogPort catalog) {
        return new ResearchCatalogEarningsUniverseAdapter(catalog);
    }

    @Bean
    LoadSupplementalApiPort loadSupplementalApiPort(
            @Qualifier("fileSupplementalApiPort") LoadSupplementalApiPort fallback,
            @Qualifier("earningsCalendarRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            Clock clock,
            EarningsCalendarProperties properties,
            LoadEarningsUniversePort universe,
            @Qualifier("earningsCalendarExecutor") ExecutorService executor
    ) {
        return new NasdaqEarningsSupplementalApiAdapter(
                fallback, universe, restClient, objectMapper, clock, properties.cacheTtl(), executor);
    }

    @Bean
    QuerySupplementalApiUseCase querySupplementalApiUseCase(
            @Qualifier("loadSupplementalApiPort") LoadSupplementalApiPort port
    ) {
        return new QuerySupplementalApiService(port);
    }
}
