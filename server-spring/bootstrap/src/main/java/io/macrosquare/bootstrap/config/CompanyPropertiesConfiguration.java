package io.macrosquare.bootstrap.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        SecCompanyFactsProperties.class,
        SecCompanyIdentityProperties.class,
        SecCompanySubmissionsProperties.class,
        SecCompanyFilingProperties.class,
        YahooCompanyQuoteProperties.class,
        YahooCompanyPriceHistoryProperties.class,
        CompanyAnalystProperties.class,
        CompanyAnalystHistoryProperties.class,
        CompanyReadProperties.class,
        CompanyResearchSummaryProperties.class
})
public class CompanyPropertiesConfiguration {
}
