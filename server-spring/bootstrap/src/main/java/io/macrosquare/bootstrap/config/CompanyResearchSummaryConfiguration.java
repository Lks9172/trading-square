package io.macrosquare.bootstrap.config;

import io.macrosquare.company.adapter.in.scheduling.CompanyResearchSummaryScheduler;
import io.macrosquare.company.adapter.out.persistence.InMemoryCompanyResearchSummaryRepository;
import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import io.macrosquare.company.application.port.in.RefreshCompanyResearchSummariesUseCase;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystUniversePort;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.port.out.LoadCompanySectorAssessmentPort;
import io.macrosquare.company.domain.horizon.CompanyHorizonSignalPolicy;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecisionPolicy;
import io.macrosquare.company.application.service.RefreshCompanyResearchSummariesService;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
public class CompanyResearchSummaryConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode",
            havingValue = "legacy-file", matchIfMissing = true)
    InMemoryCompanyResearchSummaryRepository inMemoryCompanyResearchSummaryRepository() {
        return new InMemoryCompanyResearchSummaryRepository();
    }

    @Bean(name = "companyResearchSummaryExecutor", destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "macrosquare.company-research-summary", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ExecutorService companyResearchSummaryExecutor(CompanyResearchSummaryProperties properties) {
        return Executors.newFixedThreadPool(
                properties.concurrency(),
                Thread.ofVirtual().name("company-summary-refresh-", 0).factory()
        );
    }

    @Bean(name = "companyResearchSummaryTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "macrosquare.company-research-summary", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    ThreadPoolTaskScheduler companyResearchSummaryTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("company-summary-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.company-research-summary", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    RefreshCompanyResearchSummariesUseCase refreshCompanyResearchSummariesUseCase(
            LoadCompanyAnalystUniversePort universe,
            EvaluateCompanyResearchParityUseCase research,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            CompanyResearchSummaryRepository repository,
            LoadCompanyReadPort companyRead,
            LoadCompanySectorAssessmentPort sectorAssessment,
            @Qualifier("companyResearchSummaryExecutor") Executor executor,
            Clock clock,
            OperationalEventSink operationalEvents
    ) {
        return new RefreshCompanyResearchSummariesService(
                universe, research, priceSignals, repository, executor, clock, operationalEvents,
                companyRead, sectorAssessment, new CompanyHorizonSignalPolicy(),
                new CompanyInvestmentDecisionPolicy());
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.company-research-summary", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    CompanyResearchSummaryScheduler companyResearchSummaryScheduler(
            RefreshCompanyResearchSummariesUseCase refresh,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new CompanyResearchSummaryScheduler(refresh, exclusiveTasks);
    }
}
