package io.macrosquare.bootstrap.config;

import io.macrosquare.shared.adapter.out.observability.MicrometerOperationalEventAdapter;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.macrosquare.shared.adapter.out.persistence.SingleWriterFileLease;
import io.macrosquare.bootstrap.health.SnapshotFreshnessHealthIndicator;
import io.macrosquare.bootstrap.health.ObjectStorageHealthIndicator;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.minio.MinioClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityModuleConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "macrosquare.persistence", name = "mode", havingValue = "legacy-file", matchIfMissing = true)
    ExclusiveTaskExecution localExclusiveTaskExecution() {
        return ExclusiveTaskExecution.local();
    }

    @Bean
    OperationalEventSink operationalEventSink(MeterRegistry meterRegistry) {
        return new MicrometerOperationalEventAdapter(meterRegistry);
    }

    @Bean
    SnapshotFreshnessHealthIndicator snapshotFreshnessHealthIndicator(
            MarketDataProperties properties,
            LoadMarketSnapshotProjectionPort snapshots,
            java.time.Clock clock,
            MeterRegistry meterRegistry
    ) {
        return new SnapshotFreshnessHealthIndicator(properties, snapshots, clock, meterRegistry);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
    ObjectStorageHealthIndicator objectStorageHealthIndicator(
            MinioClient client,
            ObjectStorageProperties properties
    ) {
        return new ObjectStorageHealthIndicator(client, properties);
    }

    @Bean(name = "objectStorageHealthIndicator")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "macrosquare.persistence", name = "mode", havingValue = "legacy-file", matchIfMissing = true)
    HealthIndicator legacyObjectStorageHealthIndicator() {
        return () -> Health.up().withDetail("mode", "legacy-file").build();
    }

    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "macrosquare.storage", name = "single-writer-guard-enabled",
            havingValue = "true")
    SingleWriterFileLease singleWriterFileLease(InvestmentExecutionProperties properties) {
        return new SingleWriterFileLease(properties.dataDirectory());
    }
}
