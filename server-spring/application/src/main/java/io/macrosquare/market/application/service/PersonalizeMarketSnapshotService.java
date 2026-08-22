package io.macrosquare.market.application.service;

import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.Document;
import io.macrosquare.market.application.model.MarketReadModels.NullValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.model.CurrentMarketDecisionContext;
import io.macrosquare.market.application.port.in.PersonalizeMarketSnapshotUseCase;
import io.macrosquare.market.application.port.out.BuildCurrentExecutionPlansPort;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.market.domain.allocation.CoreAllocationPolicy;
import io.macrosquare.market.domain.observation.MarketInputFreshnessPolicy;
import io.macrosquare.market.domain.regime.MacroRegimeEvidence;
import io.macrosquare.market.domain.regime.MacroRegimePolicy;
import io.macrosquare.market.domain.signal.CoreAssetSignalPolicy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Re-evaluates regime, signals and allocation for one user profile without mutating the default snapshot. */
public final class PersonalizeMarketSnapshotService implements PersonalizeMarketSnapshotUseCase {

    private static final Set<String> HORIZONS = Set.of("short", "medium", "long");
    private static final Set<String> RISK_TOLERANCES = Set.of("conservative", "moderate", "aggressive");

    private final LoadMarketSnapshotProjectionPort snapshotPort;
    private final MacroRegimePolicy regimePolicy;
    private final CoreAssetSignalPolicy signalPolicy;
    private final CoreAllocationPolicy allocationPolicy;
    private final Clock clock;
    private final MarketInputFreshnessPolicy freshnessPolicy;
    private final BuildCurrentExecutionPlansPort currentExecutionPlans;

    public PersonalizeMarketSnapshotService(
            LoadMarketSnapshotProjectionPort snapshotPort,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock
    ) {
        this(snapshotPort, regimePolicy, signalPolicy, allocationPolicy, clock,
                new MarketInputFreshnessPolicy(), context -> null);
    }

    public PersonalizeMarketSnapshotService(
            LoadMarketSnapshotProjectionPort snapshotPort,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock,
            MarketInputFreshnessPolicy freshnessPolicy
    ) {
        this(snapshotPort, regimePolicy, signalPolicy, allocationPolicy, clock,
                freshnessPolicy, context -> null);
    }

    public PersonalizeMarketSnapshotService(
            LoadMarketSnapshotProjectionPort snapshotPort,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock,
            MarketInputFreshnessPolicy freshnessPolicy,
            BuildCurrentExecutionPlansPort currentExecutionPlans
    ) {
        this.snapshotPort = Objects.requireNonNull(snapshotPort);
        this.regimePolicy = Objects.requireNonNull(regimePolicy);
        this.signalPolicy = Objects.requireNonNull(signalPolicy);
        this.allocationPolicy = Objects.requireNonNull(allocationPolicy);
        this.clock = Objects.requireNonNull(clock);
        this.freshnessPolicy = Objects.requireNonNull(freshnessPolicy);
        this.currentExecutionPlans = Objects.requireNonNull(currentExecutionPlans);
    }

    @Override
    public Document personalize(Document profileOverrides) {
        var base = snapshotPort.loadCurrentOrSeed();
        var root = mutable(base.root());
        var request = profileOverrides.root().fields();
        var meta = mutableObject(root.get("meta"), "meta");
        var previousProfile = optionalObject(meta.get("profile"));
        var mergedProfile = new LinkedHashMap<>(previousProfile);
        copy(request, mergedProfile, "riskTolerance");
        copy(request, mergedProfile, "investmentHorizon");
        copy(request, mergedProfile, "leverageEnabled");
        copy(request, mergedProfile, "includeCrypto");
        copy(request, mergedProfile, "includeKR");

        var horizon = allowedText(mergedProfile.get("investmentHorizon"), "long", HORIZONS,
                "investmentHorizon");
        allowedText(mergedProfile.get("riskTolerance"), "moderate", RISK_TOLERANCES, "riskTolerance");
        var leverageEnabled = bool(mergedProfile.get("leverageEnabled"), true);
        var includeKorea = bool(mergedProfile.get("includeKR"), true);

        var automatic = optionalObject(meta.get("autoInputs"));
        var previousManual = optionalObject(previousProfile.get("manualInputs"));
        var requestedManual = optionalObject(request.get("manualInputs"));
        var manual = new LinkedHashMap<>(previousManual);
        requestedManual.forEach(manual::put);
        var defaultControls = integer(manual.get("policyDirection"), 0) == 0
                && integer(manual.get("geoRisk"), 2) == 2
                && bool(manual.get("cbBuying"), true);
        var effective = defaultControls ? automatic : manual;
        var policyDirection = bounded(integer(effective.get("policyDirection"), 0), -2, 2, "policyDirection");
        var geoRisk = bounded(integer(effective.get("geoRisk"), 2), 0, 3, "geoRisk");
        mergedProfile.put("manualInputs", new ObjectValue(manual));

        var asOf = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        var raw = freshNumbers(mutableObject(root.get("raw"), "raw"), asOf, true);
        var derived = freshNumbers(mutableObject(root.get("derived"), "derived"), asOf, false);
        var smartMoney = optionalObject(meta.get("smartMoney"));
        var smartMoneyInput = SmartMoneyFreshnessResolver.resolve(smartMoney, asOf, freshnessPolicy);
        meta.put("smartMoneyFreshness", SmartMoneyFreshnessResolver.metadata(smartMoneyInput));
        var regime = regimePolicy.evaluate(new MacroRegimeEvidence(
                raw, derived, policyDirection, geoRisk, smartMoneyInput.scoreForDecision()), asOf);
        var signals = signalPolicy.evaluate(raw, derived, regime, asOf);
        var allocation = allocationPolicy.evaluate(
                regime, signals, raw, derived, horizon, leverageEnabled, includeKorea, asOf);
        var calculatedAt = clock.instant();
        var plans = currentExecutionPlans.build(new CurrentMarketDecisionContext(
                calculatedAt, regime.regime().name(), regime.score(), raw, derived,
                allocation.allocations(), signals.stream().map(signal ->
                        new CurrentMarketDecisionContext.Signal(
                                signal.asset(), signal.action().name(), signal.dataCoveragePct(),
                                signal.reasons(), signal.unmetReasons())).toList()));

        meta.put("profile", new ObjectValue(mergedProfile));
        meta.put("manualInputs", new ObjectValue(new LinkedHashMap<>(effective)));
        meta.put("inputMode", text(defaultControls ? "auto" : "manual"));
        if (plans != null) {
            meta.put("executionPlans", plans);
            meta.put("executionPlanFreshness", RefreshMarketSnapshotService.executionPlanFreshness(calculatedAt));
        }
        root.put("timestamp", text(calculatedAt.toString()));
        root.put("regime", regime(regime));
        root.put("signals", signals(signals));
        root.put("allocation", allocation(allocation));
        root.put("meta", new ObjectValue(meta));
        return new Document(new ObjectValue(root));
    }

    private static void copy(Map<String, StructuredValue> source, Map<String, StructuredValue> target, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private static String allowedText(StructuredValue value, String fallback, Set<String> allowed, String field) {
        var result = value instanceof TextValue text ? text.value() : fallback;
        if (!allowed.contains(result)) throw new IllegalArgumentException(field + " is invalid");
        return result;
    }

    private static int bounded(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(field + " is out of range");
        return value;
    }

    private static ObjectValue regime(io.macrosquare.market.domain.regime.MacroRegimeAssessment value) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("regime", text(value.regime().name()));
        fields.put("score", number(value.score()));
        var components = new LinkedHashMap<String, StructuredValue>();
        value.components().forEach((key, score) -> components.put(key, number(score)));
        fields.put("components", new ObjectValue(components));
        fields.put("date", text(value.date().toString()));
        return new ObjectValue(fields);
    }

    private static ArrayValue signals(List<io.macrosquare.market.domain.signal.CoreAssetSignal> values) {
        var result = new ArrayList<StructuredValue>();
        for (var value : values) {
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("asset", text(value.asset()));
            fields.put("signal", text(value.action().name()));
            fields.put("conditionsMet", number(value.conditionsMet()));
            fields.put("conditionsTotal", number(value.conditionsTotal()));
            fields.put("conditionsAvailable", number(value.conditionsAvailable()));
            fields.put("weightedScore", number(value.weightedScore()));
            fields.put("weightedMaxScore", number(value.weightedMaxScore()));
            fields.put("dataCoveragePct", number(value.dataCoveragePct()));
            fields.put("reasons", texts(value.reasons()));
            fields.put("unmetReasons", texts(value.unmetReasons()));
            fields.put("missingReasons", texts(value.missingReasons()));
            fields.put("date", text(value.date().toString()));
            if (value.asset().equals("LEVERAGE")) {
                fields.put("tier", value.leverageTier() == null ? NullValue.INSTANCE : text(value.leverageTier()));
            }
            var explanation = new LinkedHashMap<String, StructuredValue>();
            explanation.put("baseSignal", text(value.action().name()));
            explanation.put("finalSignal", text(value.action().name()));
            explanation.put("overrides", new ArrayValue(List.of()));
            explanation.put("macroReasons", texts(value.reasons().stream().limit(3).toList()));
            explanation.put("timingNotes", texts(value.unmetReasons().stream().limit(3).toList()));
            fields.put("explanation", new ObjectValue(explanation));
            result.add(new ObjectValue(fields));
        }
        return new ArrayValue(result);
    }

    private static ObjectValue allocation(io.macrosquare.market.domain.allocation.CoreAllocationPlan value) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("regime", text(value.regime().name()));
        fields.put("score", number(value.score()));
        var allocations = new LinkedHashMap<String, StructuredValue>();
        value.allocations().forEach((key, amount) -> allocations.put(key, number(amount)));
        fields.put("allocations", new ObjectValue(allocations));
        fields.put("leverageAllowed", new BooleanValue(value.leverageAllowed()));
        fields.put("buyStage", value.buyStage() == null ? NullValue.INSTANCE : number(value.buyStage()));
        fields.put("date", text(value.date().toString()));
        return new ObjectValue(fields);
    }

    private Map<String, Double> freshNumbers(
            Map<String, StructuredValue> objects,
            LocalDate asOf,
            boolean raw
    ) {
        var values = new LinkedHashMap<String, Double>();
        objects.forEach((key, rawValue) -> {
            if (!(rawValue instanceof ObjectValue object)) return;
            var value = object.fields().get("value");
            if (!(value instanceof NumberValue number)) return;
            var date = localDate(object.fields().get("date"));
            var usable = raw ? freshnessPolicy.usableRaw(key, date, asOf)
                    : freshnessPolicy.usableDerived(key, date, asOf);
            if (usable) values.put(key, number.value().doubleValue());
        });
        return Map.copyOf(values);
    }

    private static LocalDate localDate(StructuredValue value) {
        if (!(value instanceof TextValue text)) return null;
        try {
            return LocalDate.parse(text.value());
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    private static LinkedHashMap<String, StructuredValue> mutable(ObjectValue value) {
        return new LinkedHashMap<>(value.fields());
    }

    private static LinkedHashMap<String, StructuredValue> mutableObject(StructuredValue value, String field) {
        if (!(value instanceof ObjectValue object)) throw new IllegalArgumentException(field + " must be an object");
        return mutable(object);
    }

    private static Map<String, StructuredValue> optionalObject(StructuredValue value) {
        return value instanceof ObjectValue object ? object.fields() : Map.of();
    }

    private static int integer(StructuredValue value, int fallback) {
        return value instanceof NumberValue number ? number.value().intValue() : fallback;
    }

    private static boolean bool(StructuredValue value, boolean fallback) {
        return value instanceof BooleanValue bool ? bool.value() : fallback;
    }

    private static TextValue text(String value) { return new TextValue(value); }
    private static NumberValue number(long value) { return new NumberValue(value); }
    private static NumberValue number(double value) { return new NumberValue(BigDecimal.valueOf(value)); }
    private static ArrayValue texts(List<String> values) {
        return new ArrayValue(values.stream().map(PersonalizeMarketSnapshotService::text)
                .map(StructuredValue.class::cast).toList());
    }
}
