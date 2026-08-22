package io.macrosquare.market.adapter.out.research;

import io.macrosquare.market.application.model.CurrentMarketDecisionContext;
import io.macrosquare.market.application.model.CurrentTopdownProjection;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.NullValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.EvaluateCurrentTopdownPort;
import io.macrosquare.research.application.model.CurrentSectorRotationAssessment;
import io.macrosquare.research.application.port.in.CurrentSectorRotationCommand;
import io.macrosquare.research.application.port.in.CaptureSectorRotationSnapshotUseCase;
import io.macrosquare.research.application.port.in.EvaluateCurrentSectorRotationUseCase;
import io.macrosquare.research.domain.rotation.SectorRotationItem;
import io.macrosquare.research.domain.rotation.SectorRotationOutlookBucket;
import io.macrosquare.research.domain.rotation.SectorRotationState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Outer anti-corruption adapter between current market evidence and research rotation policy. */
public final class ResearchTopdownBridgeAdapter implements EvaluateCurrentTopdownPort {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(
            ResearchTopdownBridgeAdapter.class);

    private final EvaluateCurrentSectorRotationUseCase rotation;
    private final CaptureSectorRotationSnapshotUseCase snapshots;

    public ResearchTopdownBridgeAdapter(EvaluateCurrentSectorRotationUseCase rotation) {
        this(rotation, CaptureSectorRotationSnapshotUseCase.unavailable());
    }

    public ResearchTopdownBridgeAdapter(
            EvaluateCurrentSectorRotationUseCase rotation,
            CaptureSectorRotationSnapshotUseCase snapshots
    ) {
        this.rotation = Objects.requireNonNull(rotation);
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    @Override
    public CurrentTopdownProjection evaluate(CurrentMarketDecisionContext context) {
        var assessment = rotation.evaluate(new CurrentSectorRotationCommand(
                context.calculatedAt().toString(), context.rawValues(), context.derivedValues(),
                context.rawObservedOn(), context.derivedObservedOn(), context.regime()));
        try {
            if (snapshots.capture(assessment)) {
                LOGGER.info("Immutable sector rotation composite snapshot captured (calculatedAt={})",
                        assessment.calculatedAt());
            }
        } catch (RuntimeException error) {
            // Validation persistence must remain observable without making the
            // user-facing current snapshot unavailable.
            LOGGER.error(
                    "Sector rotation validation capture failed "
                            + "(errorType={}, reason={}, universeSize={}, rotationItems={})",
                    error.getClass().getSimpleName(),
                    error.getMessage(),
                    assessment.universeSize(),
                    assessment.rotation().sectors().size(),
                    error
            );
        }
        return new CurrentTopdownProjection(
                topdown(assessment, context.signals()),
                assessment.currentMomentumCoverage(), assessment.universeSize());
    }

    private static ObjectValue topdown(
            CurrentSectorRotationAssessment assessment,
            List<CurrentMarketDecisionContext.Signal> signals
    ) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("summary", text(assessment.rotation().summary()
                + " 현재 모멘텀 " + assessment.currentMomentumCoverage() + "/"
                + assessment.universeSize() + "개 축을 사용했습니다."));
        var favored = assessment.rotation().sectors().stream()
                .filter(item -> item.state() == SectorRotationState.LEADING
                        || item.state() == SectorRotationState.IMPROVING)
                .filter(item -> {
                    var profile = assessment.profiles().get(item.key());
                    return profile != null
                            && (profile.buyScore() == null || profile.buyScore() >= 55)
                            && (profile.crowdingScore() == null || profile.crowdingScore() < 75);
                })
                .limit(4).map(item -> sectorProfile(assessment, item)).toList();
        var avoided = assessment.rotation().sectors().stream()
                .filter(item -> item.state() == SectorRotationState.WEAKENING
                        || item.state() == SectorRotationState.LAGGING)
                .filter(item -> {
                    var profile = assessment.profiles().get(item.key());
                    return item.rotationScore() < 60 || profile != null
                            && profile.buyScore() != null && profile.buyScore() < 55;
                })
                .limit(3).map(item -> sectorProfile(assessment, item)).toList();
        fields.put("favoredSectors", new ArrayValue(favored));
        fields.put("avoidedSectors", new ArrayValue(avoided));
        fields.put("assetRationale", assetRationale(signals));
        fields.put("rotation", rotation(assessment));
        return new ObjectValue(fields);
    }

    private static StructuredValue sectorProfile(
            CurrentSectorRotationAssessment assessment,
            SectorRotationItem item
    ) {
        var profile = assessment.profiles().get(item.key());
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("key", text(item.key()));
        fields.put("label", text(item.label()));
        fields.put("classification", text(item.classification().name().toLowerCase(Locale.ROOT)));
        fields.put("score", nullableNumber(profile == null ? null : profile.mediumTermRelativeStrength()));
        fields.put("shortTermScore", nullableNumber(profile == null ? null : profile.shortTermRelativeStrength()));
        fields.put("longTermScore", nullableNumber(profile == null ? null : profile.mediumTermRelativeStrength()));
        fields.put("earningsRevisionScore", nullableNumber(profile == null ? null : profile.earningsRevisionScore()));
        fields.put("valuationScore", nullableNumber(profile == null ? null : profile.valuationScore()));
        var quality = new LinkedHashMap<String, StructuredValue>();
        quality.put("policySupport", nullableNumber(profile == null ? null : profile.policySupport()));
        quality.put("structuralDemand", nullableNumber(profile == null ? null : profile.structuralDemand()));
        quality.put("supplyTightness", nullableNumber(profile == null ? null : profile.supplyTightness()));
        quality.put("marketConcentration", nullableNumber(profile == null ? null : profile.marketConcentration()));
        quality.put("totalScore", nullableNumber(profile == null ? null : profile.qualityScore()));
        fields.put("quality", new ObjectValue(quality));
        fields.put("stance", text(item.state() == SectorRotationState.LEADING
                || item.state() == SectorRotationState.IMPROVING ? "favored" : "avoided"));
        fields.put("appealScore", nullableNumber(profile == null ? null : profile.appealScore()));
        fields.put("crowdingScore", nullableNumber(profile == null ? null : profile.crowdingScore()));
        fields.put("buyScore", nullableNumber(profile == null ? null : profile.buyScore()));
        fields.put("buyLabel", text(profile == null || profile.buyLabel() == null ? "근거 확인" : profile.buyLabel()));
        fields.put("reasons", texts(item.reasons()));
        return new ObjectValue(fields);
    }

    private static ArrayValue assetRationale(List<CurrentMarketDecisionContext.Signal> signals) {
        var values = new ArrayList<StructuredValue>();
        for (var signal : signals) {
            var macro = new ArrayList<String>();
            var sector = new ArrayList<String>();
            var flow = new ArrayList<String>();
            var timing = new ArrayList<String>();
            for (var reason : signal.reasons()) categorize(reason, macro, sector, flow, timing);
            signal.unmetReasons().stream().limit(2).forEach(timing::add);
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("asset", text(signal.asset()));
            fields.put("label", text(assetLabel(signal.asset())));
            fields.put("macroReasons", texts(macro.stream().limit(3).toList()));
            fields.put("sectorReasons", texts(sector.stream().limit(3).toList()));
            fields.put("flowReasons", texts(flow.stream().limit(3).toList()));
            fields.put("timingNotes", texts(timing.stream().limit(3).toList()));
            values.add(new ObjectValue(fields));
        }
        return new ArrayValue(values);
    }

    private static void categorize(
            String reason,
            List<String> macro,
            List<String> sector,
            List<String> flow,
            List<String> timing
    ) {
        var value = reason.toLowerCase(Locale.ROOT);
        if (value.contains("섹터") || value.contains("반도체") || value.contains("산업재")
                || value.contains("소재")) sector.add(reason);
        else if (value.contains("수급") || value.contains("외국인") || value.contains("기관")) flow.add(reason);
        else if (value.contains("이격") || value.contains("200일") || value.contains("rsi")
                || value.contains("가격구조") || value.contains("추세") || value.contains("피보")
                || value.contains("과열")) timing.add(reason);
        else macro.add(reason);
    }

    private static ObjectValue rotation(CurrentSectorRotationAssessment assessment) {
        var view = assessment.rotation();
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("regime", text(view.regime().name()));
        fields.put("confidence", number(view.confidence()));
        var scores = new LinkedHashMap<String, StructuredValue>();
        view.regimeScores().forEach((key, value) -> scores.put(key.name(), number(value)));
        fields.put("regimeScores", new ObjectValue(scores));
        fields.put("summary", text(view.summary()));
        fields.put("favoredNext", texts(view.favoredNext()));
        fields.put("fadingNext", texts(view.fadingNext()));
        fields.put("currentLeaders", rotationBuckets(view.currentLeaders()));
        fields.put("nextCandidates", rotationBuckets(view.nextCandidates()));
        fields.put("secondaryCandidates", rotationBuckets(view.secondaryCandidates()));
        fields.put("fadingCandidates", rotationBuckets(view.fadingCandidates()));
        var sectors = new ArrayList<StructuredValue>();
        view.sectors().forEach(item -> sectors.add(rotationSector(item)));
        fields.put("sectors", new ArrayValue(sectors));
        return new ObjectValue(fields);
    }

    private static ArrayValue rotationBuckets(List<SectorRotationOutlookBucket> values) {
        var result = new ArrayList<StructuredValue>();
        for (var value : values) {
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("label", text(value.label()));
            fields.put("sectorKey", text(value.sectorKey()));
            fields.put("rotationScore", number(value.rotationScore()));
            fields.put("state", text(value.state().name()));
            fields.put("rotationLabel", text(value.rotationLabel().displayName()));
            fields.put("expectedLeadershipWindow", text(value.expectedLeadershipWindow().code()));
            fields.put("expectedLeadershipMessage", text(value.expectedLeadershipMessage()));
            fields.put("note", text(value.note()));
            result.add(new ObjectValue(fields));
        }
        return new ArrayValue(result);
    }

    private static ObjectValue rotationSector(SectorRotationItem item) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("key", text(item.key()));
        fields.put("label", text(item.label()));
        fields.put("classification", text(item.classification().name().toLowerCase(Locale.ROOT)));
        fields.put("rotationScore", number(item.rotationScore()));
        fields.put("macroFitScore", number(item.macroFitScore()));
        fields.put("relativeStrengthScore", number(item.relativeStrengthScore()));
        fields.put("fundamentalScore", number(item.fundamentalScore()));
        fields.put("valuationScore", nullableNumber(item.valuationScore()));
        fields.put("earningsRevisionScore", number(item.earningsRevisionScore()));
        fields.put("flowScore", nullableNumber(item.flowScore()));
        fields.put("crowdingReliefScore", number(item.crowdingReliefScore()));
        fields.put("state", text(item.state().name()));
        fields.put("rotationLabel", text(item.rotationLabel().displayName()));
        fields.put("expectedLeadershipWindow", text(item.expectedLeadershipWindow().code()));
        fields.put("expectedLeadershipMessage", text(item.expectedLeadershipMessage()));
        fields.put("reasons", texts(item.reasons()));
        return new ObjectValue(fields);
    }

    private static String assetLabel(String asset) {
        return switch (asset) {
            case "NASDAQ" -> "나스닥";
            case "KOSPI" -> "코스피";
            case "GOLD" -> "금";
            case "SILVER" -> "은";
            case "COPPER" -> "구리";
            case "EMERGING" -> "신흥국";
            case "LEVERAGE" -> "레버리지";
            case "CASH" -> "현금";
            default -> asset;
        };
    }

    private static StructuredValue nullableNumber(Number value) {
        return value == null ? NullValue.INSTANCE : number(value.doubleValue());
    }

    private static TextValue text(String value) { return new TextValue(value); }
    private static NumberValue number(long value) { return new NumberValue(value); }
    private static NumberValue number(double value) { return new NumberValue(BigDecimal.valueOf(value)); }
    private static ArrayValue texts(List<String> values) {
        return new ArrayValue(values.stream().map(TextValue::new).map(StructuredValue.class::cast).toList());
    }
}
