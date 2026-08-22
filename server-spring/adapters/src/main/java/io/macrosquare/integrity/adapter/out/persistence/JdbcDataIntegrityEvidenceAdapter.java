package io.macrosquare.integrity.adapter.out.persistence;

import io.macrosquare.integrity.application.port.out.LoadDataIntegrityEvidencePort;
import io.macrosquare.integrity.domain.DataIntegrityEvidence;
import io.macrosquare.integrity.domain.IntegrityMetric;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/** Read-only PostgreSQL projection of all previously observed critical incident classes. */
public final class JdbcDataIntegrityEvidenceAdapter implements LoadDataIntegrityEvidencePort {

    private static final String SQL = """
            with sector_keys(series_key) as (
                values ('XLK'), ('XLF'), ('XLE'), ('XLV'), ('XLI'), ('XLY'), ('XLC'), ('XLB'),
                       ('XLRE'), ('XLU'), ('XLP'), ('SOXX'), ('SMH'), ('ITA'), ('GRID'), ('IGF')
            ), sector_total_return_keys(series_key) as (
                values ('XLK_TR'), ('XLF_TR'), ('XLE_TR'), ('XLV_TR'), ('XLI_TR'), ('XLY_TR'),
                       ('XLC_TR'), ('XLB_TR'), ('XLRE_TR'), ('XLU_TR'), ('XLP_TR'), ('SOXX_TR'),
                       ('SMH_TR'), ('ITA_TR'), ('GRID_TR'), ('IGF_TR')
            ), filing_groups as (
                select m.manager_id, f.report_period, count(*)::bigint position_count,
                       percentile_cont(0.9) within group(
                           order by h.value_usd / nullif(h.shares, 0)
                       ) as implied_price_p90,
                       count(*) filter(where h.value_usd / nullif(h.shares, 0) < 1)::bigint below_one
                from institutional.manager m
                join institutional.filing f on f.manager_cik = m.cik
                join institutional.holding h on h.accession_number = f.accession_number
                group by m.manager_id, f.report_period
            ), company_metrics as (
                select count(*)::bigint company_universe_rows,
                       count(*) filter(where calculation_version = :calculationVersion)::bigint
                           company_current_calculation_rows,
                       count(*) filter(where total_score is not null)::bigint
                           company_comparable_score_rows,
                       count(*) filter(where price_bottom_score is not null)::bigint
                           company_price_signal_rows,
                       count(*) filter(where
                           total_score not between 0 and 100
                           or growth_score not between 0 and 100
                           or quality_score not between 0 and 100
                           or valuation_score not between 0 and 100
                           or balance_sheet_score not between 0 and 100
                           or buy_score not between 0 and 100
                           or appeal_score not between 0 and 100
                           or crowding_score not between 0 and 100
                           or price_bottom_score not between 0 and 100
                           or volume_confirmation_score not between 0 and 100
                           or failure_risk_score not between 0 and 100
                           or confirmed_bottom_score not between 0 and 100
                       )::bigint invalid_company_score_rows,
                       count(*) filter(where
                           num_nonnulls(total_score, growth_score, quality_score, valuation_score,
                               balance_sheet_score, buy_score, appeal_score, crowding_score) not in (0, 8)
                           or ((total_score is null) <> (buy_label is null))
                           or (total_score is not null and (
                               fundamentals_status <> 'CURRENT' or not valuation_eligible
                           ))
                       )::bigint incomplete_company_score_rows,
                       count(*) filter(where fundamentals_as_of > current_date
                           or latest_periodic_report_date > current_date
                           or latest_periodic_filing_date > current_date
                           or updated_at > clock_timestamp() + interval '5 minutes')::bigint
                           future_company_date_rows,
                       count(*) filter(where fundamentals_status <> 'CURRENT'
                           and (total_score is not null or buy_score is not null))::bigint
                           noncurrent_scored_rows,
                       count(*) filter(where execution_action in ('BUY', 'STRONG BUY') and (
                           fundamentals_status <> 'CURRENT'
                           or not valuation_eligible
                           or total_score is null or growth_score is null or quality_score is null
                           or valuation_score is null or balance_sheet_score is null or buy_score is null
                           or price_bottom_score is null or volume_confirmation_score is null
                           or failure_risk_score is null or confirmed_bottom_score is null
                           or confirmed_bottom_state is null
                       ))::bigint buy_without_evidence_rows,
                       count(*) filter(where num_nonnulls(
                           price_bottom_score, volume_confirmation_score, failure_risk_score,
                           confirmed_bottom_score, confirmed_bottom_state
                       ) not in (0, 5))::bigint incomplete_price_signal_rows,
                       count(*) filter(where fundamentals_status in ('UNAVAILABLE', 'UNKNOWN', 'PENDING'))::bigint
                           unavailable_company_rows,
                       count(*) filter(where ticker in ('EA', 'CTRA', 'MMC'))::bigint
                           retired_or_alias_company_rows,
                       count(*) filter(where ticker = 'MRSH')::bigint canonical_mrsh_rows,
                       min(updated_at) oldest_company_summary_at
                from company.research_summary
            ), collection_classification as (
                select status.source, status.status, status.collected_count, status.persisted_count,
                       status.failure_type, status.failure_keys, status.completed_at,
                       (
                           status.source = 'SENTIMENT'
                           and status.failure_type = 'PROVIDER_POLICY_UNAVAILABLE'
                           and regexp_replace(status.failure_keys, '\\s', '', 'g') = 'NAAIM_EXPOSURE'
                           and status.collected_count > 0 and status.persisted_count > 0
                       ) or (
                           status.source = 'YAHOO'
                           and status.collected_count > 0 and status.persisted_count > 0
                           and regexp_replace(status.failure_keys, '\\s', '', 'g')
                               ~ '^(USDJPY|USDKRW)(,(USDJPY|USDKRW))*$'
                           and coalesce((
                               select bool_and(exists(
                                   select 1
                                   from market.observation observation
                                   where observation.source = 'YAHOO'
                                     and observation.series_key = failed.series_key
                                     and observation.collected_at >= clock_timestamp() - interval '30 minutes'
                               ))
                               from regexp_split_to_table(
                                   regexp_replace(status.failure_keys, '\\s', '', 'g'), ','
                               ) as failed(series_key)
                           ), false)
                       ) acceptable_degraded
                from market.collection_status status
            ), collection_metrics as (
                select count(*)::bigint market_collection_status_rows,
                       count(*) filter(where
                           status = 'FAILED'
                           or persisted_count <> collected_count
                           or (status = 'SUCCESS' and (
                               btrim(failure_keys) <> '' or btrim(failure_type) <> ''
                           ))
                           or (status = 'DEGRADED' and not acceptable_degraded)
                       )::bigint hard_collection_failure_rows,
                       count(*) filter(where completed_at < clock_timestamp() - case source
                           when 'YAHOO' then interval '30 minutes'
                           when 'KRX' then interval '90 minutes'
                           when 'FEAR_GREED' then interval '3 hours'
                           else interval '12 hours'
                       end)::bigint stale_collection_rows,
                       -- failure_keys is itself comma separated. A comma between
                       -- source rows therefore loses the provider/key boundary
                       -- and can make two overlapping source failures share the
                       -- wrong recurrence fingerprint. ASCII unit separator is
                       -- outside the persisted failure-key alphabet.
                       coalesce(string_agg(
                           source || ':' || status || ':' || failure_type || ':' || failure_keys,
                           chr(31) order by source)
                           filter(where
                               status = 'FAILED'
                               or persisted_count <> collected_count
                               or (status = 'SUCCESS' and (
                                   btrim(failure_keys) <> '' or btrim(failure_type) <> ''
                               ))
                               or (status = 'DEGRADED' and not acceptable_degraded)
                           ), '') hard_collection_sources
                from collection_classification
            ), market_metrics as (
                select count(*) filter(where observed_on > current_date
                           or collected_at > clock_timestamp() + interval '5 minutes')::bigint future_market_rows,
                       count(*) filter(where value in (
                           'NaN'::double precision, 'Infinity'::double precision,
                           '-Infinity'::double precision))::bigint nonfinite_market_rows,
                       (count(*) - count(distinct (source, series_key, observed_on)))::bigint duplicate_market_rows
                from market.observation
            ), sector_series as (
                select k.series_key,
                       count(o.series_key)::bigint observation_count,
                       max(o.observed_on) latest_observed_on
                from sector_keys k
                left join market.observation o
                    on o.source = 'YAHOO' and o.series_key = k.series_key
                group by k.series_key
            ), benchmark_series as (
                select count(*)::bigint observation_count,
                       max(observed_on) latest_observed_on
                from market.observation
                where source = 'YAHOO' and series_key = 'SP500'
            ), sector_total_return_series as (
                select k.series_key,
                       count(o.series_key)::bigint observation_count,
                       max(o.observed_on) latest_observed_on
                from sector_total_return_keys k
                left join market.observation o
                    on o.source = 'YAHOO' and o.series_key = k.series_key
                group by k.series_key
            ), total_return_benchmark_series as (
                select count(*)::bigint observation_count,
                       max(observed_on) latest_observed_on
                from market.observation
                where source = 'YAHOO' and series_key = 'SPY_TR'
            ), recent_sector_prices as (
                select series_key, observed_on, value,
                       lag(value) over(partition by series_key order by observed_on) previous_value
                from market.observation
                where source = 'YAHOO'
                  and series_key in (select series_key from sector_keys)
                  and observed_on >= current_date - 45
            ), recent_sector_total_returns as (
                select series_key, observed_on, value,
                       lag(value) over(partition by series_key order by observed_on) previous_value
                from market.observation
                where source = 'YAHOO'
                  and series_key in (select series_key from sector_total_return_keys)
                  -- Include observations before the 45-day rolling refresh
                  -- boundary so a mixed dividend-adjustment basis is visible.
                  and observed_on >= current_date - 60
            ), sector_metrics as (
                select count(*) filter(where s.observation_count > 0)::bigint sector_price_series_rows,
                       count(*) filter(where s.observation_count >= 253)::bigint sector_history_ready_rows,
                       case when max(b.observation_count) >= 253
                                  and max(b.latest_observed_on) >= current_date - 7
                            then 1 else 0 end::bigint sector_benchmark_ready_rows,
                       count(*) filter(where s.latest_observed_on is null
                           or s.latest_observed_on < current_date - 7)::bigint stale_sector_price_rows,
                       count(*) filter(where s.latest_observed_on is distinct from b.latest_observed_on)::bigint
                           misaligned_sector_price_rows,
                       (select count(*) from recent_sector_prices
                           where previous_value > 0 and abs(value / previous_value - 1) > 0.45)::bigint
                           sector_price_discontinuity_rows,
                       (select count(*) from sector_total_return_series
                           where observation_count > 0)::bigint sector_total_return_series_rows,
                       (select count(*) from sector_total_return_series
                           where observation_count >= 2000)::bigint sector_total_return_history_ready_rows,
                       case when max(tb.observation_count) >= 2000
                                  and max(tb.latest_observed_on) >= current_date - 7
                            then 1 else 0 end::bigint sector_total_return_benchmark_ready_rows,
                       (select count(*) from sector_total_return_series
                           where latest_observed_on is null
                              or latest_observed_on < current_date - 7)::bigint stale_sector_total_return_rows,
                       (select count(*)
                          from sector_total_return_series tr
                          cross join total_return_benchmark_series benchmark
                         where tr.latest_observed_on is distinct from benchmark.latest_observed_on)::bigint
                           misaligned_sector_total_return_rows,
                       (select count(*) from recent_sector_total_returns
                           where previous_value > 0 and abs(value / previous_value - 1) > 0.45)::bigint
                           sector_total_return_discontinuity_rows
                from sector_series s
                cross join benchmark_series b
                cross join total_return_benchmark_series tb
            ), rotation_run_quality as (
                select r.run_id, r.price_anchor_on, r.calculated_at, r.universe_size,
                       count(i.run_id)::bigint item_count,
                       count(distinct i.sector_key)::bigint distinct_sector_count,
                       count(distinct i.rank)::bigint distinct_rank_count
                from research.sector_rotation_run r
                left join research.sector_rotation_item_snapshot i on i.run_id = r.run_id
                where r.methodology_version = 'CURRENT_SECTOR_ROTATION_COMPOSITE_V3'
                group by r.run_id, r.price_anchor_on, r.calculated_at, r.universe_size
            ), rotation_ledger_metrics as (
                select case when exists (
                           select 1 from rotation_run_quality
                           where price_anchor_on >= current_date - 7
                             and calculated_at <= clock_timestamp() + interval '5 minutes'
                             and universe_size = 11 and item_count = 11
                             and distinct_sector_count = 11 and distinct_rank_count = 11
                       ) then 1 else 0 end::bigint current_sector_rotation_ready_rows,
                       count(*) filter(where universe_size <> 11 or item_count <> 11
                           or distinct_sector_count <> 11 or distinct_rank_count <> 11
                           or price_anchor_on > calculated_at::date)::bigint invalid_sector_rotation_run_rows
                from rotation_run_quality
            ), latest_analyst as (
                select distinct on (ticker) ticker, analyst_score, upside_pct
                from company.analyst_snapshot
                order by ticker, observed_on desc
            ), analyst_metrics as (
                select count(*) filter(where observed_on > current_date
                           or collected_at > clock_timestamp() + interval '5 minutes')::bigint future_analyst_rows,
                       count(*) filter(where analyst_score not between -2 and 2
                           or analyst_score in ('NaN'::double precision, 'Infinity'::double precision,
                               '-Infinity'::double precision)
                           or upside_pct < -100 or upside_pct > 1000
                           or upside_pct in ('NaN'::double precision, 'Infinity'::double precision,
                               '-Infinity'::double precision))::bigint invalid_analyst_rows,
                       (count(*) - count(distinct (ticker, observed_on)))::bigint duplicate_analyst_rows,
                       (select count(*) from company.analyst_series_state)::bigint analyst_series_rows,
                       (select count(*) from company.analyst_series_state where
                           updated_at > clock_timestamp() + interval '5 minutes'
                           or updated_at < clock_timestamp() - interval '2 hours'
                       )::bigint stale_analyst_series_rows,
                       (select count(*) from latest_analyst
                           where analyst_score is null and upside_pct is null
                       )::bigint empty_latest_analyst_rows
                from company.analyst_snapshot
            ), institutional_metrics as (
                select (select count(*) from institutional.holding where value_usd <= 0 or shares <= 0
                           or value_usd in ('NaN'::double precision, 'Infinity'::double precision,
                               '-Infinity'::double precision)
                           or shares in ('NaN'::double precision, 'Infinity'::double precision,
                               '-Infinity'::double precision))::bigint invalid_13f_holding_rows,
                       (select count(*) from institutional.filing where filed_on > current_date
                           or report_period > current_date or report_period > filed_on)::bigint invalid_13f_date_rows,
                       (select count(*) from filing_groups where position_count >= 5
                           and implied_price_p90 < 1 and below_one * 10 >= position_count * 9)::bigint
                           suspicious_13f_unit_groups
            ), operational_metrics as (
                select (select count(*) from storage.object_pointer p
                           left join storage.object_artifact a on a.id = p.artifact_id
                           where a.id is null)::bigint dangling_object_pointer_rows,
                       (select count(*) from notification.candidate_snapshot c
                           left join company.research_summary r on c.kind = 'COMPANY' and r.ticker = c.symbol
                           where c.kind = 'COMPANY' and (
                               r.ticker is null or r.fundamentals_status <> 'CURRENT'
                               or r.total_score is distinct from c.total_score
                               or r.buy_score is distinct from c.buy_score
                               or r.execution_action is distinct from c.action
                               or r.confirmed_bottom_state is distinct from c.bottom_state
                               or r.confirmed_bottom_score is distinct from c.bottom_score
                           ))::bigint candidate_drift_rows,
                       (select count(*) from notification.outbox where status = 'RETRY')::bigint outbox_retry_rows,
                       (select count(*) from notification.outbox where status = 'DEAD')::bigint outbox_dead_rows,
                       (select count(*) from notification.outbox where
                           (status = 'IN_FLIGHT' and leased_until <= clock_timestamp())
                           or (status = 'PENDING' and created_at < clock_timestamp() - interval '10 minutes')
                       )::bigint outbox_stuck_rows
            )
            select * from company_metrics
            cross join collection_metrics
            cross join market_metrics
            cross join sector_metrics
            cross join rotation_ledger_metrics
            cross join analyst_metrics
            cross join institutional_metrics
            cross join operational_metrics
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final int calculationVersion;
    private final Clock clock;

    public JdbcDataIntegrityEvidenceAdapter(
            NamedParameterJdbcTemplate jdbc,
            int calculationVersion
    ) {
        this(jdbc, calculationVersion, Clock.systemUTC());
    }

    public JdbcDataIntegrityEvidenceAdapter(
            NamedParameterJdbcTemplate jdbc,
            int calculationVersion,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        if (calculationVersion < 1) throw new IllegalArgumentException("calculationVersion must be positive");
        this.calculationVersion = calculationVersion;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public DataIntegrityEvidence load() {
        return Objects.requireNonNull(jdbc.queryForObject(
                SQL,
                new MapSqlParameterSource("calculationVersion", calculationVersion),
                this::map
        ), "data integrity evidence");
    }

    private DataIntegrityEvidence map(ResultSet row, int ignored) throws SQLException {
        var metrics = new EnumMap<IntegrityMetric, Long>(IntegrityMetric.class);
        for (var metric : IntegrityMetric.values()) {
            metrics.put(metric, row.getLong(column(metric)));
        }
        var oldest = row.getObject("oldest_company_summary_at", java.time.OffsetDateTime.class);
        var sources = row.getString("hard_collection_sources");
        return new DataIntegrityEvidence(
                metrics,
                oldest == null ? null : oldest.toInstant(),
                clock.instant(),
                sources == null || sources.isBlank()
                        ? List.of()
                        : java.util.Arrays.stream(sources.split(String.valueOf((char) 31), -1))
                                .map(String::trim)
                                .filter(value -> !value.isBlank())
                                .toList()
        );
    }

    private static String column(IntegrityMetric metric) {
        return metric.name().toLowerCase(java.util.Locale.ROOT);
    }
}
