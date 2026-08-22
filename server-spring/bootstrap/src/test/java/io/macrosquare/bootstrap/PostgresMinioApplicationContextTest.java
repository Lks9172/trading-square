package io.macrosquare.bootstrap;

import io.macrosquare.shared.adapter.out.storage.JsonEnvelopeStore;
import io.macrosquare.shared.adapter.out.storage.WritableJsonEnvelopeStore;
import io.macrosquare.shared.adapter.out.persistence.PostgresAdvisoryTaskExecution;
import io.macrosquare.shared.application.port.out.ExclusiveTaskExecution;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "macrosquare.persistence.mode=postgres-minio",
        "macrosquare.persistence.legacy-import-enabled=false",
        "macrosquare.notifications.enabled=false",
        "macrosquare.market-collection.enabled=false",
        "macrosquare.market-collection.history-seed-enabled=false",
        "macrosquare.market-collection.snapshot-refresh-enabled=false",
        "macrosquare.company-analyst-history.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:postgres-minio-context;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/test-migration"
})
class PostgresMinioApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exposesOneProjectionStoreForBothReadAndWriteContracts() {
        var readableStores = context.getBeansOfType(JsonEnvelopeStore.class);
        var writableStores = context.getBeansOfType(WritableJsonEnvelopeStore.class);

        assertThat(readableStores).hasSize(1);
        assertThat(writableStores).hasSize(1);
        assertThat(readableStores.values().iterator().next())
                .isSameAs(writableStores.values().iterator().next());
        var exclusiveTasks = context.getBean(ExclusiveTaskExecution.class);
        assertThat(exclusiveTasks).isInstanceOf(PostgresAdvisoryTaskExecution.class);
        assertThat(ReflectionTestUtils.getField(exclusiveTasks, "dataSource"))
                .isInstanceOf(SimpleDriverDataSource.class)
                .isNotSameAs(context.getBean(DataSource.class));
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbc.queryForObject(
                "select applied from context_migration_probe where id = 1", Boolean.class))
                .isTrue();
    }
}
