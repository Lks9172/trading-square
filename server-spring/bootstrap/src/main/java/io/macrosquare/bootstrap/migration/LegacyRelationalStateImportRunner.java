package io.macrosquare.bootstrap.migration;

import io.macrosquare.bootstrap.config.InvestmentExecutionProperties;
import io.macrosquare.bootstrap.config.MarketDataProperties;
import io.macrosquare.bootstrap.config.NotificationProperties;
import io.macrosquare.bootstrap.config.CompanyAnalystHistoryProperties;
import io.macrosquare.company.adapter.out.persistence.FileCompanyAnalystHistoryStoreAdapter;
import io.macrosquare.company.adapter.out.persistence.JdbcCompanyAnalystHistoryStoreAdapter;
import io.macrosquare.execution.adapter.out.persistence.FileInvestmentExecutionAdapter;
import io.macrosquare.execution.adapter.out.persistence.JdbcInvestmentExecutionAdapter;
import io.macrosquare.notification.adapter.out.persistence.FileNotificationStateRepository;
import io.macrosquare.notification.adapter.out.persistence.JdbcNotificationStateRepository;
import io.macrosquare.shared.adapter.out.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * One-way, idempotent cutover importer. Legacy files remain read-only rollback evidence;
 * all post-cutover writes go to PostgreSQL or MinIO.
 */
public final class LegacyRelationalStateImportRunner implements ApplicationRunner, Ordered {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegacyRelationalStateImportRunner.class);
    private static final int MAX_TRADE_LOG_IMPORT = 100_000;

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final InvestmentExecutionProperties executionProperties;
    private final NotificationProperties notificationProperties;
    private final MarketDataProperties marketDataProperties;
    private final CompanyAnalystHistoryProperties analystHistoryProperties;
    private final JdbcInvestmentExecutionAdapter executionTarget;
    private final JdbcNotificationStateRepository notificationTarget;
    private final JdbcCompanyAnalystHistoryStoreAdapter analystHistoryTarget;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final ObjectStorage objectStorage;

    public LegacyRelationalStateImportRunner(
            ObjectMapper objectMapper,
            Clock clock,
            InvestmentExecutionProperties executionProperties,
            NotificationProperties notificationProperties,
            MarketDataProperties marketDataProperties,
            CompanyAnalystHistoryProperties analystHistoryProperties,
            JdbcInvestmentExecutionAdapter executionTarget,
            JdbcNotificationStateRepository notificationTarget,
            JdbcCompanyAnalystHistoryStoreAdapter analystHistoryTarget,
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions,
            ObjectStorage objectStorage
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.executionProperties = Objects.requireNonNull(executionProperties);
        this.notificationProperties = Objects.requireNonNull(notificationProperties);
        this.marketDataProperties = Objects.requireNonNull(marketDataProperties);
        this.analystHistoryProperties = Objects.requireNonNull(analystHistoryProperties);
        this.executionTarget = Objects.requireNonNull(executionTarget);
        this.notificationTarget = Objects.requireNonNull(notificationTarget);
        this.analystHistoryTarget = Objects.requireNonNull(analystHistoryTarget);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.objectStorage = Objects.requireNonNull(objectStorage);
    }

    @Override
    public void run(ApplicationArguments args) {
        importExecution();
        importNotificationState();
        importAnalystHistory();
        importCurrentSnapshot();
    }

    private void importExecution() {
        // Select each legacy artifact independently. Some installations already
        // wrote the Spring-owned plan but still kept tranche/audit files in the
        // older Node tree; selecting one directory for the whole aggregate would
        // silently drop that mixed-layout state during cutover.
        var owned = executionSource(executionProperties.dataDirectory(),
                executionProperties.legacyDataDirectory());
        var legacy = executionSource(executionProperties.legacyDataDirectory(),
                executionProperties.dataDirectory());
        var plan = owned.load().or(legacy::load);
        var ownedTranches = owned.findAll();
        var tranches = ownedTranches.isEmpty() ? legacy.findAll() : ownedTranches;
        var ownedTradeLog = owned.recent(MAX_TRADE_LOG_IMPORT);
        var tradeLog = ownedTradeLog.isEmpty() ? legacy.recent(MAX_TRADE_LOG_IMPORT) : ownedTradeLog;
        transactions.executeWithoutResult(ignored -> {
            if (count("execution.investment_plan") == 0) plan.ifPresent(executionTarget::save);
            if (count("execution.tranche_entry") == 0) tranches.forEach(executionTarget::append);
            if (count("execution.trade_log") == 0) tradeLog.forEach(executionTarget::append);
        });
        LOGGER.info("Legacy execution import checked (plan={}, tranches={}, tradeLog={})",
                plan.isPresent(), tranches.size(), tradeLog.size());
    }

    private FileInvestmentExecutionAdapter executionSource(
            java.nio.file.Path primary,
            java.nio.file.Path fallback
    ) {
        return new FileInvestmentExecutionAdapter(
                objectMapper, clock, primary, fallback, false);
    }

    private void importNotificationState() {
        if (count("notification.delivery_state") > 0) return;
        var file = notificationProperties.dataDirectory().resolve("notification-state-v1.json");
        if (!Files.isRegularFile(file)) return;
        var source = new FileNotificationStateRepository(objectMapper, notificationProperties.dataDirectory());
        notificationTarget.save(source.load());
        LOGGER.info("Legacy notification delivery state imported");
    }

    private void importAnalystHistory() {
        var source = new FileCompanyAnalystHistoryStoreAdapter(
                objectMapper, analystHistoryProperties.directory());
        var imported = 0;
        for (var ticker : analystHistoryProperties.tickers()) {
            if (analystHistoryTarget.load(ticker).isPresent()) continue;
            var history = source.load(ticker);
            if (history.isEmpty()) continue;
            var file = analystHistoryProperties.directory().resolve(
                    "company-analyst-history-" + ticker.toLowerCase(java.util.Locale.ROOT) + ".json");
            var updatedAt = clock.instant();
            try {
                if (Files.isRegularFile(file)) updatedAt = Files.getLastModifiedTime(file).toInstant();
            } catch (java.io.IOException error) {
                LOGGER.warn("Unable to retain analyst history file timestamp for {}", ticker, error);
            }
            analystHistoryTarget.save(ticker, history.get(), updatedAt);
            imported++;
        }
        LOGGER.info("Legacy analyst history import checked (imported={})", imported);
    }

    private void importCurrentSnapshot() {
        var key = "projections/latest-system-snapshot-default-v1.json";
        if (objectStorage.find(key, marketDataProperties.maximumSnapshotBytes()).isPresent()) return;
        var file = marketDataProperties.snapshotFile();
        if (!Files.isRegularFile(file)) return;
        try {
            var bytes = Files.readAllBytes(file);
            if (bytes.length == 0 || bytes.length > marketDataProperties.maximumSnapshotBytes()) {
                throw new IllegalArgumentException("legacy snapshot exceeds its configured bound");
            }
            objectStorage.put(key, bytes, "application/json", java.util.Map.of("migration", "spring-file-store"));
            LOGGER.info("Current market snapshot imported into MinIO");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to import current market snapshot", error);
        }
    }

    private long count(String table) {
        if (!List.of(
                "execution.investment_plan", "execution.tranche_entry", "execution.trade_log",
                "notification.delivery_state").contains(table)) {
            throw new IllegalArgumentException("unsupported import table");
        }
        var result = jdbc.queryForObject("select count(*) from " + table,
                new MapSqlParameterSource(), Long.class);
        return result == null ? 0L : result;
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
