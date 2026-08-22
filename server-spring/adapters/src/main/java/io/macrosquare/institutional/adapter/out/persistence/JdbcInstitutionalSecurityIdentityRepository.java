package io.macrosquare.institutional.adapter.out.persistence;

import io.macrosquare.institutional.application.port.out.InstitutionalPersistenceException;
import io.macrosquare.institutional.application.port.out.InstitutionalSecurityIdentityRepository;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** PostgreSQL-owned bitemporal-enough identity history for delayed quarterly 13F reports. */
public final class JdbcInstitutionalSecurityIdentityRepository
        implements InstitutionalSecurityIdentityRepository {

    private static final String TOUCH_SAME = """
            update institutional.security_identity
               set issuer = :issuer,
                   cik = :cik,
                   sector_key = case when :sectorKey = '' then sector_key else :sectorKey end,
                   confidence = greatest(confidence, :confidence),
                   last_seen_on = greatest(last_seen_on, :validFrom),
                   updated_at = clock_timestamp()
             where cusip = :cusip and ticker = :ticker and valid_to is null
            """;
    private static final String CLOSE_REPLACED = """
            update institutional.security_identity
               set valid_to = :validTo, updated_at = clock_timestamp()
             where cusip = :cusip and ticker <> :ticker and valid_to is null and valid_from < :validFrom
            """;
    private static final String UPSERT = """
            insert into institutional.security_identity (
                cusip, ticker, cik, issuer, sector_key, valid_from, valid_to,
                confidence, source, last_seen_on, updated_at
            ) values (
                :cusip, :ticker, :cik, :issuer, :sectorKey, :validFrom, :validTo,
                :confidence, :source, :validFrom, clock_timestamp()
            )
            on conflict (cusip, valid_from) do update set
                ticker = excluded.ticker,
                cik = excluded.cik,
                issuer = excluded.issuer,
                sector_key = excluded.sector_key,
                valid_to = excluded.valid_to,
                confidence = excluded.confidence,
                source = excluded.source,
                last_seen_on = greatest(institutional.security_identity.last_seen_on, excluded.last_seen_on),
                updated_at = excluded.updated_at
            """;
    private static final String ACTIVE_ON = """
            with ranked as (
                select *, row_number() over (
                    partition by cusip order by valid_from desc, confidence desc, ticker
                ) as rank_number
                from institutional.security_identity
                where valid_from <= :reportPeriod
                  and (valid_to is null or valid_to >= :reportPeriod)
            )
            select cusip, ticker, cik, issuer, sector_key, valid_from, valid_to, confidence, source
            from ranked where rank_number = 1
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcInstitutionalSecurityIdentityRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public int savePointInTime(List<InstitutionalSecurityIdentity> identities) {
        if (identities == null || identities.isEmpty()) return 0;
        try {
            return transactions.execute(status -> {
                var saved = 0;
                for (var identity : identities) {
                    var parameters = parameters(identity);
                    if (jdbc.update(TOUCH_SAME, parameters) == 0) {
                        jdbc.update(CLOSE_REPLACED, parameters.addValue(
                                "validTo", Date.valueOf(identity.validFrom().minusDays(1))));
                        jdbc.update(UPSERT, parameters.addValue("validTo", identity.validTo() == null
                                ? null : Date.valueOf(identity.validTo())));
                    }
                    saved++;
                }
                return saved;
            });
        } catch (RuntimeException error) {
            throw new InstitutionalPersistenceException("Unable to persist point-in-time CUSIP identities", error);
        }
    }

    @Override
    public Map<String, InstitutionalSecurityIdentity> loadActiveOn(LocalDate reportPeriod) {
        Objects.requireNonNull(reportPeriod, "reportPeriod");
        try {
            var result = new LinkedHashMap<String, InstitutionalSecurityIdentity>();
            jdbc.query(ACTIVE_ON, new MapSqlParameterSource(
                    "reportPeriod", Date.valueOf(reportPeriod)), row -> {
                var identity = new InstitutionalSecurityIdentity(
                        row.getString("cusip"), row.getString("ticker"), row.getString("cik"),
                        row.getString("issuer"), row.getString("sector_key"),
                        row.getObject("valid_from", LocalDate.class),
                        row.getObject("valid_to", LocalDate.class), row.getInt("confidence"),
                        row.getString("source"));
                result.put(identity.cusip(), identity);
            });
            return Map.copyOf(result);
        } catch (RuntimeException error) {
            throw new InstitutionalPersistenceException("Unable to load point-in-time CUSIP identities", error);
        }
    }

    private static MapSqlParameterSource parameters(InstitutionalSecurityIdentity value) {
        return new MapSqlParameterSource()
                .addValue("cusip", value.cusip())
                .addValue("ticker", value.ticker())
                .addValue("cik", value.cik())
                .addValue("issuer", value.issuer())
                .addValue("sectorKey", value.sectorKey())
                .addValue("validFrom", Date.valueOf(value.validFrom()))
                .addValue("confidence", value.confidence())
                .addValue("source", value.source());
    }
}
