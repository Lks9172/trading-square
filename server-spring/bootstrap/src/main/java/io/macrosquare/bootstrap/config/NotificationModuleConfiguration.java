package io.macrosquare.bootstrap.config;

import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.execution.application.port.in.QueryWeeklyReviewUseCase;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.notification.adapter.in.scheduling.NotificationScheduler;
import io.macrosquare.notification.adapter.out.company.SpringInvestmentCandidateRefreshAdapter;
import io.macrosquare.notification.adapter.out.market.SnapshotMarketNotificationAdapter;
import io.macrosquare.notification.adapter.out.persistence.FileInvestmentCandidateAdapter;
import io.macrosquare.notification.adapter.out.persistence.FileNotificationStateRepository;
import io.macrosquare.notification.adapter.out.telegram.TelegramNotificationAdapter;
import io.macrosquare.notification.application.port.in.NotificationOrchestrationUseCase;
import io.macrosquare.notification.application.port.in.NotificationOutboxDispatchUseCase;
import io.macrosquare.notification.application.port.in.NotificationOutboxMaintenanceUseCase;
import io.macrosquare.notification.application.port.out.LoadInvestmentCandidatesPort;
import io.macrosquare.notification.application.port.out.LoadMarketNotificationPort;
import io.macrosquare.notification.application.port.out.NotificationStateRepository;
import io.macrosquare.notification.application.port.out.NotificationOutboxRepository;
import io.macrosquare.notification.application.port.out.RefreshInvestmentCandidatePort;
import io.macrosquare.notification.application.port.out.SendNotificationPort;
import io.macrosquare.notification.application.service.NotificationOrchestrationService;
import io.macrosquare.notification.application.service.NotificationOutboxService;
import io.macrosquare.notification.domain.InvestmentCandidatePolicy;
import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
@ConditionalOnProperty(prefix = "macrosquare.notifications", name = "enabled", havingValue = "true")
public class NotificationModuleConfiguration {

    @Bean(name = "notificationWorkerExecutor", destroyMethod = "shutdownNow")
    ExecutorService notificationWorkerExecutor(NotificationProperties properties) {
        return Executors.newFixedThreadPool(properties.scanConcurrency(),
                Thread.ofVirtual().name("notification-worker-", 0).factory());
    }

    @Bean(name = "notificationTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler notificationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // A full company scan must not delay the independent five-minute market check.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("notification-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    InvestmentCandidatePolicy investmentCandidatePolicy() {
        return new InvestmentCandidatePolicy();
    }

    @Bean
    LoadInvestmentCandidatesPort loadInvestmentCandidatesPort(
            JsonEnvelopeStore projectionStore,
            Clock clock
    ) {
        return new FileInvestmentCandidateAdapter(projectionStore, clock);
    }

    @Bean
    LoadMarketNotificationPort loadMarketNotificationPort(
            LoadMarketSnapshotProjectionPort snapshotStore,
            QueryWeeklyReviewUseCase weeklyReview
    ) {
        return new SnapshotMarketNotificationAdapter(snapshotStore, weeklyReview);
    }

    @Bean
    @ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "file", matchIfMissing = true)
    FileNotificationStateRepository notificationStateRepository(
            ObjectMapper objectMapper,
            NotificationProperties properties
    ) {
        return new FileNotificationStateRepository(objectMapper, properties.dataDirectory());
    }

    @Bean("telegramNotificationRestClient")
    RestClient telegramNotificationRestClient(NotificationProperties properties) {
        var client = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().requestFactory(requestFactory)
                // Telegram requires the bot token in the URL path. This dedicated client must
                // never publish HTTP observations or traces containing the expanded URI.
                .observationRegistry(ObservationRegistry.NOOP)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE).build();
    }

    @Bean
    SendNotificationPort sendNotificationPort(
            @org.springframework.beans.factory.annotation.Qualifier("telegramNotificationRestClient") RestClient client,
            NotificationProperties properties
    ) {
        return new TelegramNotificationAdapter(
                client, properties.telegramBotToken(), properties.telegramChatId(),
                properties.sendAttempts(), properties.retryDelay());
    }

    @Bean
    RefreshInvestmentCandidatePort refreshInvestmentCandidatePort(
            EvaluateCompanyResearchParityUseCase companyResearch,
            EvaluateCompanyPriceSignalParityUseCase companyPriceSignals,
            CompanyResearchSummaryRepository companySummaries,
            Clock clock
    ) {
        return new SpringInvestmentCandidateRefreshAdapter(
                companyResearch, companyPriceSignals, companySummaries,
                new io.macrosquare.company.domain.investment.CompanyPriceStructureActionGuard(), clock);
    }

    @Bean
    NotificationOrchestrationUseCase notificationOrchestrationUseCase(
            LoadInvestmentCandidatesPort candidates,
            LoadMarketNotificationPort market,
            NotificationStateRepository state,
            RefreshInvestmentCandidatePort candidateRefresher,
            InvestmentCandidatePolicy policy,
            @org.springframework.beans.factory.annotation.Qualifier("notificationWorkerExecutor") ExecutorService executor,
            Clock clock,
            OperationalEventSink operationalEvents
    ) {
        return new NotificationOrchestrationService(
                candidates, market, state, candidateRefresher, policy, executor, clock, operationalEvents);
    }

    @Bean
    NotificationOutboxService notificationOutboxService(
            NotificationOutboxRepository outbox,
            SendNotificationPort sender,
            Clock clock,
            NotificationProperties properties,
            OperationalEventSink operationalEvents
    ) {
        return new NotificationOutboxService(
                outbox,
                sender,
                clock,
                properties.outboxBatchSize(),
                properties.outboxLeaseDuration(),
                properties.outboxRetryBaseDelay(),
                properties.outboxMaximumAttempts(),
                operationalEvents);
    }

    @Bean
    NotificationScheduler notificationScheduler(
            NotificationOrchestrationUseCase notifications,
            NotificationOutboxDispatchUseCase outbox,
            NotificationOutboxMaintenanceUseCase outboxMaintenance,
            @org.springframework.beans.factory.annotation.Qualifier("notificationTaskScheduler") ThreadPoolTaskScheduler scheduler,
            Clock clock,
            NotificationProperties properties,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new NotificationScheduler(
                notifications, outbox, outboxMaintenance, scheduler, clock,
                properties.startupDelay(), properties.postStartupRecalculationDelay(),
                properties.outboxRetention(), exclusiveTasks);
    }
}
