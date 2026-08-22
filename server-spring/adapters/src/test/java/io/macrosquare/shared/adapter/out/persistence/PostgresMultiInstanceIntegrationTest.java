package io.macrosquare.shared.adapter.out.persistence;

import io.macrosquare.execution.adapter.out.persistence.JdbcInvestmentExecutionAdapter;
import io.macrosquare.company.adapter.out.persistence.JdbcCompanyResearchSummaryRepository;
import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.execution.domain.model.InvestmentPlan;
import io.macrosquare.institutional.adapter.out.persistence.JdbcInstitutionalFilingRepository;
import io.macrosquare.institutional.adapter.out.persistence.JdbcInstitutionalSecurityIdentityRepository;
import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalHolding;
import io.macrosquare.institutional.domain.model.InstitutionalManager;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;
import io.macrosquare.disclosure.adapter.out.persistence.JdbcDartRepository;
import io.macrosquare.disclosure.domain.model.DartCompany;
import io.macrosquare.disclosure.domain.model.DartDisclosure;
import io.macrosquare.disclosure.domain.model.DartEventType;
import io.macrosquare.disclosure.domain.model.DartFinancialMetric;
import io.macrosquare.notification.adapter.out.persistence.JdbcNotificationStateRepository;
import io.macrosquare.integrity.adapter.out.persistence.JdbcDataIntegrityEvidenceAdapter;
import io.macrosquare.integrity.adapter.out.notification.NotificationIntegrityIncidentAdapter;
import io.macrosquare.integrity.application.model.IntegrityIncidentTransition;
import io.macrosquare.integrity.domain.DataIntegrityReport;
import io.macrosquare.integrity.domain.DataIntegrityViolation;
import io.macrosquare.integrity.domain.IntegrityMetric;
import io.macrosquare.market.adapter.out.persistence.JdbcMarketObservationRepository;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketObservation;
import io.macrosquare.notification.application.model.NotificationState;
import io.macrosquare.notification.application.model.NotificationStateChange;
import io.macrosquare.notification.application.model.OutboundNotification;
import io.macrosquare.policy.adapter.out.persistence.JdbcPolicyAnalysisRepository;
import io.macrosquare.policy.adapter.out.persistence.JdbcPolicyCalibrationRepository;
import io.macrosquare.policy.domain.model.PolicyCalibrationObservation;
import io.macrosquare.policy.domain.model.PolicyDecisionDirection;
import io.macrosquare.policy.domain.model.PolicyDirection;
import io.macrosquare.policy.domain.model.PolicyDocument;
import io.macrosquare.policy.domain.model.PolicyDocumentAnalysis;
import io.macrosquare.policy.domain.model.PolicyDocumentType;
import io.macrosquare.policy.domain.model.PolicyEvidence;
import io.macrosquare.policy.domain.model.PolicyTone;
import io.macrosquare.research.adapter.out.persistence.JdbcPeerTaxonomyRepository;
import io.macrosquare.research.adapter.out.persistence.JdbcNarrativeSourceRepository;
import io.macrosquare.research.adapter.out.persistence.JdbcSectorMarketEvidenceRepository;
import io.macrosquare.research.adapter.out.persistence.JdbcSectorRotationValidationRepository;
import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.domain.peer.PeerTaxonomy;
import io.macrosquare.research.domain.narrative.NarrativeSourceQuality;
import io.macrosquare.research.domain.narrative.NarrativeSourceReading;
import io.macrosquare.research.domain.narrative.NarrativeSourceStatus;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import io.macrosquare.research.domain.rotation.SectorFundFlowEvidence;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthEvidence;
import io.macrosquare.research.domain.rotation.SectorRotationCompositeSnapshot;
import io.macrosquare.research.domain.rotation.SectorRotationHorizon;
import io.macrosquare.research.domain.rotation.SectorRotationLabel;
import io.macrosquare.research.domain.rotation.SectorRotationOutcome;
import io.macrosquare.research.domain.rotation.SectorRotationRegime;
import io.macrosquare.research.domain.rotation.SectorRotationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in test against an actual PostgreSQL server. The companion script always
 * creates a disposable database before enabling this class.
 */
@EnabledIfEnvironmentVariable(named = "MACROSQUARE_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class PostgresMultiInstanceIntegrationTest {

    private DataSource dataSource;
    private JdbcTemplate sql;

    @BeforeEach
    void resetOwnedTables() {
        dataSource = dataSource();
        sql = new JdbcTemplate(dataSource);
        sql.execute("truncate table notification.outbox, notification.candidate_snapshot, "
                + "notification.candidate_key, notification.delivery_state cascade");
        sql.execute("truncate table execution.trade_log, execution.tranche_entry, "
                + "execution.investment_plan restart identity cascade");
        sql.execute("truncate table institutional.holding, institutional.filing, "
                + "institutional.manager restart identity cascade");
        sql.execute("truncate table institutional.security_identity");
        sql.execute("truncate table policy.document_evidence, policy.document_analysis cascade");
        sql.execute("truncate table research.peer_taxonomy, research.peer_directory cascade");
        sql.execute("truncate table research.narrative_source_observation");
        sql.execute("truncate table research.sector_fund_flow_snapshot, research.sector_price_breadth_snapshot");
        sql.execute("truncate table research.sector_rotation_run cascade");
        sql.execute("truncate table disclosure.dart_financial_metric, disclosure.dart_filing, "
                + "disclosure.dart_company cascade");
        sql.execute("truncate table market.observation");
        sql.execute("truncate table market.collection_status");
        sql.execute("truncate table company.research_summary");
        sql.execute("truncate table company.analyst_snapshot, company.analyst_series_state cascade");
    }

    @Test
    void serializesAggregateMutationsAcrossTwoAdapterInstancesWithoutLostUpdates() throws Exception {
        var first = executionAdapter();
        var second = executionAdapter();
        var initial = InvestmentPlan.defaults(Instant.parse("2026-07-21T00:00:00Z"));
        first.save(initial);
        var operations = 40;
        var start = new CountDownLatch(1);

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var index = 0; index < operations; index++) {
                var adapter = index % 2 == 0 ? first : second;
                futures.add(workers.submit(() -> {
                    start.await();
                    adapter.updateAtomically(initial, PostgresMultiInstanceIntegrationTest::incrementDca);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }

        var actual = first.load().orElseThrow();
        assertEquals(initial.monthlyDcaKrw() + operations, actual.monthlyDcaKrw());
        assertEquals(operations + 1L, sql.queryForObject(
                "select version from execution.investment_plan where singleton_id = 1", Long.class));
    }

    @Test
    void aSessionAdvisoryLockExcludesTheSameTaskInAnotherInstance() throws Exception {
        var first = new PostgresAdvisoryTaskExecution(dataSource);
        var second = new PostgresAdvisoryTaskExecution(dataSource);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var worker = Executors.newSingleThreadExecutor()) {
            var owner = worker.submit(() -> first.execute("integration:exclusive-task", () -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertFalse(second.execute("integration:exclusive-task", () -> { }));
            release.countDown();
            assertTrue(owner.get(10, TimeUnit.SECONDS));
        }
        assertTrue(second.execute("integration:exclusive-task", () -> { }));
    }

    @Test
    void krxInvestorFlowRoundTripsThroughTheProductionSourceConstraint() {
        var repository = new JdbcMarketObservationRepository(new NamedParameterJdbcTemplate(dataSource));
        var observation = new MarketObservation(
                "KOSPI_FOREIGN_NET_1D", "NAVER_FINANCE:KOSPI:FOREIGN", 2_952,
                LocalDate.parse("2026-07-21"), MarketDataSource.KRX);

        assertEquals(1, repository.save(List.of(observation)));
        assertEquals(List.of(observation), repository.loadLatest(MarketDataSource.KRX));
        assertEquals(List.of(observation), repository.loadHistory(
                MarketDataSource.KRX, "KOSPI_FOREIGN_NET_1D"));
    }

    @Test
    void sectorMarketEvidenceRoundTripsWithProductionPostgresTemporalTypes() {
        var repository = new JdbcSectorMarketEvidenceRepository(new NamedParameterJdbcTemplate(dataSource));
        var observedOn = LocalDate.parse("2026-08-07");
        var collectedAt = Instant.parse("2026-08-08T03:04:05Z");
        var flow = new SectorFundFlowEvidence(
                observedOn, 140.25, 100_000_000, 14_025_000_000.0,
                125_000_000, 410_000_000, 930_000_000, 2.92, 6.63, 94);
        var breadth = new SectorPriceBreadthEvidence(
                observedOn, observedOn.minusDays(1), observedOn,
                40, 38, 31, 29, 24, 70);

        repository.saveFundFlow("SECTOR_XLK", "XLK", flow, collectedAt);
        repository.savePriceBreadth("SECTOR_XLK", breadth, collectedAt);

        var current = repository.loadCurrent("SECTOR_XLK", observedOn, 7);
        assertEquals(flow, current.fundFlow());
        assertEquals(breadth, current.priceBreadth());
        assertEquals(collectedAt, sql.queryForObject(
                "select collected_at from research.sector_fund_flow_snapshot where sector_key='SECTOR_XLK'",
                java.time.OffsetDateTime.class).toInstant());
    }

    @Test
    void appendsImmutableSectorRotationSnapshotsAndOutcomesIdempotently() {
        var jdbc = new NamedParameterJdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var repository = new JdbcSectorRotationValidationRepository(jdbc, transactions);
        var runId = UUID.randomUUID();
        // 운영 integrity 계약은 DB의 current_date 대비 7일 이내 run만 READY로 본다.
        // 고정 날짜를 쓰면 시간이 흐른 뒤 배포 preflight가 기능 회귀 없이 실패하므로
        // 데이터베이스 clock을 테스트 기준 시점으로 사용한다.
        var asOf = sql.queryForObject("select current_date", LocalDate.class);
        var priceAnchor = asOf.minusDays(1);
        var items = new ArrayList<SectorRotationCompositeSnapshot.Item>();
        var rank = 0;
        for (var key : List.of("XLK", "XLF", "XLE", "XLV", "XLI", "XLY", "XLC", "XLB", "XLRE", "XLU", "XLP")) {
            items.add(new SectorRotationCompositeSnapshot.Item(
                    "SECTOR_" + key, ++rank, 70, 71, 72, 73, 60, 65, 55, 62, 45,
                    SectorRotationState.IMPROVING, SectorRotationLabel.ROTATION_IN,
                    SectorRotationHorizon.ONE_TO_THREE_MONTHS,
                    priceAnchor.minusDays(1), priceAnchor, asOf, 80, asOf, asOf, 90));
        }
        var snapshot = new SectorRotationCompositeSnapshot(
                runId, Instant.now().minusSeconds(60), asOf, priceAnchor,
                "CURRENT_SECTOR_ROTATION_COMPOSITE_V3", SectorRotationRegime.MID_GROWTH,
                77, 11, 11, 11, asOf.minusDays(2), asOf, items);

        assertTrue(repository.append(snapshot));
        assertFalse(repository.append(snapshot));
        assertEquals(11, sql.queryForObject(
                "select count(*) from research.sector_rotation_item_snapshot", Integer.class));
        var integrity = new JdbcDataIntegrityEvidenceAdapter(
                new NamedParameterJdbcTemplate(dataSource), 5).load();
        assertEquals(1, integrity.metric(IntegrityMetric.CURRENT_SECTOR_ROTATION_READY_ROWS));
        assertEquals(0, integrity.metric(IntegrityMetric.INVALID_SECTOR_ROTATION_RUN_ROWS));
        assertEquals(3, repository.loadPendingWindows(10).size());
        assertEquals(priceAnchor, repository.loadPendingWindows(10).getFirst().priceAnchorOn());

        var outcomes = items.stream().map(item -> new SectorRotationOutcome(
                runId, item.sectorKey(), 21, priceAnchor, priceAnchor.plusDays(30), 7, 4, 5)).toList();
        assertEquals(11, repository.appendOutcomes(outcomes));
        assertEquals(0, repository.appendOutcomes(outcomes));
        assertEquals(2, repository.loadPendingWindows(10).size());
        assertThrows(DataAccessException.class, () -> sql.update(
                "update research.sector_rotation_item_snapshot set rotation_score=99"));
    }

    @Test
    void quarantineAndBuyEvidenceGuardsAreEnforcedByTheRealDatabase() {
        var repository = new JdbcCompanyResearchSummaryRepository(
                new NamedParameterJdbcTemplate(dataSource), new ObjectMapper());
        var now = Instant.parse("2026-08-07T08:00:00Z");
        var current = new CompanyResearchSummarySnapshot(
                "TEST", LocalDate.parse("2026-06-30"), 100_000_000_000.0,
                12.0, 25.0, 4.0,
                80, 75, 85, 77, 83,
                82, "매수 우호", 84, 25,
                "INDEPENDENT_MARKET_CAP", true, List.of(),
                "CURRENT", LocalDate.parse("2026-06-30"), LocalDate.parse("2026-07-25"),
                "10-Q", 0, List.of(),
                80, 76, 20, 85, "CONVICTION", "BUY", now
        );

        repository.save(current);
        assertThrows(DataIntegrityViolationException.class, () -> sql.update(
                "update company.research_summary set growth_score=null where ticker='TEST'"));
        sql.update("insert into company.analyst_series_state(ticker,updated_at) values ('TEST',clock_timestamp())");
        assertThrows(DataIntegrityViolationException.class, () -> sql.update("""
                insert into company.analyst_snapshot(ticker,observed_on,analyst_score,upside_pct)
                values ('TEST',current_date,1.0,1001.0)
                """));
        repository.save(current.quarantined("current source failed", now.plusSeconds(1)));

        var quarantined = repository.find("TEST").orElseThrow();
        assertEquals("UNAVAILABLE", quarantined.fundamentalsStatus());
        assertNull(quarantined.totalScore());
        assertNull(quarantined.buyScore());
        assertEquals("HOLD", quarantined.executionAction());
        assertThrows(DataIntegrityViolationException.class, () -> sql.update(
                "update company.research_summary set execution_action='BUY' where ticker='TEST'"));
    }

    @Test
    void integrityIncidentFingerprintRoundTripsThroughTheTransactionalStateAggregate() {
        var repository = notificationRepository();
        var now = Instant.parse("2026-08-07T08:00:00Z");
        var fingerprint = "a".repeat(64);

        repository.save(new NotificationState(Set.of(), "market", fingerprint, now, List.of()));

        assertEquals(fingerprint, repository.load().integrityFingerprint());
        assertEquals(fingerprint, sql.queryForObject(
                "select integrity_fingerprint from notification.delivery_state where channel='telegram'",
                String.class));
    }

    @Test
    void recurrenceAlertIsDurableDeduplicatedAndRearmedAfterRecovery() {
        var adapter = new NotificationIntegrityIncidentAdapter(notificationRepository());
        var at = Instant.parse("2026-08-07T08:00:00Z");
        var incident = new DataIntegrityReport(
                at,
                List.of(new DataIntegrityViolation(
                        "BUY_WITHOUT_EVIDENCE_ROWS", 1, 0,
                        "현재 근거 없이 생성된 BUY 액션")),
                List.of("YAHOO:FAILED:PRICE")
        );
        var healthy = new DataIntegrityReport(at.plusSeconds(60), List.of(), List.of());

        assertEquals(IntegrityIncidentTransition.NEW_ALERT,
                adapter.transition(incident, "alert", "recovered", at));
        assertEquals(IntegrityIncidentTransition.UNCHANGED_INCIDENT,
                adapter.transition(incident, "duplicate", "recovered", at.plusSeconds(10)));
        assertEquals(1, sql.queryForObject(
                "select count(*) from notification.outbox where operation='data-integrity-alert'",
                Integer.class));

        var changedFailureKey = new DataIntegrityReport(
                at.plusSeconds(30),
                incident.violations(),
                List.of("YAHOO:FAILED:USDJPY")
        );
        assertEquals(IntegrityIncidentTransition.NEW_ALERT,
                adapter.transition(changedFailureKey, "different-key", "recovered", at.plusSeconds(30)));
        assertEquals(2, sql.queryForObject(
                "select count(*) from notification.outbox where operation='data-integrity-alert'",
                Integer.class));

        assertEquals(IntegrityIncidentTransition.RECOVERED,
                adapter.transition(healthy, "alert", "recovered", at.plusSeconds(60)));
        assertEquals(1, sql.queryForObject(
                "select count(*) from notification.outbox where operation='data-integrity-recovery'",
                Integer.class));
        assertEquals(IntegrityIncidentTransition.NEW_ALERT,
                adapter.transition(incident, "alert-again", "recovered", at.plusSeconds(120)));
        assertEquals(3, sql.queryForObject(
                "select count(*) from notification.outbox where operation='data-integrity-alert'",
                Integer.class));
    }

    @Test
    void recurrenceEvidenceQueryRunsAgainstTheCompleteProductionSchema() {
        var now = Instant.parse("2026-08-07T08:00:00Z");
        var evidence = new JdbcDataIntegrityEvidenceAdapter(
                new NamedParameterJdbcTemplate(dataSource), 5, Clock.fixed(now, java.time.ZoneOffset.UTC)).load();

        assertEquals(0, evidence.metric(IntegrityMetric.COMPANY_UNIVERSE_ROWS));
        assertEquals(0, evidence.metric(IntegrityMetric.COMPANY_COMPARABLE_SCORE_ROWS));
        assertEquals(0, evidence.metric(IntegrityMetric.COMPANY_PRICE_SIGNAL_ROWS));
        assertEquals(0, evidence.metric(IntegrityMetric.INVALID_COMPANY_SCORE_ROWS));
        assertEquals(now, evidence.observedAt());
    }

    @Test
    void recurrenceEvidencePreservesProviderBoundariesWhenFailureKeysContainCommas() {
        sql.update("""
                insert into market.collection_status (
                    source, status, attempted_at, collected_count, persisted_count,
                    failure_keys, completed_at
                ) values
                    ('YAHOO', 'DEGRADED', clock_timestamp(), 1, 1, 'USDKRW,USDJPY', clock_timestamp()),
                    ('KRX', 'FAILED', clock_timestamp(), 0, 0, 'KOSPI', clock_timestamp())
                on conflict (source) do update set
                    status = excluded.status,
                    attempted_at = excluded.attempted_at,
                    collected_count = excluded.collected_count,
                    persisted_count = excluded.persisted_count,
                    failure_keys = excluded.failure_keys,
                    completed_at = excluded.completed_at
                """);
        var evidence = new JdbcDataIntegrityEvidenceAdapter(
                new NamedParameterJdbcTemplate(dataSource), 5).load();

        assertEquals(List.of("KRX:FAILED::KOSPI", "YAHOO:DEGRADED::USDKRW,USDJPY"),
                evidence.hardCollectionSources());
    }

    @Test
    void recurrenceEvidenceDoesNotAlertOnOneTransientFxGapWhileFreshPriorValuesRemain() {
        sql.update("""
                insert into market.observation (
                    source, series_key, provider_code, observed_on, value, collected_at
                ) values
                    ('YAHOO', 'USDKRW', 'KRW=X', current_date, 1388.4, clock_timestamp()),
                    ('YAHOO', 'USDJPY', 'JPY=X', current_date, 151.2, clock_timestamp())
                """);
        sql.update("""
                insert into market.collection_status (
                    source, status, attempted_at, collected_count, persisted_count,
                    failure_type, failure_keys, completed_at
                ) values (
                    'YAHOO', 'DEGRADED', clock_timestamp(), 88, 88,
                    'SOURCE_GAP', 'USDKRW,USDJPY', clock_timestamp()
                )
                """);
        var adapter = new JdbcDataIntegrityEvidenceAdapter(
                new NamedParameterJdbcTemplate(dataSource), 5);

        var freshFallback = adapter.load();

        assertEquals(0, freshFallback.metric(IntegrityMetric.HARD_COLLECTION_FAILURE_ROWS));
        assertEquals(List.of(), freshFallback.hardCollectionSources());

        sql.update("""
                update market.observation
                   set collected_at = clock_timestamp() - interval '2 hours'
                 where source = 'YAHOO' and series_key = 'USDJPY'
                """);

        var staleFallback = adapter.load();

        assertEquals(1, staleFallback.metric(IntegrityMetric.HARD_COLLECTION_FAILURE_ROWS));
        assertEquals(List.of("YAHOO:DEGRADED:SOURCE_GAP:USDKRW,USDJPY"),
                staleFallback.hardCollectionSources());
    }

    @Test
    void recurrenceEvidenceDistinguishesProviderPolicyLimitationFromSourceFailure() {
        sql.update("""
                insert into market.collection_status (
                    source, status, attempted_at, collected_count, persisted_count,
                    failure_type, failure_keys, completed_at
                ) values (
                    'SENTIMENT', 'DEGRADED', clock_timestamp(), 2, 2,
                    'PROVIDER_POLICY_UNAVAILABLE', 'NAAIM_EXPOSURE', clock_timestamp()
                )
                """);
        var adapter = new JdbcDataIntegrityEvidenceAdapter(
                new NamedParameterJdbcTemplate(dataSource), 5);

        assertEquals(0, adapter.load().metric(IntegrityMetric.HARD_COLLECTION_FAILURE_ROWS));

        sql.update("""
                update market.collection_status
                   set failure_type = 'SOURCE_GAP'
                 where source = 'SENTIMENT'
                """);

        assertEquals(1, adapter.load().metric(IntegrityMetric.HARD_COLLECTION_FAILURE_ROWS));
    }

    @Test
    void databaseRejectsAFalseSuccessfulCollectionWithMissingPersistence() {
        assertThrows(DataIntegrityViolationException.class, () -> sql.update("""
                insert into market.collection_status (
                    source, status, attempted_at, completed_at,
                    collected_count, persisted_count, failure_keys, failure_type
                ) values (
                    'YAHOO', 'SUCCESS', clock_timestamp(), clock_timestamp(),
                    3, 0, '', ''
                )
                """));
    }

    @Test
    void skipLockedClaimsEachOutboxMessageFromOnlyOneInstance() throws Exception {
        var first = notificationRepository();
        var second = notificationRepository();
        var now = Instant.parse("2026-07-21T03:00:00Z");
        var message = OutboundNotification.create("integration", "one-message", "payload", now);
        first.updateAtomically(previous -> NotificationStateChange.withNotification(
                new NotificationState(Set.of(), "fingerprint", now, List.of()), message, true));
        var start = new CountDownLatch(1);

        List<io.macrosquare.notification.application.model.ClaimedNotification> claimedA;
        List<io.macrosquare.notification.application.model.ClaimedNotification> claimedB;
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futureA = workers.submit(() -> {
                start.await();
                return first.claimPending("instance-a", 10, now, Duration.ofMinutes(5), 12);
            });
            var futureB = workers.submit(() -> {
                start.await();
                return second.claimPending("instance-b", 10, now, Duration.ofMinutes(5), 12);
            });
            start.countDown();
            claimedA = futureA.get(10, TimeUnit.SECONDS);
            claimedB = futureB.get(10, TimeUnit.SECONDS);
        }

        assertEquals(1, claimedA.size() + claimedB.size());
        var claimed = claimedA.isEmpty() ? claimedB.getFirst() : claimedA.getFirst();
        var owner = claimedA.isEmpty() ? "instance-b" : "instance-a";
        first.markDelivered(claimed.id(), owner, "telegram:1", now.plusSeconds(1));
        assertEquals("DELIVERED", sql.queryForObject(
                "select status from notification.outbox where id = ?", String.class, message.id()));
    }

    @Test
    void purgesOnlyExpiredTerminalOutboxRows() {
        var repository = notificationRepository();
        var old = Instant.parse("2026-05-01T00:00:00Z");
        var current = Instant.parse("2026-07-21T00:00:00Z");
        var delivered = OutboundNotification.create("integration", "old-delivered", "one", old);
        var dead = OutboundNotification.create("integration", "old-dead", "two", old.plusSeconds(2));
        var pending = OutboundNotification.create("integration", "current-pending", "three", current);

        enqueue(repository, delivered);
        var first = repository.claimPending("instance-a", 1, old, Duration.ofMinutes(5), 1).getFirst();
        repository.markDelivered(first.id(), "instance-a", "telegram:1", old.plusSeconds(1));
        enqueue(repository, dead);
        var second = repository.claimPending(
                "instance-a", 1, old.plusSeconds(2), Duration.ofMinutes(5), 1).getFirst();
        repository.markFailed(second.id(), "instance-a", old.plusSeconds(3), "rejected", true);
        enqueue(repository, pending);

        assertEquals(2, repository.purgeTerminalBefore(current.minus(Duration.ofDays(30))));
        assertEquals(1, sql.queryForObject("select count(*) from notification.outbox", Integer.class));
        assertEquals("PENDING", sql.queryForObject(
                "select status from notification.outbox", String.class));
    }

    private static void enqueue(
            JdbcNotificationStateRepository repository,
            OutboundNotification message
    ) {
        repository.updateAtomically(previous ->
                NotificationStateChange.withNotification(previous, message, true));
    }

    @Test
    void roundTripsNormalized13fAndPolicyEvidenceWithoutDatabaseBlobs() {
        var jdbc = new NamedParameterJdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var institutional = new JdbcInstitutionalFilingRepository(jdbc, transactions);
        var manager = new InstitutionalManager("integration", "Integration Fund", "0000000001");
        institutional.save(List.of(new InstitutionalFiling(
                manager,
                "0000000001-26-000001",
                LocalDate.parse("2026-05-15"),
                LocalDate.parse("2026-03-31"),
                "https://www.sec.gov/Archives/example.xml",
                "sec-filings/13f/0000000001/000000000126000001/example.xml",
                List.of(new InstitutionalHolding("037833100", "APPLE INC", "COM", "", 12_345_000, 50_000))
        )));

        var filings = institutional.loadLatestPerManager(2);
        assertEquals(1, filings.size());
        assertEquals(12_345_000, filings.getFirst().holdings().getFirst().valueUsd());
        assertTrue(institutional.latestCollectedAt(List.of(manager.cik())).isPresent());
        assertTrue(institutional.latestCollectedAt(List.of(manager.cik(), "0000000002")).isEmpty());
        assertThrows(DataIntegrityViolationException.class, () -> sql.update(
                "update institutional.holding set shares=0 where cusip='037833100'"));
        assertEquals(0, sql.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema in ('institutional', 'policy') and data_type = 'bytea'",
                Integer.class));

        var policy = new JdbcPolicyAnalysisRepository(jdbc, transactions);
        var document = new PolicyDocument(
                "monetary20260701a1", "Federal Reserve", "FOMC Statement",
                PolicyDocumentType.FOMC_STATEMENT, Instant.parse("2026-07-01T18:00:00Z"),
                "https://www.federalreserve.gov/newsevents/pressreleases/monetary20260701a.htm",
                "Raw text belongs in MinIO, not the relational projection.");
        policy.save(List.of(new PolicyDocumentAnalysis(
                document, PolicyTone.DOVISH, 55, 80, 6, 0,
                List.of(new PolicyEvidence("reduce the target range", PolicyDirection.DOVISH, 6, "excerpt")),
                "완화적 근거가 우세합니다."
        )));

        var analyses = policy.loadLatest(12);
        assertEquals(1, analyses.size());
        assertEquals(1, analyses.getFirst().evidence().size());
        assertEquals("", analyses.getFirst().document().text());
    }

    @Test
    void roundTripsPointInTimeIdentitiesPeersCalibrationAndDartProjections() {
        var jdbc = new NamedParameterJdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var now = Instant.parse("2026-07-21T00:00:00Z");

        var identities = new JdbcInstitutionalSecurityIdentityRepository(jdbc, transactions);
        identities.savePointInTime(List.of(new InstitutionalSecurityIdentity(
                "037833100", "AAPL", "0000320193", "APPLE INC", "technology",
                LocalDate.parse("2026-03-31"), null, 100, "INTEGRATION")));
        assertEquals("AAPL", identities.loadActiveOn(LocalDate.parse("2026-03-31"))
                .get("037833100").ticker());

        var peers = new JdbcPeerTaxonomyRepository(jdbc, transactions);
        peers.reconcileDirectory(
                List.of(new PeerUniverseCompany("AAPL", "0000320193", "Apple Inc.")),
                now, Duration.ofDays(30));
        peers.save(List.of(new PeerTaxonomy(
                "AAPL", "0000320193", "Apple Inc.", 3571, "Electronic Computers", "technology",
                LocalDate.parse("2026-07-21"), null)), now);
        assertEquals(3571, peers.findAsOf("AAPL", LocalDate.parse("2026-07-21")).sic());

        var narrative = new JdbcNarrativeSourceRepository(jdbc, transactions);
        var narrativeReading = new NarrativeSourceReading(
                NarrativeTheme.AI_POWER, "GOOGLE_NEWS_7D", "Google News 7D",
                LocalDate.parse("2026-07-21"), now, NarrativeSourceQuality.PUBLIC_FEED,
                NarrativeSourceStatus.AVAILABLE, 42d, 7d, "7D 42건",
                "https://news.google.com/search?q=AI", "a".repeat(64),
                "source-documents/narrative/google-news/ai-power/a.xml");
        assertEquals(1, narrative.save(List.of(narrativeReading)));
        assertEquals(0, narrative.save(List.of(narrativeReading)));
        assertEquals(1, narrative.save(List.of(new NarrativeSourceReading(
                narrativeReading.theme(), narrativeReading.sourceKey(), narrativeReading.label(),
                narrativeReading.observationDate(), now.plusSeconds(60), narrativeReading.quality(),
                narrativeReading.status(), 45d, 7d, "7D 45건", narrativeReading.sourceUrl(),
                "b".repeat(64), narrativeReading.rawObjectKey()))));
        var narrativeHistory = narrative.loadSince(LocalDate.parse("2026-07-01"));
        assertEquals(2, narrativeHistory.size());
        assertEquals(2, narrativeHistory.getFirst().revision());

        var policyDocument = new PolicyDocument(
                "calibration-20260721", "Federal Reserve", "FOMC Statement",
                PolicyDocumentType.FOMC_STATEMENT, now,
                "https://www.federalreserve.gov/newsevents/pressreleases/monetary20260721a.htm", "text");
        new JdbcPolicyAnalysisRepository(jdbc, transactions).save(List.of(new PolicyDocumentAnalysis(
                policyDocument, PolicyTone.NEUTRAL, 0, 60, 0, 0, List.of(), "중립입니다.")));
        var calibration = new JdbcPolicyCalibrationRepository(jdbc, transactions);
        calibration.save(List.of(new PolicyCalibrationObservation(
                policyDocument.id(), now, 60, 0, PolicyDecisionDirection.NEUTRAL, true)));
        assertEquals(1, calibration.loadChronological(10).size());

        var dart = new JdbcDartRepository(jdbc, transactions);
        assertNull(dart.loadSnapshot("005930", 10, 10).asOf());
        var company = new DartCompany(
                "00126380", "005930", "삼성전자", "Samsung Electronics",
                LocalDate.parse("2026-07-01"));
        dart.saveCompanies(List.of(company), now);
        dart.saveDisclosures(List.of(new DartDisclosure(
                "20260721000001", company.corpCode(), company.corpName(), "유상증자 결정", "삼성전자",
                LocalDate.parse("2026-07-21"), "", DartEventType.CAPITAL_ACTION,
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260721000001")), now);
        dart.saveFinancials(List.of(new DartFinancialMetric(
                company.corpCode(), 2025, "11011", "IS", "손익계산서", "ifrs-full_Revenue", "매출액",
                new BigDecimal("300000"), new BigDecimal("250000"), "KRW")), now);
        var snapshot = dart.loadSnapshot("005930", 10, 10);
        assertEquals("ready", snapshot.status());
        assertEquals(DartEventType.CAPITAL_ACTION, snapshot.disclosures().getFirst().eventType());
        assertEquals(new BigDecimal("300000.0000"), snapshot.financials().getFirst().currentAmount());

        assertEquals(0, sql.queryForObject(
                "select count(*) from information_schema.columns where table_schema in "
                        + "('institutional', 'policy', 'research', 'disclosure') and data_type = 'bytea'",
                Integer.class));
    }

    private JdbcInvestmentExecutionAdapter executionAdapter() {
        var jdbc = new NamedParameterJdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new JdbcInvestmentExecutionAdapter(jdbc, transactions, new ObjectMapper());
    }

    private JdbcNotificationStateRepository notificationRepository() {
        var jdbc = new NamedParameterJdbcTemplate(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new JdbcNotificationStateRepository(jdbc, transactions, new ObjectMapper());
    }

    private static DataSource dataSource() {
        var source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(System.getenv("MACROSQUARE_TEST_POSTGRES_URL"));
        source.setUsername(System.getenv().getOrDefault("MACROSQUARE_TEST_POSTGRES_USERNAME", "macrosquare"));
        source.setPassword(System.getenv().getOrDefault("MACROSQUARE_TEST_POSTGRES_PASSWORD", "integration-only"));
        return source;
    }

    private static InvestmentPlan incrementDca(InvestmentPlan current) {
        return new InvestmentPlan(
                current.horizon(),
                current.targetReturnAnnualPct(),
                current.maxDrawdownTolerancePct(),
                current.rebalanceIntervalDays(),
                current.leverageMaxPct(),
                current.profitTakeTargetPct(),
                current.stopLossPct(),
                current.monthlyDcaKrw() + 1,
                current.currentHoldings(),
                current.totalCapitalKrw(),
                current.totalCapitalUsd(),
                current.currentHoldingsUsd(),
                current.accountStartDate(),
                current.startingCapitalUsd(),
                current.startingCapitalKrw(),
                current.investmentExperienceYears(),
                current.accountType(),
                current.notes(),
                current.updatedAt().plusMillis(1)
        );
    }
}
