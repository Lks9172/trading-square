package io.macrosquare.institutional.adapter.out.persistence;

import io.macrosquare.institutional.application.port.out.InstitutionalFilingRepository;
import io.macrosquare.institutional.application.port.out.InstitutionalPersistenceException;
import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalHolding;
import io.macrosquare.institutional.domain.model.InstitutionalManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Date;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Transactional normalized 13F store; raw information-table XML remains in MinIO. */
public final class JdbcInstitutionalFilingRepository implements InstitutionalFilingRepository {

    private static final String UPSERT_MANAGER = """
            insert into institutional.manager (cik, manager_id, name, updated_at)
            values (:cik, :managerId, :name, clock_timestamp())
            on conflict (cik) do update set
                manager_id = excluded.manager_id,
                name = excluded.name,
                updated_at = excluded.updated_at
            """;
    private static final String UPSERT_FILING = """
            insert into institutional.filing (
                accession_number, manager_cik, filed_on, report_period, source_url, raw_object_key, collected_at
            ) values (
                :accession, :managerCik, :filedOn, :reportPeriod, :sourceUrl, :rawObjectKey, clock_timestamp()
            )
            on conflict (accession_number) do update set
                manager_cik = excluded.manager_cik,
                filed_on = excluded.filed_on,
                report_period = excluded.report_period,
                source_url = excluded.source_url,
                raw_object_key = excluded.raw_object_key,
                collected_at = excluded.collected_at
            """;
    private static final String DELETE_HOLDINGS =
            "delete from institutional.holding where accession_number = :accession";
    private static final String INSERT_HOLDING = """
            insert into institutional.holding (
                accession_number, cusip, issuer, title_class, put_call, value_usd, shares
            ) values (
                :accession, :cusip, :issuer, :titleClass, :putCall, :valueUsd, :shares
            )
            """;
    private static final String LATEST = """
            with ranked as (
                select f.*,
                       row_number() over (
                           partition by f.manager_cik
                           order by f.report_period desc, f.filed_on desc, f.accession_number desc
                       ) as rank_number
                from institutional.filing f
            )
            select m.manager_id, m.name as manager_name, m.cik,
                   f.accession_number, f.filed_on, f.report_period, f.source_url, f.raw_object_key,
                   h.cusip, h.issuer, h.title_class, h.put_call, h.value_usd, h.shares
            from ranked f
            join institutional.manager m on m.cik = f.manager_cik
            left join institutional.holding h on h.accession_number = f.accession_number
            where f.rank_number <= :filingLimit
            order by m.cik, f.report_period desc, f.accession_number, h.value_usd desc nulls last
            """;
    private static final String LATEST_COLLECTED_AT = """
            select manager_cik, max(collected_at) as latest_collected_at
            from institutional.filing
            where manager_cik in (:managerCiks)
            group by manager_cik
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcInstitutionalFilingRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public int save(List<InstitutionalFiling> filings) {
        if (filings == null || filings.isEmpty()) return 0;
        try {
            return transactions.execute(status -> {
                for (var filing : filings) saveOne(filing);
                return filings.size();
            });
        } catch (RuntimeException error) {
            throw new InstitutionalPersistenceException("Unable to persist SEC 13F filings", error);
        }
    }

    @Override
    public List<InstitutionalFiling> loadLatestPerManager(int filingLimit) {
        if (filingLimit < 1 || filingLimit > 8) throw new IllegalArgumentException("filingLimit must be between 1 and 8");
        try {
            var builders = new LinkedHashMap<String, FilingBuilder>();
            jdbc.query(LATEST, new MapSqlParameterSource("filingLimit", filingLimit), row -> {
                var accession = row.getString("accession_number");
                var managerId = row.getString("manager_id");
                var managerName = row.getString("manager_name");
                var managerCik = row.getString("cik");
                var filedOn = row.getObject("filed_on", java.time.LocalDate.class);
                var reportPeriod = row.getObject("report_period", java.time.LocalDate.class);
                var sourceUrl = row.getString("source_url");
                var rawObjectKey = row.getString("raw_object_key");
                var builder = builders.computeIfAbsent(accession, ignored -> new FilingBuilder(
                        new InstitutionalManager(managerId, managerName, managerCik),
                        accession,
                        filedOn,
                        reportPeriod,
                        sourceUrl,
                        rawObjectKey
                ));
                var cusip = row.getString("cusip");
                if (cusip != null) builder.holdings.add(new InstitutionalHolding(
                        cusip,
                        row.getString("issuer"),
                        row.getString("title_class"),
                        row.getString("put_call"),
                        row.getDouble("value_usd"),
                        row.getDouble("shares")
                ));
            });
            return builders.values().stream().map(FilingBuilder::build).toList();
        } catch (RuntimeException error) {
            throw new InstitutionalPersistenceException("Unable to load normalized SEC 13F filings", error);
        }
    }

    @Override
    public Optional<Instant> latestCollectedAt(List<String> managerCiks) {
        if (managerCiks == null || managerCiks.isEmpty()) return Optional.empty();
        try {
            var expected = managerCiks.stream().distinct().toList();
            var values = jdbc.query(
                    LATEST_COLLECTED_AT,
                    new MapSqlParameterSource("managerCiks", expected),
                    (row, index) -> row.getObject(
                            "latest_collected_at", java.time.OffsetDateTime.class).toInstant()
            );
            if (values.size() != expected.size()) return Optional.empty();
            return values.stream().min(Instant::compareTo);
        } catch (RuntimeException error) {
            throw new InstitutionalPersistenceException("Unable to load latest SEC 13F collection time", error);
        }
    }

    private void saveOne(InstitutionalFiling filing) {
        jdbc.update(UPSERT_MANAGER, new MapSqlParameterSource()
                .addValue("cik", filing.manager().cik())
                .addValue("managerId", filing.manager().id())
                .addValue("name", filing.manager().name()));
        jdbc.update(UPSERT_FILING, new MapSqlParameterSource()
                .addValue("accession", filing.accessionNumber())
                .addValue("managerCik", filing.manager().cik())
                .addValue("filedOn", Date.valueOf(filing.filedOn()))
                .addValue("reportPeriod", Date.valueOf(filing.reportPeriod()))
                .addValue("sourceUrl", filing.sourceUrl())
                .addValue("rawObjectKey", filing.rawObjectKey()));
        jdbc.update(DELETE_HOLDINGS, new MapSqlParameterSource("accession", filing.accessionNumber()));
        if (!filing.holdings().isEmpty()) {
            var parameters = filing.holdings().stream().map(value -> new MapSqlParameterSource()
                    .addValue("accession", filing.accessionNumber())
                    .addValue("cusip", value.cusip())
                    .addValue("issuer", value.issuer())
                    .addValue("titleClass", value.titleClass())
                    .addValue("putCall", value.putCall())
                    .addValue("valueUsd", value.valueUsd())
                    .addValue("shares", value.shares()))
                    .toArray(MapSqlParameterSource[]::new);
            jdbc.batchUpdate(INSERT_HOLDING, parameters);
        }
    }

    private static final class FilingBuilder {
        private final InstitutionalManager manager;
        private final String accession;
        private final java.time.LocalDate filedOn;
        private final java.time.LocalDate reportPeriod;
        private final String sourceUrl;
        private final String rawObjectKey;
        private final List<InstitutionalHolding> holdings = new ArrayList<>();

        private FilingBuilder(
                InstitutionalManager manager,
                String accession,
                java.time.LocalDate filedOn,
                java.time.LocalDate reportPeriod,
                String sourceUrl,
                String rawObjectKey
        ) {
            this.manager = manager;
            this.accession = accession;
            this.filedOn = filedOn;
            this.reportPeriod = reportPeriod;
            this.sourceUrl = sourceUrl;
            this.rawObjectKey = rawObjectKey;
        }

        private InstitutionalFiling build() {
            return new InstitutionalFiling(
                    manager, accession, filedOn, reportPeriod, sourceUrl, rawObjectKey, holdings);
        }
    }
}
