package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.application.port.out.SectorRotationValidationRepository;
import io.macrosquare.research.domain.rotation.PendingSectorRotationWindow;
import io.macrosquare.research.domain.rotation.SectorRotationCompositeSnapshot;
import io.macrosquare.research.domain.rotation.SectorRotationOutcome;
import io.macrosquare.shared.adapter.out.persistence.PostgresTemporal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Objects;

/** Atomic append-only PostgreSQL ledger for live composite validation. */
public final class JdbcSectorRotationValidationRepository implements SectorRotationValidationRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations transactions;

    public JdbcSectorRotationValidationRepository(
            NamedParameterJdbcTemplate jdbc,
            TransactionOperations transactions
    ) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
    }

    @Override
    public boolean append(SectorRotationCompositeSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var inserted = jdbc.update("""
                    insert into research.sector_rotation_run (
                        run_id, calculated_at, as_of_date, price_anchor_on, methodology_version, regime,
                        regime_confidence, current_momentum_coverage, total_return_coverage,
                        universe_size, oldest_macro_observed_on, latest_macro_observed_on
                    ) values (
                        :runId, :calculatedAt, :asOfDate, :priceAnchorOn, :methodologyVersion, :regime,
                        :regimeConfidence, :currentMomentumCoverage, :totalReturnCoverage,
                        :universeSize, :oldestMacroObservedOn, :latestMacroObservedOn
                    ) on conflict (methodology_version, price_anchor_on) do nothing
                    """, new MapSqlParameterSource()
                    .addValue("runId", snapshot.runId())
                    .addValue("calculatedAt", PostgresTemporal.timestamp(snapshot.calculatedAt()))
                    .addValue("asOfDate", snapshot.asOfDate())
                    .addValue("priceAnchorOn", snapshot.priceAnchorOn())
                    .addValue("methodologyVersion", snapshot.methodologyVersion())
                    .addValue("regime", snapshot.regime().name())
                    .addValue("regimeConfidence", snapshot.regimeConfidence())
                    .addValue("currentMomentumCoverage", snapshot.currentMomentumCoverage())
                    .addValue("totalReturnCoverage", snapshot.totalReturnCoverage())
                    .addValue("universeSize", snapshot.universeSize())
                    .addValue("oldestMacroObservedOn", snapshot.oldestMacroObservedOn())
                    .addValue("latestMacroObservedOn", snapshot.latestMacroObservedOn()));
            if (inserted == 0) return false;
            var batches = snapshot.items().stream().map(item -> parameters(snapshot, item))
                    .toArray(MapSqlParameterSource[]::new);
            var counts = jdbc.batchUpdate("""
                    insert into research.sector_rotation_item_snapshot (
                        run_id, sector_key, rank, rotation_score, macro_fit_score,
                        relative_strength_score, fundamental_score, valuation_score,
                        earnings_revision_score, fund_flow_score, price_breadth_score,
                        crowding_relief_score, state, rotation_label, expected_leadership_window,
                        oldest_momentum_observed_on, latest_momentum_observed_on,
                        revision_observed_on, revision_coverage_pct, fund_flow_observed_on,
                        price_breadth_observed_on, price_breadth_coverage_pct
                    ) values (
                        :runId, :sectorKey, :rank, :rotationScore, :macroFitScore,
                        :relativeStrengthScore, :fundamentalScore, :valuationScore,
                        :earningsRevisionScore, :fundFlowScore, :priceBreadthScore,
                        :crowdingReliefScore, :state, :rotationLabel, :expectedLeadershipWindow,
                        :oldestMomentumObservedOn, :latestMomentumObservedOn,
                        :revisionObservedOn, :revisionCoveragePct, :fundFlowObservedOn,
                        :priceBreadthObservedOn, :priceBreadthCoveragePct
                    )
                    """, batches);
            if (counts.length != 11) throw new IllegalStateException("sector snapshot item write was incomplete");
            return true;
        }));
    }

    @Override
    public List<PendingSectorRotationWindow> loadPendingWindows(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return jdbc.query("""
                select r.run_id, r.as_of_date, r.price_anchor_on, h.trading_sessions
                from research.sector_rotation_run r
                cross join (values (21), (63), (126)) as h(trading_sessions)
                where not exists (
                    select 1 from research.sector_rotation_outcome o
                    where o.run_id = r.run_id and o.trading_sessions = h.trading_sessions
                )
                order by r.as_of_date, h.trading_sessions
                limit :limit
                """, new MapSqlParameterSource("limit", limit), (row, ignored) -> new PendingSectorRotationWindow(
                row.getObject("run_id", java.util.UUID.class),
                row.getObject("as_of_date", java.time.LocalDate.class),
                row.getObject("price_anchor_on", java.time.LocalDate.class),
                row.getInt("trading_sessions")));
    }

    @Override
    public int appendOutcomes(List<SectorRotationOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return 0;
        return transactions.execute(status -> {
            var parameters = outcomes.stream().map(value -> new MapSqlParameterSource()
                            .addValue("runId", value.runId()).addValue("sectorKey", value.sectorKey())
                            .addValue("tradingSessions", value.tradingSessions())
                            .addValue("startOn", value.startOn()).addValue("endOn", value.endOn())
                            .addValue("sectorReturnPct", value.sectorReturnPct())
                            .addValue("benchmarkReturnPct", value.benchmarkReturnPct())
                            .addValue("universeEqualWeightReturnPct", value.universeEqualWeightReturnPct())
                            .addValue("benchmarkExcessReturnPct", value.benchmarkExcessReturnPct())
                            .addValue("universeExcessReturnPct", value.universeExcessReturnPct()))
                    .toArray(MapSqlParameterSource[]::new);
            var counts = jdbc.batchUpdate("""
                    insert into research.sector_rotation_outcome (
                        run_id, sector_key, trading_sessions, start_on, end_on,
                        sector_return_pct, benchmark_return_pct, universe_equal_weight_return_pct,
                        benchmark_excess_return_pct, universe_excess_return_pct
                    ) values (
                        :runId, :sectorKey, :tradingSessions, :startOn, :endOn,
                        :sectorReturnPct, :benchmarkReturnPct, :universeEqualWeightReturnPct,
                        :benchmarkExcessReturnPct, :universeExcessReturnPct
                    ) on conflict (run_id, sector_key, trading_sessions) do nothing
                    """, parameters);
            return java.util.Arrays.stream(counts).map(value -> value > 0 ? 1 : 0).sum();
        });
    }

    private static MapSqlParameterSource parameters(
            SectorRotationCompositeSnapshot snapshot,
            SectorRotationCompositeSnapshot.Item item
    ) {
        return new MapSqlParameterSource()
                .addValue("runId", snapshot.runId()).addValue("sectorKey", item.sectorKey())
                .addValue("rank", item.rank()).addValue("rotationScore", item.rotationScore())
                .addValue("macroFitScore", item.macroFitScore())
                .addValue("relativeStrengthScore", item.relativeStrengthScore())
                .addValue("fundamentalScore", item.fundamentalScore())
                .addValue("valuationScore", item.valuationScore())
                .addValue("earningsRevisionScore", item.earningsRevisionScore())
                .addValue("fundFlowScore", item.fundFlowScore())
                .addValue("priceBreadthScore", item.priceBreadthScore())
                .addValue("crowdingReliefScore", item.crowdingReliefScore())
                .addValue("state", item.state().name()).addValue("rotationLabel", item.rotationLabel().name())
                .addValue("expectedLeadershipWindow", item.expectedLeadershipWindow().name())
                .addValue("oldestMomentumObservedOn", item.oldestMomentumObservedOn())
                .addValue("latestMomentumObservedOn", item.latestMomentumObservedOn())
                .addValue("revisionObservedOn", item.revisionObservedOn())
                .addValue("revisionCoveragePct", item.revisionCoveragePct())
                .addValue("fundFlowObservedOn", item.fundFlowObservedOn())
                .addValue("priceBreadthObservedOn", item.priceBreadthObservedOn())
                .addValue("priceBreadthCoveragePct", item.priceBreadthCoveragePct());
    }
}
