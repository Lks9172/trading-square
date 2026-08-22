package io.macrosquare.bootstrap.config;

import io.macrosquare.research.domain.rotation.SectorRotationPolicy;
import io.macrosquare.research.domain.rotation.SectorWalkForwardBacktestPolicy;
import io.macrosquare.research.domain.rotation.SectorEarningsRevisionBreadthPolicy;
import io.macrosquare.research.domain.rotation.SectorFundFlowPolicy;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthPolicy;
import io.macrosquare.market.application.port.out.LoadMarketReadPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.research.adapter.out.market.MarketReadResearchSnapshotAdapter;
import io.macrosquare.research.adapter.out.market.MarketSectorTotalReturnHistoryAdapter;
import io.macrosquare.research.adapter.out.market.MarketSectorRotationPriceWindowAdapter;
import io.macrosquare.research.adapter.out.persistence.FileResearchCatalogAdapter;
import io.macrosquare.research.adapter.out.persistence.JdbcSectorEarningsRevisionBreadthAdapter;
import io.macrosquare.research.adapter.out.persistence.JdbcSectorMarketEvidenceRepository;
import io.macrosquare.research.adapter.out.persistence.JdbcSectorRotationValidationRepository;
import io.macrosquare.research.adapter.out.company.CompanyResearchSummaryMetricsAdapter;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.research.application.port.in.EvaluateResearchParityUseCase;
import io.macrosquare.research.application.port.in.QueryNarrativesUseCase;
import io.macrosquare.research.application.port.in.QueryResearchCatalogUseCase;
import io.macrosquare.research.application.port.in.RunSectorRotationWalkForwardBacktestUseCase;
import io.macrosquare.research.application.port.in.CaptureSectorRotationSnapshotUseCase;
import io.macrosquare.research.application.port.in.EvaluateSectorRotationOutcomesUseCase;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.LoadResearchSnapshotPort;
import io.macrosquare.research.application.port.out.LoadCurrentCompanyMetricsPort;
import io.macrosquare.research.application.port.out.LoadSectorTotalReturnHistoryPort;
import io.macrosquare.research.application.port.out.LoadSectorEarningsRevisionBreadthPort;
import io.macrosquare.research.application.port.out.NarrativeSourceRepository;
import io.macrosquare.research.application.port.out.SectorMarketEvidenceRepository;
import io.macrosquare.research.application.port.out.SectorRotationValidationRepository;
import io.macrosquare.research.application.port.out.LoadSectorRotationPriceWindowPort;
import io.macrosquare.research.application.service.EvaluateResearchParityService;
import io.macrosquare.research.application.service.EvaluateCurrentSectorRotationService;
import io.macrosquare.research.application.port.in.EvaluateCurrentSectorRotationUseCase;
import io.macrosquare.research.application.service.NarrativeThemeCatalog;
import io.macrosquare.research.application.service.NarrativeSourceCatalog;
import io.macrosquare.research.application.service.QueryNarrativesService;
import io.macrosquare.research.application.service.QueryResearchCatalogService;
import io.macrosquare.research.application.service.RunSectorRotationWalkForwardBacktestService;
import io.macrosquare.research.application.service.CaptureSectorRotationSnapshotService;
import io.macrosquare.research.application.service.EvaluateSectorRotationOutcomesService;
import io.macrosquare.research.domain.narrative.NarrativeHeatPolicy;
import io.macrosquare.research.domain.narrative.NarrativeSourcePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class ResearchModuleConfiguration {

    @Bean
    NarrativeHeatPolicy narrativeHeatPolicy() {
        return new NarrativeHeatPolicy();
    }

    @Bean
    SectorRotationPolicy sectorRotationPolicy() {
        return new SectorRotationPolicy();
    }

    @Bean
    SectorWalkForwardBacktestPolicy sectorWalkForwardBacktestPolicy() {
        return new SectorWalkForwardBacktestPolicy();
    }

    @Bean
    SectorEarningsRevisionBreadthPolicy sectorEarningsRevisionBreadthPolicy() {
        return new SectorEarningsRevisionBreadthPolicy();
    }

    @Bean
    SectorFundFlowPolicy sectorFundFlowPolicy() {
        return new SectorFundFlowPolicy();
    }

    @Bean
    SectorPriceBreadthPolicy sectorPriceBreadthPolicy() {
        return new SectorPriceBreadthPolicy();
    }

    @Bean
    LoadSectorEarningsRevisionBreadthPort loadSectorEarningsRevisionBreadthPort(
            PersistenceProperties persistenceProperties,
            ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider
    ) {
        if (persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO) {
            return new JdbcSectorEarningsRevisionBreadthAdapter(jdbcProvider.getObject());
        }
        return LoadSectorEarningsRevisionBreadthPort.unavailable();
    }

    @Bean
    SectorMarketEvidenceRepository sectorMarketEvidenceRepository(
            PersistenceProperties persistenceProperties,
            ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider
    ) {
        if (persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO) {
            return new JdbcSectorMarketEvidenceRepository(jdbcProvider.getObject());
        }
        return SectorMarketEvidenceRepository.unavailable();
    }

    @Bean
    SectorRotationValidationRepository sectorRotationValidationRepository(
            PersistenceProperties persistenceProperties,
            ObjectProvider<NamedParameterJdbcTemplate> jdbcProvider,
            ObjectProvider<TransactionOperations> transactionsProvider
    ) {
        if (persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO) {
            return new JdbcSectorRotationValidationRepository(
                    jdbcProvider.getObject(), transactionsProvider.getObject());
        }
        return SectorRotationValidationRepository.unavailable();
    }

    @Bean
    LoadSectorRotationPriceWindowPort loadSectorRotationPriceWindowPort(
            PersistenceProperties persistenceProperties,
            MarketObservationRepository repository,
            Clock clock
    ) {
        return persistenceProperties.mode() == PersistenceProperties.Mode.POSTGRES_MINIO
                ? new MarketSectorRotationPriceWindowAdapter(repository, clock)
                : LoadSectorRotationPriceWindowPort.unavailable();
    }

    @Bean
    EvaluateSectorRotationOutcomesUseCase evaluateSectorRotationOutcomesUseCase(
            SectorRotationValidationRepository repository,
            LoadSectorRotationPriceWindowPort prices
    ) {
        return new EvaluateSectorRotationOutcomesService(repository, prices);
    }

    @Bean
    CaptureSectorRotationSnapshotUseCase captureSectorRotationSnapshotUseCase(
            SectorRotationValidationRepository repository,
            LoadSectorRotationPriceWindowPort prices,
            EvaluateSectorRotationOutcomesUseCase outcomes
    ) {
        return new CaptureSectorRotationSnapshotService(repository, prices, outcomes);
    }

    @Bean
    NarrativeThemeCatalog narrativeThemeCatalog() {
        return new NarrativeThemeCatalog();
    }

    @Bean
    LoadResearchSnapshotPort loadResearchSnapshotPort(
            LoadMarketReadPort marketReadPort,
            ObjectMapper objectMapper
    ) {
        return new MarketReadResearchSnapshotAdapter(marketReadPort, objectMapper);
    }

    @Bean
    LoadResearchCatalogPort loadResearchCatalogPort(
            JsonEnvelopeStore projectionStore
    ) {
        return new FileResearchCatalogAdapter(projectionStore);
    }

    @Bean
    LoadCurrentCompanyMetricsPort loadCurrentCompanyMetricsPort(
            CompanyResearchSummaryRepository repository,
            Clock clock
    ) {
        return new CompanyResearchSummaryMetricsAdapter(repository, clock);
    }

    @Bean
    LoadSectorTotalReturnHistoryPort loadSectorTotalReturnHistoryPort(
            MarketObservationRepository repository
    ) {
        return new MarketSectorTotalReturnHistoryAdapter(repository);
    }

    @Bean
    RunSectorRotationWalkForwardBacktestUseCase runSectorRotationWalkForwardBacktestUseCase(
            LoadSectorTotalReturnHistoryPort histories,
            SectorWalkForwardBacktestPolicy policy,
            Clock clock
    ) {
        return new RunSectorRotationWalkForwardBacktestService(histories, policy, clock);
    }

    @Bean
    EvaluateResearchParityUseCase evaluateResearchParityUseCase(
            LoadResearchSnapshotPort snapshotPort,
            NarrativeHeatPolicy narrativeHeatPolicy,
            SectorRotationPolicy sectorRotationPolicy
    ) {
        return new EvaluateResearchParityService(snapshotPort, narrativeHeatPolicy, sectorRotationPolicy);
    }

    @Bean
    QueryNarrativesUseCase queryNarrativesUseCase(
            LoadResearchSnapshotPort snapshotPort,
            NarrativeHeatPolicy narrativeHeatPolicy,
            NarrativeThemeCatalog narrativeThemeCatalog,
            NarrativeSourceRepository narrativeSourceRepository,
            NarrativeSourcePolicy narrativeSourcePolicy,
            NarrativeSourceCatalog narrativeSourceCatalog,
            Clock clock
    ) {
        return new QueryNarrativesService(
                snapshotPort, narrativeHeatPolicy, narrativeThemeCatalog, narrativeSourceRepository,
                narrativeSourcePolicy, narrativeSourceCatalog, clock);
    }

    @Bean
    EvaluateCurrentSectorRotationUseCase evaluateCurrentSectorRotationUseCase(
            LoadResearchCatalogPort catalogPort,
            SectorRotationPolicy sectorRotationPolicy,
            LoadSectorEarningsRevisionBreadthPort revisionBreadthPort,
            SectorEarningsRevisionBreadthPolicy revisionBreadthPolicy,
            SectorMarketEvidenceRepository sectorMarketEvidenceRepository
    ) {
        return new EvaluateCurrentSectorRotationService(
                catalogPort, sectorRotationPolicy, revisionBreadthPort, revisionBreadthPolicy,
                sectorMarketEvidenceRepository);
    }

    @Bean
    QueryResearchCatalogUseCase currentQueryResearchCatalogUseCase(
            LoadResearchCatalogPort catalogPort,
            LoadResearchSnapshotPort snapshotPort,
            EvaluateCurrentSectorRotationUseCase currentRotation,
            LoadCurrentCompanyMetricsPort currentCompanyMetrics
    ) {
        return new QueryResearchCatalogService(
                catalogPort, snapshotPort, currentRotation, currentCompanyMetrics);
    }
}
