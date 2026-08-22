package io.macrosquare.research.adapter.out.json;

import io.macrosquare.research.application.model.ResearchCatalogModels.DensitySummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.CompanyItem;
import io.macrosquare.research.application.model.ResearchCatalogModels.RelatedTheme;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationCandidate;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSector;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.Sector;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDefinition;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorScore;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorSummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.Theme;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDefinition;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry;
import io.macrosquare.shared.adapter.out.catalog.CurrentResearchUniverseTickerRegistry.SectorReplacement;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ResearchCatalogJsonMapper {

    /**
     * The persisted catalog is an immutable cutover artifact. Apply exchange
     * symbol lifecycle changes at the anti-corruption boundary so retired or
     * renamed securities cannot re-enter the live research universe.
     */
    private ResearchCatalogJsonMapper() {
    }

    public static ThemeCatalog mapThemes(JsonNode root) {
        var themes = new ArrayList<Theme>();
        for (var node : requiredArray(root, "themes")) {
            themes.add(new Theme(
                    requiredText(node, "id"),
                    requiredText(node, "theme"),
                    requiredText(node, "description"),
                    tickerList(requiredArray(node, "tickers")),
                    textList(requiredArray(node, "sectorKeys")),
                    sectorSummary(requiredObject(node, "sectorSummary"))
            ));
        }
        return new ThemeCatalog(themes);
    }

    public static SectorCatalog mapSectors(JsonNode root) {
        var sectors = new ArrayList<Sector>();
        for (var node : requiredArray(root, "sectors")) {
            var sectorId = requiredText(node, "id");
            sectors.add(new Sector(
                    sectorId,
                    requiredText(node, "label"),
                    requiredText(node, "description"),
                    requiredText(node, "sectorKey"),
                    sectorTickerList(sectorId, requiredArray(node, "tickers")),
                    sectorSummary(requiredObject(node, "sectorSummary")),
                    nullableRotationSector(node.get("rotation")),
                    densitySummary(requiredObject(node, "densitySummary")),
                    relatedThemes(requiredArray(node, "relatedThemes"))
            ));
        }
        return new SectorCatalog(sectors, nullableRotationSummary(root.get("rotation")));
    }

    public static ThemeDetail mapThemeDetail(JsonNode root) {
        return new ThemeDetail(
                themeDefinition(requiredObject(root, "theme")),
                companyItems(requiredArray(root, "items")),
                sectorScores(requiredArray(root, "sectorScores"), true),
                sectorSummary(requiredObject(root, "sectorSummary"), true),
                requiredText(root, "sortKey"),
                requiredText(root, "companySortKey")
        );
    }

    public static SectorDetail mapSectorDetail(JsonNode root) {
        var sectorNode = requiredObject(root, "sector");
        var sectorId = requiredText(sectorNode, "id");
        return new SectorDetail(
                sectorDefinition(sectorNode),
                requiredText(root, "sortKey"),
                relatedThemes(requiredArray(root, "relatedThemes")),
                sectorScores(requiredArray(root, "sectorScores"), true),
                sectorSummary(requiredObject(root, "sectorSummary"), true),
                nullableRotationSector(root.get("rotation")),
                nullableRotationSummary(root.get("rotationSummary")),
                densitySummary(requiredObject(root, "densitySummary")),
                sectorCompanyItems(
                        sectorId, requiredArray(root, "items"), requiredArray(sectorNode, "tickers"))
        );
    }

    private static ThemeDefinition themeDefinition(JsonNode node) {
        return new ThemeDefinition(
                requiredText(node, "id"),
                requiredText(node, "theme"),
                requiredText(node, "description"),
                tickerList(requiredArray(node, "tickers")),
                textList(requiredArray(node, "sectorKeys"))
        );
    }

    private static SectorDefinition sectorDefinition(JsonNode node) {
        var sectorId = requiredText(node, "id");
        return new SectorDefinition(
                sectorId,
                requiredText(node, "label"),
                requiredText(node, "description"),
                requiredText(node, "sectorKey"),
                sectorTickerList(sectorId, requiredArray(node, "tickers"))
        );
    }

    private static List<CompanyItem> sectorCompanyItems(
            String sectorId,
            JsonNode sourceItems,
            JsonNode sourceTickers
    ) {
        var items = new ArrayList<>(companyItems(sourceItems));
        for (var replacement : replacementsFor(sectorId, sourceTickers)) {
            if (items.stream().anyMatch(item -> item.ticker().equals(replacement.ticker()))) continue;
            items.add(new CompanyItem(
                    replacement.ticker(), replacement.name(), null, null, null, null, null, null,
                    null, null, null, replacement.sectorKey(), null, null, null, null,
                    null, null, null, items.size() + 1,
                    "현재 Spring 기업 지표 계산 대기 중"
            ));
        }
        return List.copyOf(items);
    }

    private static List<CompanyItem> companyItems(JsonNode array) {
        var items = new ArrayList<CompanyItem>();
        for (var node : array) {
            var ticker = currentTicker(requiredText(node, "ticker"));
            if (CurrentResearchUniverseTickerRegistry.retired(ticker)) continue;
            items.add(new CompanyItem(
                    ticker,
                    requiredText(node, "name"),
                    nullableNumber(node, "marketCap"),
                    nullableInt(node, "totalScore"),
                    nullableInt(node, "buyScore"),
                    nullableText(node, "buyLabel"),
                    nullableInt(node, "appealScore"),
                    nullableInt(node, "crowdingScore"),
                    nullableNumber(node, "revenueGrowthYoY"),
                    nullableNumber(node, "operatingMargin"),
                    nullableNumber(node, "evToSales"),
                    nullableText(node, "sectorKey"),
                    nullableInt(node, "bottomScore"),
                    nullableInt(node, "priceBottomScore"),
                    nullableInt(node, "volumeConfirmationScore"),
                    nullableInt(node, "failureRiskScore"),
                    nullableText(node, "bottomState"),
                    nullableInt(node, "confirmedBottomScore"),
                    nullableText(node, "confirmedBottomState"),
                    requiredInt(node, "rank"),
                    nullableText(node, "error")
            ));
        }
        return items;
    }

    private static List<SectorScore> sectorScores(JsonNode array, boolean requireTrend) {
        var scores = new ArrayList<SectorScore>();
        for (var node : array) scores.add(sectorScore(node, requireTrend));
        return scores;
    }

    private static SectorSummary sectorSummary(JsonNode node) {
        return sectorSummary(node, false);
    }

    private static SectorSummary sectorSummary(JsonNode node, boolean requireTrend) {
        var topSectorNode = node.get("topSector");
        return new SectorSummary(
                nullableInt(node, "averageBuyScore"),
                nullableInt(node, "averageBottomScore"),
                nullableInt(node, "averageBottomFailureRiskScore"),
                nullableInt(node, "averageVolumeConfirmationScore"),
                nullableInt(node, "averageAppealScore"),
                nullableInt(node, "averageCrowdingScore"),
                nullableInt(node, "averageQualityScore"),
                nullableInt(node, "averageRotationScore"),
                topSectorNode == null || topSectorNode.isNull() ? null : sectorScore(topSectorNode, requireTrend)
        );
    }

    private static SectorScore sectorScore(JsonNode node) {
        return sectorScore(node, false);
    }

    private static SectorScore sectorScore(JsonNode node, boolean requireTrend) {
        var trendPresent = node.has("buyScoreTrend");
        if (requireTrend && (!trendPresent || !node.has("buyScoreDelta7d") || !node.has("buyScoreDelta30d"))) {
            throw new IllegalArgumentException("Missing sector score history fields");
        }
        return new SectorScore(
                requiredText(node, "key"),
                requiredText(node, "label"),
                requiredText(node, "classification"),
                nullableDouble(node, "momentumScore"),
                nullableInt(node, "qualityScore"),
                nullableInt(node, "policySupport"),
                nullableInt(node, "structuralDemand"),
                nullableInt(node, "supplyTightness"),
                nullableInt(node, "marketConcentration"),
                nullableInt(node, "appealScore"),
                nullableInt(node, "crowdingScore"),
                nullableInt(node, "buyScore"),
                nullableText(node, "buyLabel"),
                requiredText(node, "stance"),
                nullableInt(node, "rotationScore"),
                nullableText(node, "rotationState"),
                nullableText(node, "rotationLabel"),
                textList(requiredArray(node, "rotationReasons")),
                nullableText(node, "bottomState"),
                nullableInt(node, "bottomScore"),
                nullableInt(node, "bottomFailureRiskScore"),
                nullableText(node, "actionLabel"),
                nullableText(node, "failureSummary"),
                nullableInt(node, "avgVolumeConfirmationScore"),
                node.has("avgVolumeConfirmationScore"),
                nullableDouble(node, "buyScoreDelta7d"),
                nullableDouble(node, "buyScoreDelta30d"),
                trendPresent ? nullableIntegerList(requiredArray(node, "buyScoreTrend")) : null,
                trendPresent
        );
    }

    private static RotationSector nullableRotationSector(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("Expected rotation object");
        return new RotationSector(
                requiredText(node, "key"),
                requiredText(node, "label"),
                requiredText(node, "classification"),
                requiredInt(node, "rotationScore"),
                requiredInt(node, "macroFitScore"),
                requiredInt(node, "relativeStrengthScore"),
                requiredInt(node, "fundamentalScore"),
                nullableInt(node, "valuationScore"),
                nullableInt(node, "earningsRevisionScore"),
                nullableInt(node, "flowScore"),
                requiredInt(node, "crowdingReliefScore"),
                requiredText(node, "state"),
                requiredText(node, "rotationLabel"),
                requiredText(node, "expectedLeadershipWindow"),
                requiredText(node, "expectedLeadershipMessage"),
                textList(requiredArray(node, "reasons"))
        );
    }

    private static RotationSummary nullableRotationSummary(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("Expected rotation summary object");
        return new RotationSummary(
                requiredText(node, "regime"),
                requiredInt(node, "confidence"),
                requiredText(node, "summary"),
                textList(requiredArray(node, "favoredNext")),
                textList(requiredArray(node, "fadingNext")),
                rotationCandidates(requiredArray(node, "currentLeaders")),
                rotationCandidates(requiredArray(node, "nextCandidates")),
                rotationCandidates(requiredArray(node, "secondaryCandidates")),
                rotationCandidates(requiredArray(node, "fadingCandidates"))
        );
    }

    private static List<RotationCandidate> rotationCandidates(JsonNode array) {
        var items = new ArrayList<RotationCandidate>();
        for (var node : array) {
            items.add(new RotationCandidate(
                    requiredText(node, "label"),
                    requiredText(node, "sectorKey"),
                    requiredInt(node, "rotationScore"),
                    requiredText(node, "state"),
                    requiredText(node, "rotationLabel"),
                    requiredText(node, "expectedLeadershipWindow"),
                    requiredText(node, "expectedLeadershipMessage"),
                    requiredText(node, "note")
            ));
        }
        return items;
    }

    private static DensitySummary densitySummary(JsonNode node) {
        return new DensitySummary(
                requiredInt(node, "peer"),
                requiredInt(node, "peerPct"),
                requiredInt(node, "narrative"),
                requiredInt(node, "narrativePct"),
                requiredInt(node, "fallback"),
                requiredInt(node, "fallbackPct"),
                requiredInt(node, "bottleneck"),
                requiredInt(node, "bottleneckPct"),
                requiredInt(node, "capitalFlow"),
                requiredInt(node, "capitalFlowPct")
        );
    }

    private static List<RelatedTheme> relatedThemes(JsonNode array) {
        var items = new ArrayList<RelatedTheme>();
        for (var node : array) {
            items.add(new RelatedTheme(requiredText(node, "id"), requiredText(node, "theme")));
        }
        return items;
    }

    private static List<String> textList(JsonNode array) {
        var values = new ArrayList<String>();
        for (var item : array) {
            if (!item.isString()) throw new IllegalArgumentException("Expected text array value");
            values.add(item.stringValue());
        }
        return values;
    }

    private static List<String> tickerList(JsonNode array) {
        var values = new java.util.LinkedHashSet<String>();
        for (var item : array) {
            if (!item.isString()) throw new IllegalArgumentException("Expected ticker array value");
            var ticker = currentTicker(item.stringValue());
            if (!CurrentResearchUniverseTickerRegistry.retired(ticker)) values.add(ticker);
        }
        return List.copyOf(values);
    }

    private static List<String> sectorTickerList(String sectorId, JsonNode array) {
        var values = new java.util.LinkedHashSet<>(tickerList(array));
        replacementsFor(sectorId, array).forEach(value -> values.add(value.ticker()));
        return List.copyOf(values);
    }

    private static List<SectorReplacement> replacementsFor(String sectorId, JsonNode sourceTickers) {
        var replacements = new ArrayList<SectorReplacement>();
        for (var item : sourceTickers) {
            String sourceTicker;
            if (item.isString()) {
                sourceTicker = currentTicker(item.stringValue());
            } else if (item.isObject() && item.has("ticker")) {
                sourceTicker = currentTicker(requiredText(item, "ticker"));
            } else {
                continue;
            }
            CurrentResearchUniverseTickerRegistry.replacementForRetired(sourceTicker)
                    .filter(value -> value.sectorId().equals(sectorId))
                    .ifPresent(replacements::add);
        }
        return List.copyOf(replacements);
    }

    private static String currentTicker(String value) {
        return CurrentResearchUniverseTickerRegistry.canonicalTicker(value);
    }

    private static List<Integer> nullableIntegerList(JsonNode array) {
        var values = new ArrayList<Integer>();
        for (var item : array) {
            if (item.isNull()) {
                values.add(null);
            } else if (item.isNumber()) {
                values.add(item.asInt());
            } else {
                throw new IllegalArgumentException("Expected nullable integer array value");
            }
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
        if (node == null || node.isNull()) return null;
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
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("Expected nullable number field: " + field);
        return node.asInt();
    }

    private static Double nullableDouble(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("Expected nullable number field: " + field);
        return node.asDouble();
    }

    private static Number nullableNumber(JsonNode parent, String field) {
        var node = parent.get(field);
        if (node == null || node.isNull()) return null;
        if (!node.isNumber()) throw new IllegalArgumentException("Expected nullable number field: " + field);
        var value = node.asDouble();
        if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            return (long) value;
        }
        return BigDecimal.valueOf(value);
    }
}
