package io.macrosquare.bootstrap.config;

import io.macrosquare.company.application.port.in.ScoreCompanyUseCase;
import io.macrosquare.company.application.service.ScoreCompanyService;
import io.macrosquare.company.domain.service.CompanyBuyScoringPolicy;
import io.macrosquare.company.domain.service.CompanyAnalystHistoryPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsNormalizationPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsContinuityPolicy;
import io.macrosquare.company.domain.service.CompanyFundamentalsFreshnessPolicy;
import io.macrosquare.company.domain.service.CompanyFilingClassificationPolicy;
import io.macrosquare.company.domain.service.CompanyGuidanceParsingPolicy;
import io.macrosquare.company.domain.service.CompanyIrMaterialPolicy;
import io.macrosquare.company.domain.service.CompanyMarketExpectationsPolicy;
import io.macrosquare.company.domain.service.CompanyRevenueMixPolicy;
import io.macrosquare.company.domain.service.CompanyScoringPolicy;
import io.macrosquare.company.domain.bottom.BottomPatternPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceContextPolicy;
import io.macrosquare.company.domain.bottom.BottomPriceSignalPolicy;
import io.macrosquare.company.domain.bottom.DeepBottomPolicy;
import io.macrosquare.company.domain.bottom.ReversalConfirmationPolicy;
import io.macrosquare.company.domain.bottom.VolumePriceConfirmationPolicy;
import io.macrosquare.company.domain.bottom.PriceStructurePolicy;
import io.macrosquare.company.domain.horizon.CompanyHorizonSignalPolicy;
import io.macrosquare.company.domain.horizon.CompanyHorizonWalkForwardPolicy;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecisionPolicy;
import io.macrosquare.technical.domain.MacdSignalPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration(proxyBeanMethods = false)
public class CompanyDomainConfiguration {

    @Bean
    CompanyScoringPolicy companyScoringPolicy() {
        return new CompanyScoringPolicy();
    }

    @Bean
    CompanyBuyScoringPolicy companyBuyScoringPolicy() {
        return new CompanyBuyScoringPolicy();
    }

    @Bean
    CompanyInvestmentDecisionPolicy companyInvestmentDecisionPolicy() {
        return new CompanyInvestmentDecisionPolicy();
    }

    @Bean
    CompanyFundamentalsNormalizationPolicy companyFundamentalsNormalizationPolicy() {
        return new CompanyFundamentalsNormalizationPolicy();
    }

    @Bean
    CompanyFundamentalsContinuityPolicy companyFundamentalsContinuityPolicy() {
        return new CompanyFundamentalsContinuityPolicy();
    }

    @Bean
    CompanyFundamentalsFreshnessPolicy companyFundamentalsFreshnessPolicy() {
        return new CompanyFundamentalsFreshnessPolicy();
    }

    @Bean
    CompanyFilingClassificationPolicy companyFilingClassificationPolicy() {
        return new CompanyFilingClassificationPolicy();
    }

    @Bean
    CompanyIrMaterialPolicy companyIrMaterialPolicy() {
        return new CompanyIrMaterialPolicy();
    }

    @Bean
    CompanyGuidanceParsingPolicy companyGuidanceParsingPolicy() {
        return new CompanyGuidanceParsingPolicy();
    }

    @Bean
    CompanyRevenueMixPolicy companyRevenueMixPolicy() {
        return new CompanyRevenueMixPolicy();
    }

    @Bean
    CompanyMarketExpectationsPolicy companyMarketExpectationsPolicy() {
        return new CompanyMarketExpectationsPolicy();
    }

    @Bean
    CompanyAnalystHistoryPolicy companyAnalystHistoryPolicy() {
        return new CompanyAnalystHistoryPolicy();
    }

    @Bean
    BottomPatternPolicy bottomPatternPolicy() {
        return new BottomPatternPolicy();
    }

    @Bean
    BottomPriceContextPolicy bottomPriceContextPolicy(BottomPatternPolicy bottomPatternPolicy) {
        return new BottomPriceContextPolicy(bottomPatternPolicy);
    }

    @Bean
    BottomPriceSignalPolicy bottomPriceSignalPolicy() {
        return new BottomPriceSignalPolicy();
    }

    @Bean
    DeepBottomPolicy deepBottomPolicy() {
        return new DeepBottomPolicy();
    }

    @Bean
    ReversalConfirmationPolicy reversalConfirmationPolicy() {
        return new ReversalConfirmationPolicy();
    }

    @Bean
    VolumePriceConfirmationPolicy volumePriceConfirmationPolicy() {
        return new VolumePriceConfirmationPolicy();
    }

    @Bean
    PriceStructurePolicy priceStructurePolicy() {
        return new PriceStructurePolicy();
    }

    @Bean
    MacdSignalPolicy macdSignalPolicy() {
        return new MacdSignalPolicy();
    }

    @Bean
    CompanyHorizonSignalPolicy companyHorizonSignalPolicy() {
        return new CompanyHorizonSignalPolicy();
    }

    @Bean
    CompanyHorizonWalkForwardPolicy companyHorizonWalkForwardPolicy(
            BottomPriceContextPolicy contextPolicy,
            BottomPriceSignalPolicy priceSignalPolicy,
            DeepBottomPolicy deepBottomPolicy,
            ReversalConfirmationPolicy reversalPolicy,
            VolumePriceConfirmationPolicy technicalPolicy
    ) {
        return new CompanyHorizonWalkForwardPolicy(
                contextPolicy, priceSignalPolicy, deepBottomPolicy, reversalPolicy, technicalPolicy);
    }

    @Bean
    ScoreCompanyUseCase scoreCompanyUseCase(CompanyScoringPolicy companyScoringPolicy) {
        return new ScoreCompanyService(companyScoringPolicy);
    }
}
