package io.macrosquare.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
public class CompanyConcurrencyConfiguration {

    @Bean(name = "companyFactsRefreshExecutor", destroyMethod = "shutdownNow")
    ExecutorService companyFactsRefreshExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "companyIdentityRefreshExecutor", destroyMethod = "shutdownNow")
    ExecutorService companyIdentityRefreshExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "companySubmissionsRefreshExecutor", destroyMethod = "shutdownNow")
    ExecutorService companySubmissionsRefreshExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "companyQuoteRefreshExecutor", destroyMethod = "shutdownNow")
    ExecutorService companyQuoteRefreshExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "companyPriceHistoryRefreshExecutor", destroyMethod = "shutdownNow")
    ExecutorService companyPriceHistoryRefreshExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "companyResearchEnrichmentExecutor", destroyMethod = "shutdownNow")
    ExecutorService companyResearchEnrichmentExecutor() {
        return Executors.newFixedThreadPool(4,
                Thread.ofVirtual().name("company-research-enrichment-", 0).factory());
    }
}
