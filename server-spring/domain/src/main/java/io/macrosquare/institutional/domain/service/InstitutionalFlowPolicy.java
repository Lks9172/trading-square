package io.macrosquare.institutional.domain.service;

import io.macrosquare.institutional.domain.model.InstitutionalConsensus;
import io.macrosquare.institutional.domain.model.InstitutionalDivergence;
import io.macrosquare.institutional.domain.model.InstitutionalFiling;
import io.macrosquare.institutional.domain.model.InstitutionalFlowAction;
import io.macrosquare.institutional.domain.model.InstitutionalFlowSnapshot;
import io.macrosquare.institutional.domain.model.InstitutionalHolding;
import io.macrosquare.institutional.domain.model.InstitutionalManagerFlow;
import io.macrosquare.institutional.domain.model.InstitutionalPositionFlow;
import io.macrosquare.institutional.domain.model.InstitutionalSecurityIdentity;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Normalizes manager-level 13F filings into quarter-over-quarter flows and shared conviction. */
public final class InstitutionalFlowPolicy {

    public InstitutionalFlowSnapshot evaluate(List<InstitutionalFiling> filings) {
        return evaluate(filings, Map.of(), Map.of());
    }

    /**
     * Enriches delayed 13F positions with point-in-time identities and current analyst opinion.
     * All inputs are transport-neutral snapshots; the policy performs no I/O.
     */
    public InstitutionalFlowSnapshot evaluate(
            List<InstitutionalFiling> filings,
            Map<String, InstitutionalSecurityIdentity> identitiesByCusip,
            Map<String, Double> analystScoresByTicker
    ) {
        var identities = identitiesByCusip == null ? Map.<String, InstitutionalSecurityIdentity>of()
                : identitiesByCusip;
        var analystScores = analystScoresByTicker == null ? Map.<String, Double>of() : analystScoresByTicker;
        var grouped = filings.stream().collect(Collectors.groupingBy(
                filing -> filing.manager().cik(), LinkedHashMap::new, Collectors.toList()));
        var managerFlows = new ArrayList<InstitutionalManagerFlow>();
        var latestByManager = new LinkedHashMap<String, Map<String, InstitutionalHolding>>();
        var previousByManager = new LinkedHashMap<String, Map<String, InstitutionalHolding>>();
        var divergence = new LinkedHashMap<String, DivergenceAccumulator>();
        var latestPositionKeys = new LinkedHashSet<String>();
        for (var entries : grouped.values()) {
            var ordered = entries.stream()
                    .sorted(Comparator.comparing(InstitutionalFiling::reportPeriod)
                            .thenComparing(InstitutionalFiling::filedOn).reversed())
                    .toList();
            if (ordered.isEmpty()) continue;
            var latest = ordered.getFirst();
            var previous = ordered.size() > 1 ? ordered.get(1) : null;
            var latestHoldings = aggregate(latest.holdings());
            var previousHoldings = previous == null ? Map.<String, InstitutionalHolding>of()
                    : aggregate(previous.holdings());
            latestByManager.put(latest.manager().cik(), latestHoldings);
            previousByManager.put(latest.manager().cik(), previousHoldings);
            latestPositionKeys.addAll(latestHoldings.keySet());
            var calculation = managerFlow(latest, previous, latestHoldings, previousHoldings, identities);
            managerFlows.add(calculation.flow());
            for (var item : calculation.allFlows()) {
                if (item.identity() == null || !item.putCall().isBlank()) continue;
                divergence.computeIfAbsent(item.identity().ticker(), ignored ->
                                new DivergenceAccumulator(item.identity()))
                        .add(latest.manager().cik(), item);
            }
        }
        managerFlows.sort(Comparator.comparingDouble(InstitutionalManagerFlow::totalValueUsd).reversed());
        var consensus = consensus(managerFlows, latestByManager, previousByManager, identities);
        var asOf = managerFlows.stream().map(InstitutionalManagerFlow::filedOn).max(LocalDate::compareTo)
                .orElse(null);
        var mappedKeys = latestPositionKeys.stream()
                .filter(key -> identities.containsKey(cusipFromPositionKey(key)))
                .count();
        var divergences = divergence.values().stream()
                .filter(value -> analystScores.containsKey(value.identity.ticker()))
                .map(value -> value.snapshot(analystScores.get(value.identity.ticker())))
                .sorted(Comparator.comparingDouble(
                                (InstitutionalDivergence value) -> Math.abs(value.divergenceScore())).reversed()
                        .thenComparing(Comparator.comparingInt(InstitutionalDivergence::managerCount).reversed())
                        .thenComparing(InstitutionalDivergence::ticker))
                .limit(30)
                .toList();
        return new InstitutionalFlowSnapshot(
                asOf,
                "SEC Form 13F-HR + SEC issuer directory",
                managerFlows.size(),
                consensus.size(),
                Math.toIntExact(mappedKeys),
                Math.toIntExact(latestPositionKeys.size() - mappedKeys),
                managerFlows,
                consensus,
                divergences
        );
    }

    private static ManagerCalculation managerFlow(
            InstitutionalFiling latest,
            InstitutionalFiling previous,
            Map<String, InstitutionalHolding> current,
            Map<String, InstitutionalHolding> prior,
            Map<String, InstitutionalSecurityIdentity> identities
    ) {
        var keys = new LinkedHashSet<String>();
        keys.addAll(current.keySet());
        keys.addAll(prior.keySet());
        var flows = keys.stream().map(key -> flow(current.get(key), prior.get(key), identities)).toList();
        var buys = flows.stream()
                .filter(item -> item.action() == InstitutionalFlowAction.NEW
                        || item.action() == InstitutionalFlowAction.INCREASE)
                .sorted(Comparator.comparingDouble(InstitutionalPositionFlow::estimatedNetFlowUsd).reversed())
                .limit(10).toList();
        var sells = flows.stream()
                .filter(item -> item.action() == InstitutionalFlowAction.REDUCE
                        || item.action() == InstitutionalFlowAction.EXIT)
                .sorted(Comparator.comparingDouble(InstitutionalPositionFlow::estimatedNetFlowUsd))
                .limit(10).toList();
        var total = current.values().stream().mapToDouble(InstitutionalHolding::valueUsd).sum();
        var previousTotal = prior.values().stream().mapToDouble(InstitutionalHolding::valueUsd).sum();
        return new ManagerCalculation(new InstitutionalManagerFlow(
                latest.manager(), latest.reportPeriod(), previous == null ? null : previous.reportPeriod(),
                latest.filedOn(), latest.sourceUrl(), current.size(), total, previousTotal,
                total - previousTotal,
                flows.stream().mapToDouble(InstitutionalPositionFlow::estimatedNetFlowUsd).sum(),
                count(flows, InstitutionalFlowAction.NEW), count(flows, InstitutionalFlowAction.INCREASE),
                count(flows, InstitutionalFlowAction.REDUCE), count(flows, InstitutionalFlowAction.EXIT),
                buys, sells
        ), flows);
    }

    private static InstitutionalPositionFlow flow(
            InstitutionalHolding current,
            InstitutionalHolding previous,
            Map<String, InstitutionalSecurityIdentity> identities
    ) {
        var representative = current == null ? previous : current;
        var currentValue = current == null ? 0 : current.valueUsd();
        var previousValue = previous == null ? 0 : previous.valueUsd();
        var delta = currentValue - previousValue;
        var currentShares = current == null ? 0 : current.shares();
        var previousShares = previous == null ? 0 : previous.shares();
        var shareDelta = currentShares - previousShares;
        var materialShares = Math.max(1, previousShares * 0.01);
        var action = previous == null && current != null
                ? InstitutionalFlowAction.NEW
                : current == null
                ? InstitutionalFlowAction.EXIT
                : shareDelta > materialShares
                ? InstitutionalFlowAction.INCREASE
                : shareDelta < -materialShares
                ? InstitutionalFlowAction.REDUCE
                : InstitutionalFlowAction.UNCHANGED;
        var estimatedFlow = estimatedFlow(currentValue, currentShares, previousValue, previousShares, shareDelta);
        return new InstitutionalPositionFlow(
                representative.cusip(), representative.issuer(), representative.titleClass(),
                representative.putCall(), currentValue, previousValue, delta,
                previousValue <= 0 ? null : (delta / previousValue) * 100,
                currentShares, previousShares, shareDelta,
                previousShares <= 0 ? null : (shareDelta / previousShares) * 100,
                estimatedFlow, action, identities.get(normalizeCusip(representative.cusip()))
        );
    }

    private static double estimatedFlow(
            double currentValue,
            double currentShares,
            double previousValue,
            double previousShares,
            double shareDelta
    ) {
        if (currentShares > 0 && currentValue > 0) return (currentValue / currentShares) * shareDelta;
        if (currentShares == 0 && previousShares > 0) return -previousValue;
        return currentValue - previousValue;
    }

    private static List<InstitutionalConsensus> consensus(
            List<InstitutionalManagerFlow> managers,
            Map<String, Map<String, InstitutionalHolding>> latest,
            Map<String, Map<String, InstitutionalHolding>> previous,
            Map<String, InstitutionalSecurityIdentity> identities
    ) {
        var accumulators = new LinkedHashMap<String, ConsensusAccumulator>();
        for (var manager : managers) {
            var current = latest.getOrDefault(manager.manager().cik(), Map.of());
            var prior = previous.getOrDefault(manager.manager().cik(), Map.of());
            for (var entry : current.entrySet()) {
                var holding = entry.getValue();
                var priorHolding = prior.get(entry.getKey());
                var priorValue = priorHolding == null ? 0 : priorHolding.valueUsd();
                var priorShares = priorHolding == null ? 0 : priorHolding.shares();
                var estimatedFlow = estimatedFlow(
                        holding.valueUsd(), holding.shares(), priorValue, priorShares,
                        holding.shares() - priorShares);
                accumulators.computeIfAbsent(entry.getKey(), ignored -> new ConsensusAccumulator(
                                holding, identities.get(normalizeCusip(holding.cusip()))))
                        .add(manager.manager().name(), holding.valueUsd(), holding.valueUsd() - priorValue, estimatedFlow);
            }
        }
        return accumulators.values().stream()
                .filter(value -> value.managers.size() >= 2)
                .map(ConsensusAccumulator::snapshot)
                .sorted(Comparator.comparingInt(InstitutionalConsensus::managerCount).reversed()
                        .thenComparing(Comparator.comparingDouble(InstitutionalConsensus::netValueDeltaUsd).reversed()))
                .limit(30)
                .toList();
    }

    private static Map<String, InstitutionalHolding> aggregate(List<InstitutionalHolding> holdings) {
        var result = new LinkedHashMap<String, InstitutionalHolding>();
        for (var holding : holdings) {
            result.merge(holding.positionKey(), holding, (left, right) -> new InstitutionalHolding(
                    left.cusip(), left.issuer(), left.titleClass(), left.putCall(),
                    left.valueUsd() + right.valueUsd(), left.shares() + right.shares()));
        }
        return Map.copyOf(result);
    }

    private static int count(List<InstitutionalPositionFlow> values, InstitutionalFlowAction action) {
        return (int) values.stream().filter(value -> value.action() == action).count();
    }

    private static String cusipFromPositionKey(String key) {
        var separator = key.indexOf('|');
        return normalizeCusip(separator < 0 ? key : key.substring(0, separator));
    }

    private static String normalizeCusip(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private record ManagerCalculation(InstitutionalManagerFlow flow, List<InstitutionalPositionFlow> allFlows) {
    }

    private static final class ConsensusAccumulator {
        private final InstitutionalHolding holding;
        private final InstitutionalSecurityIdentity identity;
        private final List<String> managers = new ArrayList<>();
        private double totalValue;
        private double netDelta;
        private double estimatedNetFlow;

        private ConsensusAccumulator(InstitutionalHolding holding, InstitutionalSecurityIdentity identity) {
            this.holding = holding;
            this.identity = identity;
        }

        private void add(String manager, double value, double delta, double estimatedFlow) {
            managers.add(manager);
            totalValue += value;
            netDelta += delta;
            estimatedNetFlow += estimatedFlow;
        }

        private InstitutionalConsensus snapshot() {
            return new InstitutionalConsensus(
                    holding.cusip(), holding.issuer(), holding.titleClass(), managers.size(),
                    managers.stream().sorted().toList(), totalValue, netDelta, estimatedNetFlow, identity);
        }
    }

    private static final class DivergenceAccumulator {
        private final InstitutionalSecurityIdentity identity;
        private final Set<String> managers = new LinkedHashSet<>();
        private double signedMagnitude;
        private double currentShares;
        private double previousShares;
        private int positive;
        private int negative;
        private int observations;

        private DivergenceAccumulator(InstitutionalSecurityIdentity identity) {
            this.identity = identity;
        }

        private void add(String manager, InstitutionalPositionFlow value) {
            managers.add(manager);
            currentShares += value.currentShares();
            previousShares += value.previousShares();
            var direction = switch (value.action()) {
                case NEW, INCREASE -> 1;
                case REDUCE, EXIT -> -1;
                case UNCHANGED -> 0;
            };
            if (direction > 0) positive++;
            if (direction < 0) negative++;
            var denominator = value.previousShares() > 0 ? value.previousShares()
                    : Math.max(1, value.currentShares());
            signedMagnitude += direction * Math.min(1, Math.abs(value.shareDelta()) / denominator);
            observations++;
        }

        private InstitutionalDivergence snapshot(double analystScore) {
            var breadth = managers.isEmpty() ? 0 : (positive - negative) / (double) managers.size();
            var magnitude = observations == 0 ? 0 : signedMagnitude / observations;
            var institutional = clamp(2 * (breadth * .65 + magnitude * .35), -2, 2);
            var divergence = clamp(analystScore - institutional, -4, 4);
            var aggregatePct = previousShares > 0
                    ? (currentShares - previousShares) * 100 / previousShares
                    : currentShares > 0 ? 100 : 0;
            var signal = divergence >= 1 ? "ANALYSTS_AHEAD_OF_MONEY"
                    : divergence <= -1 ? "MONEY_AHEAD_OF_ANALYSTS" : "ALIGNED";
            return new InstitutionalDivergence(
                    identity.ticker(), identity.issuer(), identity.sectorKey(), analystScore,
                    institutional, divergence, managers.size(), aggregatePct, signal);
        }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
