package io.macrosquare.bootstrap.config;

import io.macrosquare.company.adapter.out.persistence.JdbcCompanyAnalystHistoryStoreAdapter;
import io.macrosquare.company.adapter.out.persistence.JdbcCompanyResearchSummaryRepository;
import io.macrosquare.execution.adapter.out.persistence.JdbcInvestmentExecutionAdapter;
import io.macrosquare.market.adapter.out.persistence.JdbcMarketObservationRepository;
import io.macrosquare.market.adapter.out.persistence.JdbcMarketCollectionStatusRepository;
import io.macrosquare.notification.adapter.out.persistence.JdbcNotificationStateRepository;
import io.macrosquare.shared.adapter.out.storage.JdbcObjectArtifactCatalog;
import io.macrosquare.shared.adapter.out.storage.MinioJsonEnvelopeStore;
import io.macrosquare.shared.adapter.out.storage.MinioObjectStorageAdapter;
import io.macrosquare.shared.adapter.out.storage.ObjectArtifactCatalog;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import io.macrosquare.shared.adapter.out.storage.WritableJsonEnvelopeStore;
import io.macrosquare.shared.adapter.out.persistence.PostgresAdvisoryTaskExecution;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import io.minio.MinioClient;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.Properties;
import javax.sql.DataSource;

/** Composition root for the production PostgreSQL + MinIO persistence profile. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        PersistenceProperties.class,
        ObjectStorageProperties.class,
        NotificationProperties.class
})
@ConditionalOnProperty(prefix = "macrosquare.persistence", name = "mode", havingValue = "postgres-minio")
public class PostgresMinioStorageConfiguration {

    @Bean
    TransactionOperations persistenceTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    ExclusiveTaskExecution exclusiveTaskExecution(
            DataSourceProperties dataSourceProperties,
            PersistenceProperties persistenceProperties
    ) {
        // A session advisory lock must retain its physical connection while a
        // task performs remote I/O. Isolate those sessions from the bounded
        // transactional Hikari pool so collection cannot starve API/database
        // work or trigger false-positive Hikari leak reports.
        var lockDataSource = dataSourceProperties.initializeDataSourceBuilder()
                .type(SimpleDriverDataSource.class)
                .build();
        if (dataSourceProperties.determineUrl().startsWith("jdbc:postgresql:")) {
            var connectionProperties = new Properties();
            connectionProperties.setProperty("ApplicationName", "macrosquare-task-lock");
            connectionProperties.setProperty("connectTimeout", "5");
            connectionProperties.setProperty("tcpKeepAlive", "true");
            lockDataSource.setConnectionProperties(connectionProperties);
        }
        return new PostgresAdvisoryTaskExecution(
                lockDataSource,
                persistenceProperties.exclusiveTaskMaxConcurrency()
        );
    }

    @Bean
    MinioClient minioClient(ObjectStorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.endpoint().toString())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Bean
    ObjectArtifactCatalog objectArtifactCatalog(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TransactionOperations transactions
    ) {
        return new JdbcObjectArtifactCatalog(jdbc, objectMapper, transactions);
    }

    @Bean
    ObjectStorage objectStorage(
            MinioClient client,
            ObjectArtifactCatalog catalog,
            ObjectStorageProperties properties,
            Clock clock
    ) {
        return new MinioObjectStorageAdapter(
                client, catalog, properties.bucket(), properties.maximumObjectBytes(), clock);
    }

    @Bean
    WritableJsonEnvelopeStore jsonEnvelopeStore(
            ObjectStorage storage,
            ObjectMapper objectMapper,
            ObjectStorageProperties properties
    ) {
        return new MinioJsonEnvelopeStore(
                storage,
                objectMapper,
                properties.maximumProjectionBytes(),
                properties.maximumCachedDocuments(),
                properties.projectionCacheTtl()
        );
    }

    @Bean
    JdbcMarketObservationRepository jdbcMarketObservationRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcMarketObservationRepository(jdbc);
    }

    @Bean
    JdbcMarketCollectionStatusRepository jdbcMarketCollectionStatusRepository(
            NamedParameterJdbcTemplate jdbc
    ) {
        return new JdbcMarketCollectionStatusRepository(jdbc);
    }

    @Bean
    JdbcCompanyAnalystHistoryStoreAdapter jdbcCompanyAnalystHistoryStoreAdapter(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        return new JdbcCompanyAnalystHistoryStoreAdapter(jdbc, transactions);
    }

    @Bean
    JdbcCompanyResearchSummaryRepository jdbcCompanyResearchSummaryRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper
    ) {
        return new JdbcCompanyResearchSummaryRepository(jdbc, objectMapper);
    }

    @Bean
    JdbcInvestmentExecutionAdapter jdbcInvestmentExecutionAdapter(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions,
            ObjectMapper objectMapper
    ) {
        return new JdbcInvestmentExecutionAdapter(jdbc, transactions, objectMapper);
    }

    @Bean
    JdbcNotificationStateRepository jdbcNotificationStateRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions,
            ObjectMapper objectMapper
    ) {
        return new JdbcNotificationStateRepository(jdbc, transactions, objectMapper);
    }

}
