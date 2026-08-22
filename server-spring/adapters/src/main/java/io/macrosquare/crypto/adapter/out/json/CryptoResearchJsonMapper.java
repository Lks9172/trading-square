package io.macrosquare.crypto.adapter.out.json;

import io.macrosquare.crypto.application.model.CryptoResearchModels.AssetSummary;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomChart;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomMetric;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomSignal;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BottomUp;
import io.macrosquare.crypto.application.model.CryptoResearchModels.BuyScore;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Catalog;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ChartMarker;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ChartPoint;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ConfirmedBottom;
import io.macrosquare.crypto.application.model.CryptoResearchModels.ExecutionBridge;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Flows;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Macro;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Market;
import io.macrosquare.crypto.application.model.CryptoResearchModels.MarketRegime;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Moat;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Narrative;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Onchain;
import io.macrosquare.crypto.application.model.CryptoResearchModels.OneLiners;
import io.macrosquare.crypto.application.model.CryptoResearchModels.PositionSizing;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Profile;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Research;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Scenarios;
import io.macrosquare.crypto.application.model.CryptoResearchModels.SupplyPressure;
import io.macrosquare.crypto.application.model.CryptoResearchModels.TrendCharts;
import io.macrosquare.crypto.application.model.CryptoResearchModels.TrendPoint;
import io.macrosquare.crypto.application.model.CryptoResearchModels.Verdicts;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class CryptoResearchJsonMapper {

    private CryptoResearchJsonMapper() {
    }

    public static Catalog mapCatalog(JsonNode root) {
        var items = new ArrayList<Research>();
        for (var node : requiredArray(root, "items")) items.add(mapResearch(node));
        var assets = new ArrayList<AssetSummary>();
        for (var node : requiredArray(root, "assets")) {
            assets.add(new AssetSummary(
                    requiredText(node, "symbol"),
                    requiredText(node, "name"),
                    requiredText(node, "category"),
                    requiredText(node, "narrativeTheme")
            ));
        }
        return new Catalog(items, marketRegime(requiredObject(root, "marketRegime")), assets, null);
    }

    public static Research mapResearch(JsonNode root) {
        return new Research(
                profile(requiredObject(root, "profile")),
                market(requiredObject(root, "market")),
                macro(requiredObject(root, "macro")),
                narrative(requiredObject(root, "narrative")),
                bottomUp(requiredObject(root, "bottomUp")),
                moat(requiredObject(root, "moat")),
                supplyPressure(requiredObject(root, "supplyPressure")),
                onchain(requiredObject(root, "onchain")),
                flows(requiredObject(root, "flows")),
                trendCharts(requiredObject(root, "trendCharts")),
                null,
                buyScore(requiredObject(root, "buyScore")),
                bottomSignal(requiredObject(root, "bottomSignal")),
                positionSizing(requiredObject(root, "positionSizing")),
                verdicts(requiredObject(root, "verdicts")),
                scenarios(requiredObject(root, "scenarios")),
                nullableExecutionBridge(root.get("executionBridge"))
        );
    }

    private static Profile profile(JsonNode node) {
        return new Profile(
                requiredText(node, "symbol"),
                requiredText(node, "yahooSymbol"),
                requiredText(node, "coingeckoId"),
                requiredText(node, "llamaChainSlug"),
                requiredText(node, "name"),
                requiredText(node, "category"),
                requiredText(node, "narrativeTheme"),
                requiredText(node, "linkedAsset"),
                requiredInt(node, "foundationalScore"),
                requiredInt(node, "networkScore"),
                requiredInt(node, "tokenomicsScore"),
                requiredInt(node, "adoptionScore"),
                textList(requiredArray(node, "macroSensitivity")),
                textList(requiredArray(node, "strengths")),
                textList(requiredArray(node, "risks"))
        );
    }

    private static Market market(JsonNode node) {
        return new Market(
                nullableText(node, "asOf"),
                nullableNumber(node, "price"),
                nullableNumber(node, "return7d"),
                nullableNumber(node, "return30d"),
                nullableNumber(node, "return90d"),
                nullableNumber(node, "volumeTrend30d"),
                nullableNumber(node, "volatility30d"),
                nullableNumber(node, "distanceFrom52wHigh"),
                nullableNumber(node, "distanceFrom52wLow")
        );
    }

    private static Macro macro(JsonNode node) {
        return new Macro(
                nullableInt(node, "liquidityScore"),
                nullableInt(node, "dollarScore"),
                nullableInt(node, "riskOnScore"),
                requiredText(node, "stance"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "drivers"))
        );
    }

    private static Narrative narrative(JsonNode node) {
        return new Narrative(
                requiredText(node, "theme"),
                requiredText(node, "stage"),
                requiredInt(node, "heatScore"),
                requiredText(node, "summary")
        );
    }

    private static BottomUp bottomUp(JsonNode node) {
        return new BottomUp(
                requiredInt(node, "networkScore"),
                requiredInt(node, "tokenomicsScore"),
                requiredInt(node, "adoptionScore"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "strengths")),
                textList(requiredArray(node, "risks"))
        );
    }

    private static Moat moat(JsonNode node) {
        return new Moat(
                requiredText(node, "moatType"),
                requiredInt(node, "moatScore"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "reasons"))
        );
    }

    private static SupplyPressure supplyPressure(JsonNode node) {
        return new SupplyPressure(
                requiredText(node, "unlockRisk"),
                requiredText(node, "dilutionRisk"),
                requiredInt(node, "floatScore"),
                nullableNumber(node, "fdvPremiumPct"),
                nullableNumber(node, "circulatingRatioPct"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "reasons"))
        );
    }

    private static Onchain onchain(JsonNode node) {
        return new Onchain(
                nullableNumber(node, "tvlUsd"),
                nullableNumber(node, "tvlTrend30dPct"),
                nullableNumber(node, "fees30dAvgUsd"),
                nullableNumber(node, "feesTrend30dPct"),
                nullableNumber(node, "developerScore"),
                nullableNumber(node, "communityScore"),
                requiredInt(node, "activityScore"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "reasons"))
        );
    }

    private static Flows flows(JsonNode node) {
        return new Flows(
                nullableInt(node, "stablecoinDemandScore"),
                requiredText(node, "stablecoinDemandLabel"),
                nullableNumber(node, "stablecoinDominancePct"),
                requiredInt(node, "altSeasonScore"),
                requiredText(node, "altSeasonLabel"),
                requiredText(node, "altSeasonInsight"),
                requiredInt(node, "btcDominanceScore"),
                requiredText(node, "btcDominanceLabel"),
                nullableNumber(node, "btcDominancePct"),
                requiredText(node, "etfFlowProxy"),
                nullableNumber(node, "etfDailyNetFlowUsd"),
                nullableNumber(node, "etfWeeklyNetFlowUsd"),
                requiredText(node, "exchangeNetflowProxy"),
                requiredText(node, "exchangeNetflowInsight"),
                requiredText(node, "exchangeFlowRisk"),
                requiredText(node, "derivativesHeat"),
                nullableNumber(node, "volumeToMarketCapPct"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "reasons"))
        );
    }

    private static TrendCharts trendCharts(JsonNode node) {
        return new TrendCharts(
                trendPoints(requiredArray(node, "btcDominanceProxy30d")),
                trendPoints(requiredArray(node, "stablecoinMcap30d")),
                trendPoints(requiredArray(node, "etfNetFlow30d")),
                trendPoints(requiredArray(node, "altSeasonProxy30d")),
                trendPoints(requiredArray(node, "exchangeNetflowProxy30d"))
        );
    }

    private static List<TrendPoint> trendPoints(JsonNode array) {
        var points = new ArrayList<TrendPoint>();
        for (var node : array) {
            points.add(new TrendPoint(requiredText(node, "date"), requiredNumber(node, "value")));
        }
        return points;
    }

    private static BuyScore buyScore(JsonNode node) {
        return new BuyScore(
                requiredInt(node, "appealScore"),
                requiredInt(node, "crowdingScore"),
                requiredInt(node, "buyScore"),
                requiredText(node, "action"),
                requiredText(node, "actionLabel"),
                textList(requiredArray(node, "reasons"))
        );
    }

    private static BottomSignal bottomSignal(JsonNode node) {
        var metrics = new ArrayList<BottomMetric>();
        for (var item : requiredArray(node, "metrics")) {
            metrics.add(new BottomMetric(
                    requiredText(item, "key"),
                    requiredText(item, "label"),
                    nullableInt(item, "score"),
                    requiredText(item, "status"),
                    requiredText(item, "detail")
            ));
        }
        return new BottomSignal(
                requiredInt(node, "score"),
                requiredText(node, "state"),
                requiredText(node, "actionBias"),
                requiredText(node, "summary"),
                nullableInt(node, "volumeConfirmationScore"),
                nullableInt(node, "failureRiskScore"),
                metrics,
                bottomChart(requiredObject(node, "chart")),
                nullableConfirmedBottom(node.get("confirmedBottom")),
                textList(requiredArray(node, "reasons")),
                textList(requiredArray(node, "cautions")),
                textList(requiredArray(node, "failureSignals"))
        );
    }

    private static BottomChart bottomChart(JsonNode node) {
        var points = new ArrayList<ChartPoint>();
        for (var item : requiredArray(node, "points")) {
            points.add(new ChartPoint(requiredText(item, "date"), requiredNumber(item, "value")));
        }
        var markers = new ArrayList<ChartMarker>();
        for (var item : requiredArray(node, "markers")) {
            markers.add(new ChartMarker(
                    requiredText(item, "kind"),
                    requiredText(item, "date"),
                    requiredNumber(item, "value"),
                    requiredText(item, "label")
            ));
        }
        return new BottomChart(points, markers);
    }

    private static ConfirmedBottom nullableConfirmedBottom(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("Expected confirmedBottom object");
        return new ConfirmedBottom(
                requiredInt(node, "score"),
                requiredText(node, "state"),
                requiredText(node, "actionBias"),
                nullableText(node, "signalDate"),
                nullableInt(node, "daysSinceSignal"),
                requiredText(node, "summary"),
                nullableNumber(node, "recentVolumeRatio"),
                nullableNumber(node, "contractionRatio"),
                nullableNumber(node, "drawdown120dPct"),
                nullableNumber(node, "ma20GapPct"),
                nullableNumber(node, "recentDrop3dPct"),
                textList(requiredArray(node, "reasons")),
                textList(requiredArray(node, "cautions"))
        );
    }

    private static PositionSizing positionSizing(JsonNode node) {
        return new PositionSizing(
                requiredInt(node, "targetPositionPct"),
                requiredInt(node, "initialEntryPctOfTarget"),
                requiredInt(node, "reservePctOfTarget"),
                requiredText(node, "summary")
        );
    }

    private static Verdicts verdicts(JsonNode node) {
        var lines = requiredObject(node, "oneLiners");
        return new Verdicts(
                requiredText(node, "quality"),
                requiredText(node, "timing"),
                requiredText(node, "valuationProxy"),
                requiredText(node, "finalAction"),
                new OneLiners(
                        requiredText(lines, "quality"),
                        requiredText(lines, "timing"),
                        requiredText(lines, "action")
                )
        );
    }

    private static Scenarios scenarios(JsonNode node) {
        return new Scenarios(
                requiredText(node, "bullCase"),
                requiredText(node, "baseCase"),
                requiredText(node, "bearCase")
        );
    }

    private static ExecutionBridge nullableExecutionBridge(JsonNode node) {
        if (node == null) throw new IllegalArgumentException("Missing executionBridge field");
        if (node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("Expected executionBridge object");
        return new ExecutionBridge(
                requiredText(node, "asset"),
                requiredText(node, "action"),
                requiredText(node, "actionLabel"),
                requiredInt(node, "targetAllocationPct"),
                requiredText(node, "alignment"),
                requiredText(node, "entryMode"),
                requiredText(node, "riskBox"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "timingNotes"))
        );
    }

    private static MarketRegime marketRegime(JsonNode node) {
        return new MarketRegime(
                requiredText(node, "regime"),
                requiredText(node, "action"),
                requiredText(node, "altRegime"),
                requiredInt(node, "targetTotalExposurePct"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "reasons"))
        );
    }

    private static List<String> textList(JsonNode array) {
        var values = new ArrayList<String>();
        for (var item : array) {
            if (!item.isString()) throw new IllegalArgumentException("Expected text array value");
            values.add(item.stringValue());
        }
        return values;
    }

    private static JsonNode requiredObject(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Missing object field: " + field);
        return node;
    }

    private static JsonNode requiredArray(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isArray()) throw new IllegalArgumentException("Missing array field: " + field);
        return node;
    }

    private static String requiredText(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isString()) throw new IllegalArgumentException("Missing text field: " + field);
        return node.stringValue();
    }

    private static String nullableText(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null) throw new IllegalArgumentException("Missing nullable text field: " + field);
        if (node.isNull()) return null;
        if (!node.isString()) throw new IllegalArgumentException("Expected nullable text field: " + field);
        return node.stringValue();
    }

    private static int requiredInt(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isNumber()) throw new IllegalArgumentException("Missing number field: " + field);
        return node.asInt();
    }

    private static Integer nullableInt(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null) throw new IllegalArgumentException("Missing nullable number field: " + field);
        if (node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("Expected nullable number field: " + field);
        return node.asInt();
    }

    private static Number requiredNumber(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || !node.isNumber()) throw new IllegalArgumentException("Missing number field: " + field);
        return jsonNumber(node);
    }

    private static Number nullableNumber(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null) throw new IllegalArgumentException("Missing nullable number field: " + field);
        if (node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("Expected nullable number field: " + field);
        return jsonNumber(node);
    }

    private static Number jsonNumber(JsonNode node) {
        var value = node.asDouble();
        if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            return (long) value;
        }
        return BigDecimal.valueOf(value);
    }
}
