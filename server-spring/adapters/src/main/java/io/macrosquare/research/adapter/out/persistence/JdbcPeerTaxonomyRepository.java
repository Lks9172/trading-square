package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.application.model.PeerUniverseCompany;
import io.macrosquare.research.application.port.out.PeerTaxonomyRepository;
import io.macrosquare.research.domain.peer.PeerTaxonomy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcPeerTaxonomyRepository implements PeerTaxonomyRepository {

    private static final String UPSERT_DIRECTORY = """
            insert into research.peer_directory (ticker, cik, company_name, last_seen_at, retired_on)
            values (:ticker, :cik, :companyName, :lastSeenAt, null)
            on conflict (ticker) do update set
                cik = excluded.cik,
                company_name = excluded.company_name,
                last_seen_at = excluded.last_seen_at,
                retired_on = null
            """;
    private static final String RETIRE_DIRECTORY = """
            update research.peer_directory
               set retired_on = :retiredOn
             where last_seen_at < :cutoff and retired_on is null
            """;
    private static final String CLOSE_RETIRED_TAXONOMY = """
            update research.peer_taxonomy taxonomy
               set valid_to = directory.retired_on,
                   updated_at = clock_timestamp()
              from research.peer_directory directory
             where taxonomy.ticker = directory.ticker
               and taxonomy.valid_to is null
               and directory.retired_on is not null
               and directory.retired_on >= taxonomy.valid_from
            """;
    private static final String TOUCH_SAME = """
            update research.peer_taxonomy
               set cik = :cik, company_name = :companyName, sic_description = :sicDescription,
                   sector_key = :sectorKey, refreshed_at = :refreshedAt, updated_at = clock_timestamp()
             where ticker = :ticker and sic = :sic and valid_to is null
            """;
    private static final String CLOSE_CHANGED = """
            update research.peer_taxonomy
               set valid_to = :closeOn, updated_at = clock_timestamp()
             where ticker = :ticker and sic <> :sic and valid_to is null and valid_from < :validFrom
            """;
    private static final String UPSERT_TAXONOMY = """
            insert into research.peer_taxonomy (
                ticker, valid_from, valid_to, cik, company_name, sic, sic_description,
                sector_key, refreshed_at, updated_at
            ) values (
                :ticker, :validFrom, :validTo, :cik, :companyName, :sic, :sicDescription,
                :sectorKey, :refreshedAt, clock_timestamp()
            )
            on conflict (ticker, valid_from) do update set
                valid_to = excluded.valid_to, cik = excluded.cik, company_name = excluded.company_name,
                sic = excluded.sic, sic_description = excluded.sic_description,
                sector_key = excluded.sector_key, refreshed_at = excluded.refreshed_at,
                updated_at = excluded.updated_at
            """;
    private static final String REFRESH_TIMES = """
            select directory.ticker,
                   greatest(
                       coalesce(directory.taxonomy_checked_at, timestamp with time zone 'epoch'),
                       coalesce(max(taxonomy.refreshed_at), timestamp with time zone 'epoch')
                   ) as refreshed_at
              from research.peer_directory directory
              left join research.peer_taxonomy taxonomy on taxonomy.ticker = directory.ticker
             where directory.taxonomy_checked_at is not null or taxonomy.refreshed_at is not null
             group by directory.ticker, directory.taxonomy_checked_at
            """;
    private static final String MARK_CHECKED = """
            update research.peer_directory
               set taxonomy_checked_at = :checkedAt
             where ticker = :ticker
            """;
    private static final String AS_OF = """
            select ticker, cik, company_name, sic, sic_description, sector_key, valid_from, valid_to
            from research.peer_taxonomy
            where ticker = :ticker and valid_from <= :asOf and (valid_to is null or valid_to >= :asOf)
            order by valid_from desc limit 1
            """;
    private static final String CANDIDATES = """
            select ticker, cik, company_name, sic, sic_description, sector_key, valid_from, valid_to
            from research.peer_taxonomy
            where valid_from <= :asOf and (valid_to is null or valid_to >= :asOf)
              and (sic = :sic or (sic / 10) = :industryGroup or (sic / 100) = :majorGroup
                   or sector_key = :sectorKey)
            order by case when sic = :sic then 0
                          when (sic / 10) = :industryGroup then 1
                          when (sic / 100) = :majorGroup then 2 else 3 end,
                     ticker
            limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcPeerTaxonomyRepository(NamedParameterJdbcTemplate jdbc, TransactionOperations transactions) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public int save(List<PeerTaxonomy> taxonomies, Instant refreshedAt) {
        if (taxonomies == null || taxonomies.isEmpty()) return 0;
        return transactions.execute(status -> {
            var saved = 0;
            for (var value : taxonomies) {
                var parameters = parameters(value, refreshedAt);
                if (jdbc.update(TOUCH_SAME, parameters) == 0) {
                    jdbc.update(CLOSE_CHANGED, parameters.addValue(
                            "closeOn", Date.valueOf(value.validFrom().minusDays(1))));
                    jdbc.update(UPSERT_TAXONOMY, parameters);
                }
                saved++;
            }
            return saved;
        });
    }

    @Override
    public void reconcileDirectory(
            List<PeerUniverseCompany> universe,
            Instant observedAt,
            Duration missingGrace
    ) {
        transactions.executeWithoutResult(status -> {
            if (!universe.isEmpty()) {
                var parameters = universe.stream().map(value -> new MapSqlParameterSource()
                        .addValue("ticker", value.ticker())
                        .addValue("cik", value.cik())
                        .addValue("companyName", value.companyName())
                        .addValue("lastSeenAt", Timestamp.from(observedAt)))
                        .toArray(MapSqlParameterSource[]::new);
                jdbc.batchUpdate(UPSERT_DIRECTORY, parameters);
            }
            jdbc.update(RETIRE_DIRECTORY, new MapSqlParameterSource()
                    .addValue("cutoff", Timestamp.from(observedAt.minus(missingGrace)))
                    .addValue("retiredOn", Date.valueOf(LocalDate.ofInstant(observedAt, ZoneOffset.UTC))));
            jdbc.update(CLOSE_RETIRED_TAXONOMY, new MapSqlParameterSource());
        });
    }

    @Override
    public Map<String, Instant> loadRefreshTimes() {
        var result = new LinkedHashMap<String, Instant>();
        jdbc.query(REFRESH_TIMES, (org.springframework.jdbc.core.RowCallbackHandler) row -> result.put(
                row.getString("ticker"), row.getTimestamp("refreshed_at").toInstant()));
        return Map.copyOf(result);
    }

    @Override
    public void markChecked(List<String> tickers, Instant checkedAt) {
        if (tickers == null || tickers.isEmpty()) return;
        var parameters = tickers.stream().map(ticker -> new MapSqlParameterSource()
                        .addValue("ticker", ticker)
                        .addValue("checkedAt", Timestamp.from(checkedAt)))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(MARK_CHECKED, parameters);
    }

    @Override
    public PeerTaxonomy findAsOf(String ticker, LocalDate asOf) {
        var values = jdbc.query(AS_OF, new MapSqlParameterSource()
                .addValue("ticker", ticker).addValue("asOf", Date.valueOf(asOf)),
                (row, number) -> taxonomy(row));
        return values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public List<PeerTaxonomy> loadCandidates(PeerTaxonomy target, LocalDate asOf, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit is out of range");
        return jdbc.query(CANDIDATES, new MapSqlParameterSource()
                .addValue("asOf", Date.valueOf(asOf))
                .addValue("sic", target.sic())
                .addValue("industryGroup", target.sic() / 10)
                .addValue("majorGroup", target.sic() / 100)
                .addValue("sectorKey", target.sectorKey())
                .addValue("limit", limit), (row, number) -> taxonomy(row));
    }

    private static MapSqlParameterSource parameters(PeerTaxonomy value, Instant refreshedAt) {
        return new MapSqlParameterSource()
                .addValue("ticker", value.ticker()).addValue("cik", value.cik())
                .addValue("companyName", value.companyName()).addValue("sic", value.sic())
                .addValue("sicDescription", value.sicDescription()).addValue("sectorKey", value.sectorKey())
                .addValue("validFrom", Date.valueOf(value.validFrom()))
                .addValue("validTo", value.validTo() == null ? null : Date.valueOf(value.validTo()))
                .addValue("refreshedAt", Timestamp.from(refreshedAt));
    }

    private static PeerTaxonomy taxonomy(java.sql.ResultSet row) throws java.sql.SQLException {
        return new PeerTaxonomy(
                row.getString("ticker"), row.getString("cik"), row.getString("company_name"),
                row.getInt("sic"), row.getString("sic_description"), row.getString("sector_key"),
                row.getObject("valid_from", LocalDate.class), row.getObject("valid_to", LocalDate.class));
    }
}
