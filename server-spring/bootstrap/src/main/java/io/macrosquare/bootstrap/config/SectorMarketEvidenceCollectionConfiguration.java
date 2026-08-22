package io.macrosquare.bootstrap.config;

import io.macrosquare.company.application.port.out.LoadCompanyPriceHistoryPort;
import io.macrosquare.research.adapter.in.scheduling.SectorMarketEvidenceScheduler;
import io.macrosquare.research.adapter.out.company.CompanyPriceHistoryResearchAdapter;
import io.macrosquare.research.adapter.out.official.StateStreetSectorFundHistoryAdapter;
import io.macrosquare.research.application.port.in.RefreshSectorMarketEvidenceUseCase;
import io.macrosquare.research.application.port.out.LoadOfficialSectorFundHistoryPort;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.LoadSectorConstituentPriceHistoryPort;
import io.macrosquare.research.application.port.out.SectorMarketEvidenceRepository;
import io.macrosquare.research.application.service.RefreshSectorMarketEvidenceService;
import io.macrosquare.research.domain.rotation.SectorFundFlowPolicy;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthPolicy;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SectorMarketEvidenceProperties.class)
@ConditionalOnProperty(prefix = "macrosquare.sector-market-evidence", name = "enabled", havingValue = "true")
public class SectorMarketEvidenceCollectionConfiguration {

    @Bean("stateStreetSectorFundRestClient")
    RestClient stateStreetSectorFundRestClient(SectorMarketEvidenceProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
                .defaultHeader(HttpHeaders.ACCEPT,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/octet-stream")
                .build();
    }

    @Bean
    LoadOfficialSectorFundHistoryPort loadOfficialSectorFundHistoryPort(
            @Qualifier("stateStreetSectorFundRestClient") RestClient restClient,
            SectorMarketEvidenceProperties properties
    ) {
        return new StateStreetSectorFundHistoryAdapter(
                restClient, properties.stateStreetBaseUrl(), properties.maximumWorkbookBytes());
    }

    @Bean
    LoadSectorConstituentPriceHistoryPort loadSectorConstituentPriceHistoryPort(
            LoadCompanyPriceHistoryPort companyPriceHistory
    ) {
        return new CompanyPriceHistoryResearchAdapter(companyPriceHistory);
    }

    @Bean
    RefreshSectorMarketEvidenceUseCase refreshSectorMarketEvidenceUseCase(
            LoadResearchCatalogPort catalog,
            LoadOfficialSectorFundHistoryPort fundHistory,
            LoadSectorConstituentPriceHistoryPort priceHistory,
            SectorMarketEvidenceRepository repository,
            SectorFundFlowPolicy fundFlowPolicy,
            SectorPriceBreadthPolicy priceBreadthPolicy,
            Clock clock
    ) {
        return new RefreshSectorMarketEvidenceService(
                catalog, fundHistory, priceHistory, repository, fundFlowPolicy, priceBreadthPolicy, clock);
    }

    @Bean("researchSectorEvidenceTaskScheduler")
    ThreadPoolTaskScheduler researchSectorEvidenceTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("sector-market-evidence-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Bean
    SectorMarketEvidenceScheduler sectorMarketEvidenceScheduler(
            RefreshSectorMarketEvidenceUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new SectorMarketEvidenceScheduler(refresh, exclusiveTasks);
    }
}
