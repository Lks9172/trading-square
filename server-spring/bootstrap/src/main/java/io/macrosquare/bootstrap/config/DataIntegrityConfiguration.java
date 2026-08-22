package io.macrosquare.bootstrap.config;

import io.macrosquare.integrity.adapter.in.scheduling.DataIntegrityScheduler;
import io.macrosquare.integrity.adapter.out.notification.NotificationIntegrityIncidentAdapter;
import io.macrosquare.integrity.adapter.out.persistence.JdbcDataIntegrityEvidenceAdapter;
import io.macrosquare.integrity.application.port.in.CheckDataIntegrityUseCase;
import io.macrosquare.integrity.application.port.out.LoadDataIntegrityEvidencePort;
import io.macrosquare.integrity.application.port.out.PublishDataIntegrityIncidentPort;
import io.macrosquare.integrity.application.service.CheckDataIntegrityService;
import io.macrosquare.integrity.domain.DataIntegrityPolicy;
import io.macrosquare.notification.application.port.out.NotificationStateRepository;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataIntegrityProperties.class)
@ConditionalOnProperty(prefix = "macrosquare.integrity-monitor", name = "enabled", havingValue = "true")
public class DataIntegrityConfiguration {

    @Bean(name = "dataIntegrityTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler dataIntegrityTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("data-integrity-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(15);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Bean
    LoadDataIntegrityEvidencePort loadDataIntegrityEvidencePort(
            NamedParameterJdbcTemplate jdbc,
            DataIntegrityProperties properties,
            Clock clock
    ) {
        return new JdbcDataIntegrityEvidenceAdapter(jdbc, properties.calculationVersion(), clock);
    }

    @Bean
    PublishDataIntegrityIncidentPort publishDataIntegrityIncidentPort(NotificationStateRepository state) {
        return new NotificationIntegrityIncidentAdapter(state);
    }

    @Bean
    CheckDataIntegrityUseCase checkDataIntegrityUseCase(
            LoadDataIntegrityEvidencePort evidence,
            PublishDataIntegrityIncidentPort incidents,
            DataIntegrityProperties properties,
            Clock clock
    ) {
        return new CheckDataIntegrityService(
                evidence,
                incidents,
                new DataIntegrityPolicy(
                        properties.expectedCompanyUniverse(), properties.maximumSummaryAge()),
                clock
        );
    }

    @Bean
    DataIntegrityScheduler dataIntegrityScheduler(
            CheckDataIntegrityUseCase check,
            ExclusiveTaskExecution exclusiveTasks
    ) {
        return new DataIntegrityScheduler(check, exclusiveTasks);
    }
}
