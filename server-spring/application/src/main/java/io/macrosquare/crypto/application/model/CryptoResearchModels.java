package io.macrosquare.crypto.application.model;

import java.util.List;

/**
 * Transport-neutral query projections for the crypto research read model.
 *
 * <p>The calculated values still originate from the legacy service during the
 * strangler phase. No HTTP, JSON, cache, persistence, or collector type crosses
 * into this model.</p>
 */
public final class CryptoResearchModels {

    private CryptoResearchModels() {
    }

    public record Catalog(
            List<Research> items,
            MarketRegime marketRegime,
            List<AssetSummary> assets,
            DecisionFreshness freshness
    ) {
        public Catalog {
            items = List.copyOf(items);
            assets = List.copyOf(assets);
        }
    }

    public record Research(
            Profile profile,
            Market market,
            Macro macro,
            Narrative narrative,
            BottomUp bottomUp,
            Moat moat,
            SupplyPressure supplyPressure,
            Onchain onchain,
            Flows flows,
            TrendCharts trendCharts,
            DecisionFreshness freshness,
            BuyScore buyScore,
            BottomSignal bottomSignal,
            PositionSizing positionSizing,
            Verdicts verdicts,
            Scenarios scenarios,
            ExecutionBridge executionBridge
    ) {
    }

    public record Profile(
            String symbol,
            String yahooSymbol,
            String coingeckoId,
            String llamaChainSlug,
            String name,
            String category,
            String narrativeTheme,
            String linkedAsset,
            int foundationalScore,
            int networkScore,
            int tokenomicsScore,
            int adoptionScore,
            List<String> macroSensitivity,
            List<String> strengths,
            List<String> risks
    ) {
        public Profile {
            macroSensitivity = List.copyOf(macroSensitivity);
            strengths = List.copyOf(strengths);
            risks = List.copyOf(risks);
        }
    }

    public record Market(
            String asOf,
            Number price,
            Number return7d,
            Number return30d,
            Number return90d,
            Number volumeTrend30d,
            Number volatility30d,
            Number distanceFrom52wHigh,
            Number distanceFrom52wLow
    ) {
    }

    public record Macro(
            Integer liquidityScore,
            Integer dollarScore,
            Integer riskOnScore,
            String stance,
            String summary,
            List<String> drivers
    ) {
        public Macro {
            drivers = List.copyOf(drivers);
        }
    }

    public record Narrative(String theme, String stage, int heatScore, String summary) {
    }

    public record BottomUp(
            int networkScore,
            int tokenomicsScore,
            int adoptionScore,
            String summary,
            List<String> strengths,
            List<String> risks
    ) {
        public BottomUp {
            strengths = List.copyOf(strengths);
            risks = List.copyOf(risks);
        }
    }

    public record Moat(String moatType, int moatScore, String summary, List<String> reasons) {
        public Moat {
            reasons = List.copyOf(reasons);
        }
    }

    public record SupplyPressure(
            String unlockRisk,
            String dilutionRisk,
            int floatScore,
            Number fdvPremiumPct,
            Number circulatingRatioPct,
            String summary,
            List<String> reasons
    ) {
        public SupplyPressure {
            reasons = List.copyOf(reasons);
        }
    }

    public record Onchain(
            Number tvlUsd,
            Number tvlTrend30dPct,
            Number fees30dAvgUsd,
            Number feesTrend30dPct,
            Number developerScore,
            Number communityScore,
            int activityScore,
            String summary,
            List<String> reasons
    ) {
        public Onchain {
            reasons = List.copyOf(reasons);
        }
    }

    public record Flows(
            Integer stablecoinDemandScore,
            String stablecoinDemandLabel,
            Number stablecoinDominancePct,
            int altSeasonScore,
            String altSeasonLabel,
            String altSeasonInsight,
            int btcDominanceScore,
            String btcDominanceLabel,
            Number btcDominancePct,
            String etfFlowProxy,
            Number etfDailyNetFlowUsd,
            Number etfWeeklyNetFlowUsd,
            String exchangeNetflowProxy,
            String exchangeNetflowInsight,
            String exchangeFlowRisk,
            String derivativesHeat,
            Number volumeToMarketCapPct,
            String summary,
            List<String> reasons
    ) {
        public Flows {
            reasons = List.copyOf(reasons);
        }
    }

    public record TrendCharts(
            List<TrendPoint> btcDominanceProxy30d,
            List<TrendPoint> stablecoinMcap30d,
            List<TrendPoint> etfNetFlow30d,
            List<TrendPoint> altSeasonProxy30d,
            List<TrendPoint> exchangeNetflowProxy30d
    ) {
        public TrendCharts {
            btcDominanceProxy30d = List.copyOf(btcDominanceProxy30d);
            stablecoinMcap30d = List.copyOf(stablecoinMcap30d);
            etfNetFlow30d = List.copyOf(etfNetFlow30d);
            altSeasonProxy30d = List.copyOf(altSeasonProxy30d);
            exchangeNetflowProxy30d = List.copyOf(exchangeNetflowProxy30d);
        }
    }

    public record TrendPoint(String date, Number value) {
    }

    /** Separates live price freshness from slower flow/on-chain decision evidence. */
    public record DecisionFreshness(
            String marketObservedOn,
            String supportingEvidenceObservedOn,
            Integer marketAgeDays,
            Integer supportingEvidenceAgeDays,
            int maximumMarketAgeDays,
            int maximumSupportingEvidenceAgeDays,
            boolean eligibleForDecisions,
            String status,
            String explanation
    ) {
    }

    public record BuyScore(
            int appealScore,
            int crowdingScore,
            int buyScore,
            String action,
            String actionLabel,
            List<String> reasons
    ) {
        public BuyScore {
            reasons = List.copyOf(reasons);
        }
    }

    public record BottomSignal(
            int score,
            String state,
            String actionBias,
            String summary,
            Integer volumeConfirmationScore,
            Integer failureRiskScore,
            List<BottomMetric> metrics,
            BottomChart chart,
            ConfirmedBottom confirmedBottom,
            List<String> reasons,
            List<String> cautions,
            List<String> failureSignals
    ) {
        public BottomSignal {
            metrics = List.copyOf(metrics);
            reasons = List.copyOf(reasons);
            cautions = List.copyOf(cautions);
            failureSignals = List.copyOf(failureSignals);
        }
    }

    public record BottomMetric(
            String key,
            String label,
            Integer score,
            String status,
            String detail
    ) {
    }

    public record BottomChart(List<ChartPoint> points, List<ChartMarker> markers) {
        public BottomChart {
            points = List.copyOf(points);
            markers = List.copyOf(markers);
        }
    }

    public record ChartPoint(String date, Number value) {
    }

    public record ChartMarker(String kind, String date, Number value, String label) {
    }

    public record ConfirmedBottom(
            int score,
            String state,
            String actionBias,
            String signalDate,
            Integer daysSinceSignal,
            String summary,
            Number recentVolumeRatio,
            Number contractionRatio,
            Number drawdown120dPct,
            Number ma20GapPct,
            Number recentDrop3dPct,
            List<String> reasons,
            List<String> cautions
    ) {
        public ConfirmedBottom {
            reasons = List.copyOf(reasons);
            cautions = List.copyOf(cautions);
        }
    }

    public record PositionSizing(
            int targetPositionPct,
            int initialEntryPctOfTarget,
            int reservePctOfTarget,
            String summary
    ) {
    }

    public record Verdicts(
            String quality,
            String timing,
            String valuationProxy,
            String finalAction,
            OneLiners oneLiners
    ) {
    }

    public record OneLiners(String quality, String timing, String action) {
    }

    public record Scenarios(String bullCase, String baseCase, String bearCase) {
    }

    public record ExecutionBridge(
            String asset,
            String action,
            String actionLabel,
            int targetAllocationPct,
            String alignment,
            String entryMode,
            String riskBox,
            String summary,
            List<String> timingNotes
    ) {
        public ExecutionBridge {
            timingNotes = List.copyOf(timingNotes);
        }
    }

    public record MarketRegime(
            String regime,
            String action,
            String altRegime,
            int targetTotalExposurePct,
            String summary,
            List<String> reasons
    ) {
        public MarketRegime {
            reasons = List.copyOf(reasons);
        }
    }

    public record AssetSummary(String symbol, String name, String category, String narrativeTheme) {
    }
}
