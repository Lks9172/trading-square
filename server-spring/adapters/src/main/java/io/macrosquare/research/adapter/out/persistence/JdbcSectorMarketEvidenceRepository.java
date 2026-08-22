package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.application.model.CurrentSectorMarketEvidence;
import io.macrosquare.research.application.port.out.SectorMarketEvidenceRepository;
import io.macrosquare.research.domain.rotation.SectorFundFlowEvidence;
import io.macrosquare.research.domain.rotation.SectorPriceBreadthEvidence;
import io.macrosquare.shared.adapter.out.persistence.PostgresTemporal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/** PostgreSQL operational projection for current sector fund-flow and price breadth. */
public final class JdbcSectorMarketEvidenceRepository implements SectorMarketEvidenceRepository {

    private static final String FUND_PROVIDER = "STATE_STREET_NAV_HISTORY";
    private static final String BREADTH_PROVIDER = "YAHOO_TRACKED_CONSTITUENTS";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSectorMarketEvidenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public void saveFundFlow(
            String sectorKey,
            String fundTicker,
            SectorFundFlowEvidence evidence,
            Instant collectedAt
    ) {
        Objects.requireNonNull(evidence);
        jdbc.update("""
                insert into research.sector_fund_flow_snapshot (
                    sector_key, fund_ticker, observed_on, nav, shares_outstanding,
                    total_net_assets, flow_1d_usd, flow_5d_usd, flow_20d_usd,
                    flow_5d_pct, flow_20d_pct, flow_score, provider, collected_at
                ) values (
                    :sectorKey, :fundTicker, :observedOn, :nav, :sharesOutstanding,
                    :totalNetAssets, :flow1dUsd, :flow5dUsd, :flow20dUsd,
                    :flow5dPct, :flow20dPct, :flowScore, :provider, :collectedAt
                )
                on conflict (sector_key, observed_on) do update set
                    fund_ticker = excluded.fund_ticker,
                    nav = excluded.nav,
                    shares_outstanding = excluded.shares_outstanding,
                    total_net_assets = excluded.total_net_assets,
                    flow_1d_usd = excluded.flow_1d_usd,
                    flow_5d_usd = excluded.flow_5d_usd,
                    flow_20d_usd = excluded.flow_20d_usd,
                    flow_5d_pct = excluded.flow_5d_pct,
                    flow_20d_pct = excluded.flow_20d_pct,
                    flow_score = excluded.flow_score,
                    provider = excluded.provider,
                    collected_at = excluded.collected_at
                """, new MapSqlParameterSource()
                .addValue("sectorKey", normalizeSectorKey(sectorKey))
                .addValue("fundTicker", normalizeFundTicker(fundTicker))
                .addValue("observedOn", evidence.observedOn())
                .addValue("nav", evidence.nav())
                .addValue("sharesOutstanding", evidence.sharesOutstanding())
                .addValue("totalNetAssets", evidence.totalNetAssets())
                .addValue("flow1dUsd", evidence.flow1dUsd())
                .addValue("flow5dUsd", evidence.flow5dUsd())
                .addValue("flow20dUsd", evidence.flow20dUsd())
                .addValue("flow5dPct", evidence.flow5dPct())
                .addValue("flow20dPct", evidence.flow20dPct())
                .addValue("flowScore", evidence.score())
                .addValue("provider", FUND_PROVIDER)
                .addValue("collectedAt", PostgresTemporal.timestamp(Objects.requireNonNull(collectedAt))));
    }

    @Override
    public void savePriceBreadth(String sectorKey, SectorPriceBreadthEvidence evidence, Instant collectedAt) {
        Objects.requireNonNull(evidence);
        jdbc.update("""
                insert into research.sector_price_breadth_snapshot (
                    sector_key, observed_on, oldest_component_on, latest_component_on,
                    constituent_count, covered_count, above_ma20_count, above_ma50_count,
                    above_ma200_count, breadth_score, provider, collected_at
                ) values (
                    :sectorKey, :observedOn, :oldestComponentOn, :latestComponentOn,
                    :constituentCount, :coveredCount, :aboveMa20Count, :aboveMa50Count,
                    :aboveMa200Count, :breadthScore, :provider, :collectedAt
                )
                on conflict (sector_key, observed_on) do update set
                    oldest_component_on = excluded.oldest_component_on,
                    latest_component_on = excluded.latest_component_on,
                    constituent_count = excluded.constituent_count,
                    covered_count = excluded.covered_count,
                    above_ma20_count = excluded.above_ma20_count,
                    above_ma50_count = excluded.above_ma50_count,
                    above_ma200_count = excluded.above_ma200_count,
                    breadth_score = excluded.breadth_score,
                    provider = excluded.provider,
                    collected_at = excluded.collected_at
                """, new MapSqlParameterSource()
                .addValue("sectorKey", normalizeSectorKey(sectorKey))
                .addValue("observedOn", evidence.asOfDate())
                .addValue("oldestComponentOn", evidence.oldestObservedOn())
                .addValue("latestComponentOn", evidence.latestObservedOn())
                .addValue("constituentCount", evidence.constituentCount())
                .addValue("coveredCount", evidence.coveredCount())
                .addValue("aboveMa20Count", evidence.aboveMa20Count())
                .addValue("aboveMa50Count", evidence.aboveMa50Count())
                .addValue("aboveMa200Count", evidence.aboveMa200Count())
                .addValue("breadthScore", evidence.score())
                .addValue("provider", BREADTH_PROVIDER)
                .addValue("collectedAt", PostgresTemporal.timestamp(Objects.requireNonNull(collectedAt))));
    }

    @Override
    public CurrentSectorMarketEvidence loadCurrent(String sectorKey, LocalDate asOfDate, int maxAgeDays) {
        var key = normalizeSectorKey(sectorKey);
        Objects.requireNonNull(asOfDate, "asOfDate");
        if (maxAgeDays < 0) throw new IllegalArgumentException("maxAgeDays must not be negative");
        var parameters = new MapSqlParameterSource()
                .addValue("sectorKey", key)
                .addValue("asOf", asOfDate)
                .addValue("oldest", asOfDate.minusDays(maxAgeDays));
        var flow = jdbc.query("""
                select observed_on, nav, shares_outstanding, total_net_assets,
                       flow_1d_usd, flow_5d_usd, flow_20d_usd,
                       flow_5d_pct, flow_20d_pct, flow_score
                from research.sector_fund_flow_snapshot
                where sector_key = :sectorKey and observed_on between :oldest and :asOf
                order by observed_on desc
                limit 1
                """, parameters, (row, ignored) -> new SectorFundFlowEvidence(
                row.getObject("observed_on", LocalDate.class),
                row.getDouble("nav"), row.getDouble("shares_outstanding"),
                row.getDouble("total_net_assets"), row.getDouble("flow_1d_usd"),
                row.getDouble("flow_5d_usd"), row.getDouble("flow_20d_usd"),
                row.getDouble("flow_5d_pct"), row.getDouble("flow_20d_pct"),
                row.getInt("flow_score"))).stream().findFirst().orElse(null);
        var breadth = jdbc.query("""
                select observed_on, oldest_component_on, latest_component_on,
                       constituent_count, covered_count, above_ma20_count,
                       above_ma50_count, above_ma200_count, breadth_score
                from research.sector_price_breadth_snapshot
                where sector_key = :sectorKey and observed_on between :oldest and :asOf
                order by observed_on desc
                limit 1
                """, parameters, (row, ignored) -> new SectorPriceBreadthEvidence(
                row.getObject("observed_on", LocalDate.class),
                row.getObject("oldest_component_on", LocalDate.class),
                row.getObject("latest_component_on", LocalDate.class),
                row.getInt("constituent_count"), row.getInt("covered_count"),
                row.getInt("above_ma20_count"), row.getInt("above_ma50_count"),
                row.getInt("above_ma200_count"), row.getInt("breadth_score")))
                .stream().findFirst().orElse(null);
        return new CurrentSectorMarketEvidence(flow, breadth);
    }

    private static String normalizeSectorKey(String raw) {
        if (raw == null) throw new IllegalArgumentException("sectorKey is required");
        var value = raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("SECTOR_XL[A-Z]{1,2}")) throw new IllegalArgumentException("unsupported sector key");
        return value;
    }

    private static String normalizeFundTicker(String raw) {
        if (raw == null) throw new IllegalArgumentException("fundTicker is required");
        var value = raw.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("XL[A-Z]{1,2}")) throw new IllegalArgumentException("unsupported fund ticker");
        return value;
    }
}
