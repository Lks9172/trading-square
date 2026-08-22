package io.macrosquare.crypto.adapter.in.web;

import io.macrosquare.crypto.application.model.CryptoResearchModels;

import java.util.List;

public final class CryptoResearchApiResponse {

    private CryptoResearchApiResponse() {
    }

    public record Catalog(
            List<Research> items,
            MarketRegime marketRegime,
            List<AssetSummary> assets,
            DecisionFreshness freshness
    ) {
        static Catalog from(CryptoResearchModels.Catalog source) {
            return new Catalog(
                    source.items().stream().map(Research::from).toList(),
                    MarketRegime.from(source.marketRegime()),
                    source.assets().stream().map(AssetSummary::from).toList(),
                    DecisionFreshness.from(source.freshness())
            );
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
        static Research from(CryptoResearchModels.Research source) {
            return new Research(
                    Profile.from(source.profile()),
                    Market.from(source.market()),
                    Macro.from(source.macro()),
                    Narrative.from(source.narrative()),
                    BottomUp.from(source.bottomUp()),
                    Moat.from(source.moat()),
                    SupplyPressure.from(source.supplyPressure()),
                    Onchain.from(source.onchain()),
                    Flows.from(source.flows()),
                    TrendCharts.from(source.trendCharts()),
                    DecisionFreshness.from(source.freshness()),
                    BuyScore.from(source.buyScore()),
                    BottomSignal.from(source.bottomSignal()),
                    PositionSizing.from(source.positionSizing()),
                    Verdicts.from(source.verdicts()),
                    Scenarios.from(source.scenarios()),
                    ExecutionBridge.from(source.executionBridge())
            );
        }
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
        static Profile from(CryptoResearchModels.Profile source) {
            return new Profile(
                    source.symbol(), source.yahooSymbol(), source.coingeckoId(), source.llamaChainSlug(),
                    source.name(), source.category(), source.narrativeTheme(), source.linkedAsset(),
                    source.foundationalScore(), source.networkScore(), source.tokenomicsScore(),
                    source.adoptionScore(), source.macroSensitivity(), source.strengths(), source.risks()
            );
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
        static Market from(CryptoResearchModels.Market source) {
            return new Market(
                    source.asOf(), source.price(), source.return7d(), source.return30d(), source.return90d(),
                    source.volumeTrend30d(), source.volatility30d(), source.distanceFrom52wHigh(),
                    source.distanceFrom52wLow()
            );
        }
    }

    public record Macro(
            Integer liquidityScore,
            Integer dollarScore,
            Integer riskOnScore,
            String stance,
            String summary,
            List<String> drivers
    ) {
        static Macro from(CryptoResearchModels.Macro source) {
            return new Macro(
                    source.liquidityScore(), source.dollarScore(), source.riskOnScore(), source.stance(),
                    source.summary(), source.drivers()
            );
        }
    }

    public record Narrative(String theme, String stage, int heatScore, String summary) {
        static Narrative from(CryptoResearchModels.Narrative source) {
            return new Narrative(source.theme(), source.stage(), source.heatScore(), source.summary());
        }
    }

    public record BottomUp(
            int networkScore,
            int tokenomicsScore,
            int adoptionScore,
            String summary,
            List<String> strengths,
            List<String> risks
    ) {
        static BottomUp from(CryptoResearchModels.BottomUp source) {
            return new BottomUp(
                    source.networkScore(), source.tokenomicsScore(), source.adoptionScore(), source.summary(),
                    source.strengths(), source.risks()
            );
        }
    }

    public record Moat(String moatType, int moatScore, String summary, List<String> reasons) {
        static Moat from(CryptoResearchModels.Moat source) {
            return new Moat(source.moatType(), source.moatScore(), source.summary(), source.reasons());
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
        static SupplyPressure from(CryptoResearchModels.SupplyPressure source) {
            return new SupplyPressure(
                    source.unlockRisk(), source.dilutionRisk(), source.floatScore(), source.fdvPremiumPct(),
                    source.circulatingRatioPct(), source.summary(), source.reasons()
            );
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
        static Onchain from(CryptoResearchModels.Onchain source) {
            return new Onchain(
                    source.tvlUsd(), source.tvlTrend30dPct(), source.fees30dAvgUsd(), source.feesTrend30dPct(),
                    source.developerScore(), source.communityScore(), source.activityScore(), source.summary(),
                    source.reasons()
            );
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
        static Flows from(CryptoResearchModels.Flows source) {
            return new Flows(
                    source.stablecoinDemandScore(), source.stablecoinDemandLabel(), source.stablecoinDominancePct(),
                    source.altSeasonScore(), source.altSeasonLabel(), source.altSeasonInsight(),
                    source.btcDominanceScore(), source.btcDominanceLabel(), source.btcDominancePct(),
                    source.etfFlowProxy(), source.etfDailyNetFlowUsd(), source.etfWeeklyNetFlowUsd(),
                    source.exchangeNetflowProxy(), source.exchangeNetflowInsight(), source.exchangeFlowRisk(),
                    source.derivativesHeat(), source.volumeToMarketCapPct(), source.summary(), source.reasons()
            );
        }
    }

    public record TrendCharts(
            List<TrendPoint> btcDominanceProxy30d,
            List<TrendPoint> stablecoinMcap30d,
            List<TrendPoint> etfNetFlow30d,
            List<TrendPoint> altSeasonProxy30d,
            List<TrendPoint> exchangeNetflowProxy30d
    ) {
        static TrendCharts from(CryptoResearchModels.TrendCharts source) {
            return new TrendCharts(
                    source.btcDominanceProxy30d().stream().map(TrendPoint::from).toList(),
                    source.stablecoinMcap30d().stream().map(TrendPoint::from).toList(),
                    source.etfNetFlow30d().stream().map(TrendPoint::from).toList(),
                    source.altSeasonProxy30d().stream().map(TrendPoint::from).toList(),
                    source.exchangeNetflowProxy30d().stream().map(TrendPoint::from).toList()
            );
        }
    }

    public record TrendPoint(String date, Number value) {
        static TrendPoint from(CryptoResearchModels.TrendPoint source) {
            return new TrendPoint(source.date(), source.value());
        }
    }

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
        static DecisionFreshness from(CryptoResearchModels.DecisionFreshness source) {
            if (source == null) {
                return new DecisionFreshness(null, null, null, null, 2, 7, false,
                        "UNKNOWN", "코인 의사결정 근거의 최신성을 확인할 수 없습니다.");
            }
            return new DecisionFreshness(
                    source.marketObservedOn(), source.supportingEvidenceObservedOn(),
                    source.marketAgeDays(), source.supportingEvidenceAgeDays(),
                    source.maximumMarketAgeDays(), source.maximumSupportingEvidenceAgeDays(),
                    source.eligibleForDecisions(), source.status(), source.explanation()
            );
        }
    }

    public record BuyScore(
            int appealScore,
            int crowdingScore,
            int buyScore,
            String action,
            String actionLabel,
            List<String> reasons
    ) {
        static BuyScore from(CryptoResearchModels.BuyScore source) {
            return new BuyScore(
                    source.appealScore(), source.crowdingScore(), source.buyScore(), source.action(),
                    source.actionLabel(), source.reasons()
            );
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
        static BottomSignal from(CryptoResearchModels.BottomSignal source) {
            return new BottomSignal(
                    source.score(), source.state(), source.actionBias(), source.summary(),
                    source.volumeConfirmationScore(), source.failureRiskScore(),
                    source.metrics().stream().map(BottomMetric::from).toList(),
                    BottomChart.from(source.chart()), ConfirmedBottom.from(source.confirmedBottom()),
                    source.reasons(), source.cautions(), source.failureSignals()
            );
        }
    }

    public record BottomMetric(String key, String label, Integer score, String status, String detail) {
        static BottomMetric from(CryptoResearchModels.BottomMetric source) {
            return new BottomMetric(source.key(), source.label(), source.score(), source.status(), source.detail());
        }
    }

    public record BottomChart(List<ChartPoint> points, List<ChartMarker> markers) {
        static BottomChart from(CryptoResearchModels.BottomChart source) {
            return new BottomChart(
                    source.points().stream().map(ChartPoint::from).toList(),
                    source.markers().stream().map(ChartMarker::from).toList()
            );
        }
    }

    public record ChartPoint(String date, Number value) {
        static ChartPoint from(CryptoResearchModels.ChartPoint source) {
            return new ChartPoint(source.date(), source.value());
        }
    }

    public record ChartMarker(String kind, String date, Number value, String label) {
        static ChartMarker from(CryptoResearchModels.ChartMarker source) {
            return new ChartMarker(source.kind(), source.date(), source.value(), source.label());
        }
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
        static ConfirmedBottom from(CryptoResearchModels.ConfirmedBottom source) {
            if (source == null) return null;
            return new ConfirmedBottom(
                    source.score(), source.state(), source.actionBias(), source.signalDate(),
                    source.daysSinceSignal(), source.summary(), source.recentVolumeRatio(),
                    source.contractionRatio(), source.drawdown120dPct(), source.ma20GapPct(),
                    source.recentDrop3dPct(), source.reasons(), source.cautions()
            );
        }
    }

    public record PositionSizing(
            int targetPositionPct,
            int initialEntryPctOfTarget,
            int reservePctOfTarget,
            String summary
    ) {
        static PositionSizing from(CryptoResearchModels.PositionSizing source) {
            return new PositionSizing(
                    source.targetPositionPct(), source.initialEntryPctOfTarget(), source.reservePctOfTarget(),
                    source.summary()
            );
        }
    }

    public record Verdicts(
            String quality,
            String timing,
            String valuationProxy,
            String finalAction,
            OneLiners oneLiners
    ) {
        static Verdicts from(CryptoResearchModels.Verdicts source) {
            return new Verdicts(
                    source.quality(), source.timing(), source.valuationProxy(), source.finalAction(),
                    OneLiners.from(source.oneLiners())
            );
        }
    }

    public record OneLiners(String quality, String timing, String action) {
        static OneLiners from(CryptoResearchModels.OneLiners source) {
            return new OneLiners(source.quality(), source.timing(), source.action());
        }
    }

    public record Scenarios(String bullCase, String baseCase, String bearCase) {
        static Scenarios from(CryptoResearchModels.Scenarios source) {
            return new Scenarios(source.bullCase(), source.baseCase(), source.bearCase());
        }
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
        static ExecutionBridge from(CryptoResearchModels.ExecutionBridge source) {
            if (source == null) return null;
            return new ExecutionBridge(
                    source.asset(), source.action(), source.actionLabel(), source.targetAllocationPct(),
                    source.alignment(), source.entryMode(), source.riskBox(), source.summary(), source.timingNotes()
            );
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
        static MarketRegime from(CryptoResearchModels.MarketRegime source) {
            return new MarketRegime(
                    source.regime(), source.action(), source.altRegime(), source.targetTotalExposurePct(),
                    source.summary(), source.reasons()
            );
        }
    }

    public record AssetSummary(String symbol, String name, String category, String narrativeTheme) {
        static AssetSummary from(CryptoResearchModels.AssetSummary source) {
            return new AssetSummary(source.symbol(), source.name(), source.category(), source.narrativeTheme());
        }
    }
}
