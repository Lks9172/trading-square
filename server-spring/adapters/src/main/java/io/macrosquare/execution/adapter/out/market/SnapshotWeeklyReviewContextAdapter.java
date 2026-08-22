package io.macrosquare.execution.adapter.out.market;

import io.macrosquare.execution.application.model.WeeklyReviewMarketContext;
import io.macrosquare.execution.application.model.WeeklyReviewMarketContext.MarketEvent;
import io.macrosquare.execution.application.model.WeeklyReviewMarketContext.MarketSignal;
import io.macrosquare.execution.application.port.out.LoadWeeklyReviewMarketContextPort;
import io.macrosquare.market.application.model.MarketReadModels.ArrayValue;
import io.macrosquare.market.application.model.MarketReadModels.BooleanValue;
import io.macrosquare.market.application.model.MarketReadModels.NumberValue;
import io.macrosquare.market.application.model.MarketReadModels.ObjectValue;
import io.macrosquare.market.application.model.MarketReadModels.StructuredValue;
import io.macrosquare.market.application.model.MarketReadModels.TextValue;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Anti-corruption adapter from the evolving snapshot document to weekly-review inputs. */
public final class SnapshotWeeklyReviewContextAdapter implements LoadWeeklyReviewMarketContextPort {

    private final LoadMarketSnapshotProjectionPort snapshots;

    public SnapshotWeeklyReviewContextAdapter(LoadMarketSnapshotProjectionPort snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots);
    }

    @Override
    public WeeklyReviewMarketContext loadCurrent() {
        var root = snapshots.loadCurrentOrSeed().root();
        var regime = object(root.fields().get("regime"));
        var allocation = object(root.fields().get("allocation"));
        var meta = object(root.fields().get("meta"));
        var derived = object(root.fields().get("derived"));
        return new WeeklyReviewMarketContext(
                instant(root.fields().get("timestamp")),
                text(regime.fields().get("regime"), "UNKNOWN"),
                integer(regime.fields().get("score"), 0),
                allocations(object(allocation.fields().get("allocations"))),
                signals(root.fields().get("signals")),
                warnings(derived, meta),
                events(meta.fields().get("calendar"))
        );
    }

    private static Map<String, Integer> allocations(ObjectValue source) {
        var result = new LinkedHashMap<String, Integer>();
        source.fields().forEach((key, value) -> {
            if (value instanceof NumberValue number) result.put(key, number.value().intValue());
        });
        return result;
    }

    private static List<MarketSignal> signals(StructuredValue source) {
        if (!(source instanceof ArrayValue values)) return List.of();
        var result = new ArrayList<MarketSignal>();
        for (var entry : values.values()) {
            var signal = object(entry);
            var asset = text(signal.fields().get("asset"), "");
            if (asset.isBlank()) continue;
            var conditionsTotal = integer(signal.fields().get("conditionsTotal"), 0);
            result.add(new MarketSignal(
                    asset,
                    text(signal.fields().get("signal"), "HOLD"),
                    integer(signal.fields().get("conditionsMet"), 0),
                    conditionsTotal,
                    legacyCompatibleCoverage(signal, conditionsTotal),
                    texts(signal.fields().get("missingReasons")),
                    texts(signal.fields().get("reasons")),
                    texts(signal.fields().get("unmetReasons"))
            ));
        }
        return result;
    }

    private static List<String> warnings(ObjectValue derived, ObjectValue meta) {
        var values = new ArrayList<String>();
        addWarning(values, derived, "TAIL_RISK_LEVEL", value -> value >= 1);
        addWarning(values, derived, "FEDERAL_DEFICIT_GDP_TIER", value -> value < 0);
        addWarning(values, derived, "FEDERAL_DEBT_GDP_TIER", value -> value < 0);
        addWarning(values, derived, "LIQUIDITY_PLUMBING_SIGNAL", value -> value <= -1);

        var staleness = object(meta.fields().get("staleness"));
        var stale = staleness.fields().entrySet().stream()
                .filter(entry -> {
                    var point = object(entry.getValue());
                    var eligible = point.fields().get("eligibleForSignals");
                    if (eligible instanceof BooleanValue value) return !value.value();
                    var days = integer(point.fields().get("daysAgo"), 0);
                    var maximumAge = integer(point.fields().get("maximumAgeDays"), -1);
                    if (maximumAge >= 0) return days > maximumAge;
                    var frequency = text(point.fields().get("frequency"), "");
                    return (frequency.equals("일간") && days > 5)
                            || (frequency.equals("주간") && days > 14)
                            || (frequency.equals("월간") && days > 75)
                            || (frequency.equals("분기") && days > 270);
                })
                .map(Map.Entry::getKey)
                .sorted()
                .limit(5)
                .toList();
        if (!stale.isEmpty()) values.add("갱신 지연 데이터: " + String.join(", ", stale));
        return List.copyOf(values);
    }

    private static void addWarning(
            List<String> target,
            ObjectValue derived,
            String key,
            java.util.function.DoublePredicate predicate
    ) {
        var point = object(derived.fields().get(key));
        if (point.fields().get("eligibleForSignals") instanceof BooleanValue eligible && !eligible.value()) return;
        var value = decimal(point.fields().get("value"));
        if (value == null || !predicate.test(value)) return;
        var interpretation = text(point.fields().get("interpretation"), "");
        target.add(interpretation.isBlank() ? key + "=" + format(value) : interpretation);
    }

    private static List<MarketEvent> events(StructuredValue source) {
        if (!(source instanceof ArrayValue values)) return List.of();
        var result = new ArrayList<MarketEvent>();
        for (var entry : values.values()) {
            var event = object(entry);
            var date = text(event.fields().get("date"), "");
            var name = text(event.fields().get("name"), "");
            if (date.isBlank() || name.isBlank()) continue;
            try {
                result.add(new MarketEvent(
                        LocalDate.parse(date),
                        name,
                        text(event.fields().get("category"), "OTHER"),
                        text(event.fields().get("importance"), "medium")
                ));
            } catch (java.time.DateTimeException ignored) {
                // Invalid legacy calendar rows are omitted instead of poisoning the report.
            }
        }
        return result;
    }

    private static List<String> texts(StructuredValue source) {
        if (!(source instanceof ArrayValue values)) return List.of();
        return values.values().stream().filter(TextValue.class::isInstance)
                .map(TextValue.class::cast).map(TextValue::value).toList();
    }

    private static ObjectValue object(StructuredValue value) {
        return value instanceof ObjectValue object ? object : new ObjectValue(Map.of());
    }

    private static Instant instant(StructuredValue value) {
        var timestamp = text(value, "");
        try {
            return Instant.parse(timestamp);
        } catch (java.time.DateTimeException ignored) {
            return Instant.EPOCH;
        }
    }

    private static String text(StructuredValue value, String fallback) {
        return value instanceof TextValue text ? text.value() : fallback;
    }

    private static int integer(StructuredValue value, int fallback) {
        return value instanceof NumberValue number ? number.value().intValue() : fallback;
    }

    private static int legacyCompatibleCoverage(ObjectValue signal, int conditionsTotal) {
        var value = signal.fields().get("dataCoveragePct");
        if (value instanceof NumberValue number) return number.value().intValue();
        // Snapshots written before coverage tracking had no missing-data representation.
        // Preserve their prior semantics until the first refresh instead of reporting a fake 0%.
        return conditionsTotal > 0 ? 100 : 0;
    }

    private static Double decimal(StructuredValue value) {
        return value instanceof NumberValue number ? number.value().doubleValue() : null;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : Double.toString(value);
    }
}
