package io.macrosquare.bootstrap.config;

import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import io.macrosquare.company.application.port.in.EnrichCompanyResearchUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyDetailRevenueMixShadowUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyFilingDetailParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyRevenueMixParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanySubmissionsParityUseCase;
import io.macrosquare.company.application.port.in.QueryCompanyReadUseCase;
import io.macrosquare.company.application.port.in.ProbeCompanyFilingDocumentUseCase;
import io.macrosquare.company.application.port.in.ResolveCompanyAnalystHistoryUseCase;
import io.macrosquare.company.application.port.out.LoadCompanyFundamentalsEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanyFilingDetailEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanyFilingDocumentContentPort;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystConsensusPort;
import io.macrosquare.company.application.port.out.LoadCompanyMarketQuotePort;
import io.macrosquare.company.application.port.out.LoadCompanyMarketCapitalizationPort;
import io.macrosquare.company.application.port.out.LoadCompanyPriceHistoryPort;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.port.out.LoadCompanyRevenueMixEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanySubmissionsEvidencePort;
import io.macrosquare.company.application.port.out.LoadCompanySectorAssessmentPort;
import io.macrosquare.company.application.port.out.ResolveCompanyIdentityPort;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.company.adapter.out.research.ResearchCatalogCompanySectorAssessmentAdapter;
import io.macrosquare.research.application.port.in.QueryResearchCatalogUseCase;
import io.macrosquare.company.application.service.EvaluateCompanyResearchParityService;
import io.macrosquare.company.application.service.EnrichCompanyResearchService;
import io.macrosquare.company.application.service.CompanyRevenueMixComposer;
import io.macrosquare.company.application.service.EvaluateCompanyDetailRevenueMixShadowService;
import io.macrosquare.company.application.service.EvaluateCompanyPriceSignalParityService;
import io.macrosquare.company.application.service.EvaluateCompanyFilingDetailParityService;
import io.macrosquare.company.application.service.EvaluateCompanyRevenueMixParityService;
import io.macrosquare.company.application.service.EvaluateCompanySubmissionsParityService;
import io.macrosquare.company.application.service.QueryCompanyReadService;
import io.macrosquare.company.application.service.ProbeCompanyFilingDocumentService;
import io.macrosquare.company.domain.service.CompanyBuyScoringPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsNormalizationPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsContinuityPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsFreshnessPolicy;
import io.macrosquare.company.domain.service.CompanyFilingClassificationPolicy;
import io.macrosquare.company.domain.service.CompanyGuidanceParsingPolicy;
import io.macrosquare.company.domain.service.CompanyIrMaterialPolicy;
import io.macrosquare.company.domain.service.CompanyMarketExpectationsPolicy;
import io.macrosquare.company.domain.service.CompanyRevenueMixPolicy;
import io.macrosquare.company.domain.service.CompanyScoringPolicy;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import io.macrosquare.company.domain.bottom.BottomPriceContextPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceSignalPolicy;
import io.macrosquare.company.domain.bottom.DeepBottomPolicy;
import io.macrosquare.company.domain.bottom.ReversalConfirmationPolicy;
import io.macrosquare.company.domain.bottom.VolumePriceConfirmationPolicy;
import io.macrosquare.company.domain.bottom.PriceStructurePolicy;
import io.macrosquare.company.domain.horizon.CompanyHorizonWalkForwardPolicy;
import io.macrosquare.technical.domain.MacdSignalPolicy;
import io.macrosquare.company.domain.horizon.CompanyHorizonSignalPolicy;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecisionPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
public class CompanyUseCaseConfiguration {

    @Bean
    EvaluateCompanyResearchParityUseCase evaluateCompanyResearchParityUseCase(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanyFundamentalsEvidencePort fundamentalsEvidencePort,
            LoadCompanyMarketQuotePort marketQuotePort,
            LoadCompanyMarketCapitalizationPort marketCapitalizationPort,
            LoadCompanySubmissionsEvidencePort submissionsEvidencePort,
            @Qualifier("directCompanyAnalystConsensusPort") LoadCompanyAnalystConsensusPort analystConsensusPort,
            ResolveCompanyAnalystHistoryUseCase analystHistoryUseCase,
            CompanyFundamentalsNormalizationPolicy normalizationPolicy,
            CompanyFundamentalsContinuityPolicy continuityPolicy,
            CompanyFundamentalsFreshnessPolicy freshnessPolicy,
            CompanyMarketExpectationsPolicy expectationsPolicy,
            CompanyScoringPolicy scoringPolicy,
            CompanyBuyScoringPolicy buyScoringPolicy,
            Clock clock
    ) {
        return new EvaluateCompanyResearchParityService(
                companyReadPort,
                companyIdentityPort,
                fundamentalsEvidencePort,
                marketQuotePort,
                marketCapitalizationPort,
                submissionsEvidencePort,
                analystConsensusPort,
                analystHistoryUseCase,
                normalizationPolicy,
                continuityPolicy,
                freshnessPolicy,
                expectationsPolicy,
                scoringPolicy,
                buyScoringPolicy,
                clock
        );
    }

    @Bean
    EvaluateCompanyPriceSignalParityUseCase evaluateCompanyPriceSignalParityUseCase(
            LoadCompanyReadPort companyReadPort,
            LoadCompanyPriceHistoryPort priceHistoryPort,
            BottomPriceContextPolicy contextPolicy,
            BottomPriceSignalPolicy priceSignalPolicy,
            DeepBottomPolicy deepBottomPolicy,
            ReversalConfirmationPolicy reversalPolicy,
            VolumePriceConfirmationPolicy technicalPolicy,
            PriceStructurePolicy priceStructurePolicy,
            CompanyHorizonWalkForwardPolicy walkForwardPolicy,
            MacdSignalPolicy macdSignalPolicy,
            YahooCompanyPriceHistoryProperties properties
    ) {
        return new EvaluateCompanyPriceSignalParityService(
                companyReadPort,
                priceHistoryPort,
                contextPolicy,
                priceSignalPolicy,
                deepBottomPolicy,
                reversalPolicy,
                technicalPolicy,
                priceStructurePolicy,
                walkForwardPolicy,
                macdSignalPolicy,
                properties.lookbackDays()
        );
    }

    @Bean
    EnrichCompanyResearchUseCase enrichCompanyResearchUseCase(
            EvaluateCompanyResearchParityUseCase research,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            EvaluateCompanySubmissionsParityUseCase submissions,
            EvaluateCompanyFilingDetailParityUseCase filingDetails,
            EvaluateCompanyRevenueMixParityUseCase revenueMix,
            CompanyRevenueMixComposer revenueMixComposer,
            CompanyHorizonSignalPolicy horizonPolicy,
            LoadCompanySectorAssessmentPort sectorAssessment,
            CompanyInvestmentDecisionPolicy investmentDecisionPolicy,
            @Qualifier("companyResearchEnrichmentExecutor") Executor executor,
            Clock clock,
            CompanyReadProperties properties,
            OperationalEventSink operationalEvents
    ) {
        return new EnrichCompanyResearchService(
                research, priceSignals, submissions, filingDetails, revenueMix, revenueMixComposer, horizonPolicy,
                sectorAssessment, investmentDecisionPolicy,
                executor, clock, properties.cacheTtl(), operationalEvents);
    }

    @Bean
    LoadCompanySectorAssessmentPort loadCompanySectorAssessmentPort(
            QueryResearchCatalogUseCase researchCatalog
    ) {
        return new ResearchCatalogCompanySectorAssessmentAdapter(researchCatalog);
    }

    @Bean
    QueryCompanyReadUseCase queryCompanyReadUseCase(
            LoadCompanyReadPort companyReadPort,
            EnrichCompanyResearchUseCase enrichment,
            CompanyResearchSummaryRepository summaryRepository,
            Clock clock
    ) {
        return new QueryCompanyReadService(companyReadPort, enrichment, summaryRepository, clock);
    }

    @Bean
    EvaluateCompanySubmissionsParityUseCase evaluateCompanySubmissionsParityUseCase(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanySubmissionsEvidencePort submissionsPort,
            CompanyFilingClassificationPolicy classificationPolicy,
            SecCompanySubmissionsProperties properties
    ) {
        return new EvaluateCompanySubmissionsParityService(
                companyReadPort,
                companyIdentityPort,
                submissionsPort,
                classificationPolicy,
                properties.parityFilingLimit()
        );
    }

    @Bean
    EvaluateCompanyFilingDetailParityUseCase evaluateCompanyFilingDetailParityUseCase(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanySubmissionsEvidencePort submissionsPort,
            LoadCompanyFilingDetailEvidencePort filingDetailPort,
            LoadCompanyFilingDocumentContentPort documentContentPort,
            CompanyFilingClassificationPolicy filingClassificationPolicy,
            CompanyIrMaterialPolicy irMaterialPolicy,
            CompanyGuidanceParsingPolicy guidanceParsingPolicy,
            SecCompanyFilingProperties properties
    ) {
        return new EvaluateCompanyFilingDetailParityService(
                companyReadPort,
                companyIdentityPort,
                submissionsPort,
                filingDetailPort,
                documentContentPort,
                filingClassificationPolicy,
                irMaterialPolicy,
                guidanceParsingPolicy,
                properties.primaryFilingLimit(),
                properties.attachmentFilingLimit(),
                properties.materialLimit()
        );
    }

    @Bean
    EvaluateCompanyRevenueMixParityUseCase evaluateCompanyRevenueMixParityUseCase(
            LoadCompanyReadPort companyReadPort,
            ResolveCompanyIdentityPort companyIdentityPort,
            LoadCompanySubmissionsEvidencePort submissionsPort,
            LoadCompanyRevenueMixEvidencePort revenueMixPort,
            CompanyRevenueMixPolicy revenueMixPolicy
    ) {
        return new EvaluateCompanyRevenueMixParityService(
                companyReadPort,
                companyIdentityPort,
                submissionsPort,
                revenueMixPort,
                revenueMixPolicy
        );
    }

    @Bean
    CompanyRevenueMixComposer companyRevenueMixComposer() {
        return new CompanyRevenueMixComposer();
    }

    @Bean
    EvaluateCompanyDetailRevenueMixShadowUseCase evaluateCompanyDetailRevenueMixShadowUseCase(
            LoadCompanyReadPort companyReadPort,
            EvaluateCompanyRevenueMixParityUseCase revenueMixParityUseCase,
            CompanyRevenueMixComposer composer
    ) {
        return new EvaluateCompanyDetailRevenueMixShadowService(
                companyReadPort,
                revenueMixParityUseCase,
                composer
        );
    }

    @Bean
    ProbeCompanyFilingDocumentUseCase probeCompanyFilingDocumentUseCase(
            LoadCompanyFilingDocumentContentPort documentContentPort,
            CompanyIrMaterialPolicy irMaterialPolicy
    ) {
        return new ProbeCompanyFilingDocumentService(documentContentPort, irMaterialPolicy);
    }
}
