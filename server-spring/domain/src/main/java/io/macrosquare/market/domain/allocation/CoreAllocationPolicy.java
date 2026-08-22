package io.macrosquare.market.domain.allocation;

import io.macrosquare.market.domain.regime.MacroRegimeAssessment;
import io.macrosquare.market.domain.regime.MacroRegime;
import io.macrosquare.market.domain.signal.CoreAssetSignal;
import io.macrosquare.market.domain.signal.CoreSignalAction;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CoreAllocationPolicy {

    private static final Map<MacroRegime, Map<String, Double>> BASE = Map.ofEntries(
            Map.entry(MacroRegime.RISK_ON, base(8, 32, 0, 13, 10, 6, 18, 13)),
            Map.entry(MacroRegime.NEUTRAL, base(12, 38, 0, 24, 6, 5, 10, 5)),
            Map.entry(MacroRegime.CAUTION, base(33, 25, 0, 28, 1, 5, 8, 0)),
            Map.entry(MacroRegime.CORRECTION, base(30, 25, 0, 19, 5, 5, 11, 5)),
            Map.entry(MacroRegime.PANIC_BUT_OK, base(15, 35, 10, 20, 5, 5, 5, 5)),
            Map.entry(MacroRegime.RECESSION_RISK, base(50, 15, 0, 25, 0, 0, 5, 5)),
            Map.entry(MacroRegime.STAGFLATION, base(27, 15, 0, 33, 5, 5, 8, 7)),
            Map.entry(MacroRegime.BOND_VIGILANTE, base(31, 10, 0, 37, 5, 3, 7, 7)),
            Map.entry(MacroRegime.STAGFLATION_BOND_VIGILANTE, base(35, 10, 0, 37, 5, 3, 5, 5))
    );
    private static final Map<CoreSignalAction, Double> MULTIPLIER = Map.of(
            CoreSignalAction.STRONG_BUY, 1.3,
            CoreSignalAction.BUY, 1.1,
            CoreSignalAction.HOLD, 1.0,
            CoreSignalAction.REDUCE, .7,
            CoreSignalAction.SELL, .3
    );
    private static final Map<MacroRegime, Double> MINIMUM_CASH_PCT = Map.ofEntries(
            Map.entry(MacroRegime.RISK_ON, 5d),
            Map.entry(MacroRegime.NEUTRAL, 8d),
            Map.entry(MacroRegime.CAUTION, 20d),
            Map.entry(MacroRegime.CORRECTION, 20d),
            Map.entry(MacroRegime.PANIC_BUT_OK, 10d),
            Map.entry(MacroRegime.RECESSION_RISK, 35d),
            Map.entry(MacroRegime.STAGFLATION, 25d),
            Map.entry(MacroRegime.BOND_VIGILANTE, 25d),
            Map.entry(MacroRegime.STAGFLATION_BOND_VIGILANTE, 30d)
    );
    private static final Map<String, String> SIGNAL_KEYS = Map.of(
            "NASDAQ", "nasdaq", "GOLD", "gold", "SILVER", "silver", "COPPER", "copper",
            "CASH", "cash", "KOSPI", "korea", "EMERGING", "emerging"
    );

    public CoreAllocationPlan evaluate(
            MacroRegimeAssessment regime,
            List<CoreAssetSignal> signals,
            Map<String, Double> raw,
            Map<String, Double> derived,
            String horizon,
            boolean leverageEnabled,
            boolean includeKorea,
            LocalDate asOf
    ) {
        var values = new LinkedHashMap<>(BASE.get(regime.regime()));
        applyHorizon(values, horizon);
        if (!includeKorea) move(values, "korea", "cash", values.getOrDefault("korea", 0d));
        for (var signal : signals) {
            var key = SIGNAL_KEYS.get(signal.asset());
            if (key != null) values.computeIfPresent(key, (ignored, value) -> value * MULTIPLIER.get(signal.action()));
        }
        var fx = derived.get("KRW_FX_LEVEL");
        if (fx != null && fx <= -2) move(values, "korea", "cash", values.getOrDefault("korea", 0d) * .5);
        else if (fx != null && fx <= -1) move(values, "korea", "cash", values.getOrDefault("korea", 0d) * .3);
        if (equal(derived.get("FX_FOREIGN_COMBO_ALERT"), 2)) {
            move(values, "emerging", "cash", values.getOrDefault("emerging", 0d) * .3);
        }

        if (one(derived, "FISCAL_STRESS_HARD") || one(derived, "FISCAL_STRESS")) {
            defend(values, one(derived, "FISCAL_STRESS_HARD") ? 15 : 8, .6);
        } else if (one(derived, "OVERHEATED")) {
            defend(values, 25, .8);
        } else if (equal(derived.get("GOLDILOCKS_ZONE"), -1)) {
            defend(values, 10, .7);
        }

        var leverage = signals.stream().filter(item -> item.asset().equals("LEVERAGE")).findFirst().orElse(null);
        var cap = !leverageEnabled || leverage == null ? 0
                : "HARD".equals(leverage.leverageTier()) ? 15
                : "MEDIUM".equals(leverage.leverageTier()) ? 10
                : "SOFT".equals(leverage.leverageTier()) ? 5 : 0;
        var leverageAllowed = cap > 0 && (leverage.action() == CoreSignalAction.BUY
                || leverage.action() == CoreSignalAction.STRONG_BUY);
        if (!leverageAllowed) move(values, "leverage", "nasdaq", values.getOrDefault("leverage", 0d));
        else if (values.getOrDefault("leverage", 0d) == 0) {
            var amount = Math.min(cap, values.getOrDefault("nasdaq", 0d) * .5);
            move(values, "nasdaq", "leverage", amount);
        }

        enforceMinimumCash(values, MINIMUM_CASH_PCT.getOrDefault(regime.regime(), 0d));
        var normalized = normalize(values);
        if (normalized.getOrDefault("leverage", 0) > cap) {
            var excess = normalized.get("leverage") - cap;
            normalized.put("leverage", cap);
            normalized.compute("cash", (ignored, current) -> current + excess);
        }
        return new CoreAllocationPlan(regime.regime(), regime.score(), normalized, leverageAllowed,
                buyStage(raw, derived), asOf);
    }

    private static void applyHorizon(Map<String, Double> values, String horizon) {
        if ("short".equals(horizon)) {
            values.compute("cash", (k, v) -> v + 5); values.compute("nasdaq", (k, v) -> Math.max(0, v - 3));
            values.compute("gold", (k, v) -> Math.max(0, v - 2));
        } else if ("long".equals(horizon)) {
            values.compute("cash", (k, v) -> Math.max(0, v - 5)); values.compute("nasdaq", (k, v) -> v + 3);
            values.compute("gold", (k, v) -> v + 2);
        }
    }

    private static void defend(Map<String, Double> values, double desired, double cashShare) {
        var keys = List.of("nasdaq", "leverage", "korea", "emerging", "copper");
        var available = keys.stream().mapToDouble(key -> values.getOrDefault(key, 0d)).sum();
        var amount = Math.min(desired, available);
        if (available <= 0 || amount <= 0) return;
        for (var key : keys) values.compute(key, (ignored, current) -> Math.max(0, current - current / available * amount));
        values.compute("cash", (ignored, current) -> current + amount * cashShare);
        values.compute("gold", (ignored, current) -> current + amount * (1 - cashShare));
    }

    private static Integer buyStage(Map<String, Double> raw, Map<String, Double> derived) {
        var above = derived.get("NASDAQ_ABOVE_200DMA");
        if (above == null) return null;
        if (above == 1) return 0;
        var disparity = derived.get("NASDAQ_DISPARITY");
        var vix = raw.get("VIXCLS");
        if (disparity != null && disparity <= -25 && vix != null && vix >= 35) return 3;
        if (disparity != null && disparity <= -20) return 2;
        return 1;
    }

    private static LinkedHashMap<String, Integer> normalize(Map<String, Double> values) {
        var sum = values.values().stream().mapToDouble(Double::doubleValue).sum();
        var result = new LinkedHashMap<String, Integer>();
        values.forEach((key, value) -> result.put(key, (int) Math.round(value / sum * 100)));
        var remainder = 100 - result.values().stream().mapToInt(Integer::intValue).sum();
        if (remainder != 0) {
            var largest = result.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            result.compute(largest, (ignored, current) -> current + remainder);
        }
        return result;
    }

    private static void enforceMinimumCash(Map<String, Double> values, double minimumPct) {
        var total = values.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0 || minimumPct <= 0) return;
        var requiredCash = total * minimumPct / 100;
        var currentCash = values.getOrDefault("cash", 0d);
        var shortage = requiredCash - currentCash;
        if (shortage <= 0) return;

        var donors = values.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("cash") && entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .toList();
        var available = donors.stream().mapToDouble(key -> values.getOrDefault(key, 0d)).sum();
        var transferable = Math.min(shortage, available);
        if (transferable <= 0) return;
        for (var key : donors) {
            var current = values.getOrDefault(key, 0d);
            values.put(key, Math.max(0, current - transferable * current / available));
        }
        values.put("cash", currentCash + transferable);
    }

    private static void move(Map<String, Double> values, String from, String to, double amount) {
        var actual = Math.max(0, Math.min(values.getOrDefault(from, 0d), amount));
        values.compute(from, (ignored, current) -> current - actual);
        values.compute(to, (ignored, current) -> current + actual);
    }

    private static Map<String, Double> base(double cash, double nasdaq, double leverage, double gold,
                                             double silver, double copper, double korea, double emerging) {
        var result = new LinkedHashMap<String, Double>();
        result.put("cash", cash); result.put("nasdaq", nasdaq); result.put("leverage", leverage);
        result.put("gold", gold); result.put("silver", silver); result.put("copper", copper);
        result.put("korea", korea); result.put("emerging", emerging);
        return Map.copyOf(result);
    }

    private static boolean one(Map<String, Double> values, String key) { return equal(values.get(key), 1); }
    private static boolean equal(Double value, double expected) { return value != null && Double.compare(value, expected) == 0; }
}
