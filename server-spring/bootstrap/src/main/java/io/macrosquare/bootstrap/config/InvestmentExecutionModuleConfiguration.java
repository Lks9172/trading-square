package io.macrosquare.bootstrap.config;

import io.macrosquare.execution.adapter.out.market.SnapshotMarketExecutionContextAdapter;
import io.macrosquare.execution.adapter.out.market.SnapshotWeeklyReviewContextAdapter;
import io.macrosquare.execution.adapter.out.persistence.FileInvestmentExecutionAdapter;
import io.macrosquare.execution.application.port.in.ManageInvestmentExecutionUseCase;
import io.macrosquare.execution.application.port.in.EvaluatePurchasingPowerUseCase;
import io.macrosquare.execution.application.port.in.QueryWeeklyReviewUseCase;
import io.macrosquare.execution.application.port.out.LoadMarketExecutionContextPort;
import io.macrosquare.execution.application.port.out.LoadWeeklyReviewMarketContextPort;
import io.macrosquare.execution.application.port.out.InvestmentPlanRepository;
import io.macrosquare.execution.application.port.out.TradeLogRepository;
import io.macrosquare.execution.application.port.out.TrancheRepository;
import io.macrosquare.execution.application.service.ManageInvestmentExecutionService;
import io.macrosquare.execution.application.service.EvaluatePurchasingPowerService;
import io.macrosquare.execution.application.service.QueryWeeklyReviewService;
import io.macrosquare.execution.domain.service.ExecutionPlanPolicy;
import io.macrosquare.execution.domain.service.PortfolioAllocationPolicy;
import io.macrosquare.execution.domain.service.PurchasingPowerPolicy;
import io.macrosquare.execution.domain.service.WeeklyPlanReviewPolicy;
import io.macrosquare.market.application.port.in.QueryMarketReadUseCase;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InvestmentExecutionProperties.class)
public class InvestmentExecutionModuleConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "file", matchIfMissing = true)
    FileInvestmentExecutionAdapter fileInvestmentExecutionAdapter(
            ObjectMapper objectMapper,
            Clock clock,
            InvestmentExecutionProperties properties
    ) {
        return new FileInvestmentExecutionAdapter(
                objectMapper,
                clock,
                properties.dataDirectory()
        );
    }

    @Bean
    LoadMarketExecutionContextPort loadMarketExecutionContextPort(QueryMarketReadUseCase marketRead) {
        return new SnapshotMarketExecutionContextAdapter(marketRead);
    }

    @Bean
    LoadWeeklyReviewMarketContextPort loadWeeklyReviewMarketContextPort(
            LoadMarketSnapshotProjectionPort snapshots
    ) {
        return new SnapshotWeeklyReviewContextAdapter(snapshots);
    }

    @Bean
    ExecutionPlanPolicy executionPlanPolicy() {
        return new ExecutionPlanPolicy();
    }

    @Bean
    PortfolioAllocationPolicy portfolioAllocationPolicy() {
        return new PortfolioAllocationPolicy();
    }

    @Bean
    WeeklyPlanReviewPolicy weeklyPlanReviewPolicy(PortfolioAllocationPolicy allocations) {
        return new WeeklyPlanReviewPolicy(allocations);
    }

    @Bean
    PurchasingPowerPolicy purchasingPowerPolicy() {
        return new PurchasingPowerPolicy();
    }

    @Bean
    EvaluatePurchasingPowerUseCase evaluatePurchasingPowerUseCase(PurchasingPowerPolicy policy) {
        return new EvaluatePurchasingPowerService(policy);
    }

    @Bean
    QueryWeeklyReviewUseCase queryWeeklyReviewUseCase(
            InvestmentPlanRepository plans,
            TradeLogRepository tradeLogs,
            LoadWeeklyReviewMarketContextPort market,
            WeeklyPlanReviewPolicy policy,
            Clock clock
    ) {
        return new QueryWeeklyReviewService(plans, tradeLogs, market, policy, clock);
    }

    @Bean
    ManageInvestmentExecutionUseCase manageInvestmentExecutionUseCase(
            InvestmentPlanRepository plans,
            TradeLogRepository tradeLogs,
            TrancheRepository tranches,
            LoadMarketExecutionContextPort marketContextPort,
            ExecutionPlanPolicy policy,
            Clock clock,
            OperationalEventSink operationalEvents
    ) {
        return new ManageInvestmentExecutionService(
                plans,
                tradeLogs,
                tranches,
                marketContextPort,
                policy,
                clock,
                operationalEvents
        );
    }
}
