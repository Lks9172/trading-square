package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.CurrentSectorRotationAssessment;
import io.macrosquare.research.application.model.CurrentSectorRotationAssessment.CurrentSectorProfile;
import io.macrosquare.research.application.model.CurrentSectorRotationAssessment.CurrentRevisionBreadth;
import io.macrosquare.research.application.model.CurrentSectorRotationAssessment.CurrentFundFlow;
import io.macrosquare.research.application.model.CurrentSectorRotationAssessment.CurrentPriceBreadth;
import io.macrosquare.research.application.model.CurrentSectorMarketEvidence;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSector;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorScore;
import io.macrosquare.research.application.port.in.CurrentSectorRotationCommand;
import io.macrosquare.research.application.port.in.CurrentSectorRotationUnavailableException;
import io.macrosquare.research.application.port.in.EvaluateCurrentSectorRotationUseCase;
import io.macrosquare.research.application.port.out.LoadResearchCatalogPort;
import io.macrosquare.research.application.port.out.LoadSectorEarningsRevisionBreadthPort;
import io.macrosquare.research.application.port.out.SectorMarketEvidenceRepository;
import io.macrosquare.research.domain.rotation.MacroRegime;
import io.macrosquare.research.domain.rotation.RotationMarketEvidence;
import io.macrosquare.research.domain.rotation.SectorClassification;
import io.macrosquare.research.domain.rotation.SectorRotationEvidence;
import io.macrosquare.research.domain.rotation.SectorRotationInput;
import io.macrosquare.research.domain.rotation.SectorRotationPolicy;
import io.macrosquare.research.domain.rotation.SectorEarningsRevisionBreadthPolicy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Combines current macro/momentum inputs with slower structural catalog evidence. */
public final class EvaluateCurrentSectorRotationService implements EvaluateCurrentSectorRotationUseCase {

    private static final int MIN_CURRENT_MOMENTUM_COVERAGE_PCT = 70;
    private static final int MIN_CURRENT_MACRO_INPUTS = 5;
    private static final int MAX_REVISION_AGE_DAYS = 3;
    private static final int MAX_SECTOR_MARKET_EVIDENCE_AGE_DAYS = 7;
    private static final Set<String> STANDARD_SECTOR_KEYS = Set.of(
            "SECTOR_XLK", "SECTOR_XLF", "SECTOR_XLE", "SECTOR_XLV", "SECTOR_XLI",
            "SECTOR_XLY", "SECTOR_XLC", "SECTOR_XLB", "SECTOR_XLRE", "SECTOR_XLU",
            "SECTOR_XLP"
    );

    private final LoadResearchCatalogPort catalog;
    private final SectorRotationPolicy policy;
    private final LoadSectorEarningsRevisionBreadthPort revisionBreadth;
    private final SectorEarningsRevisionBreadthPolicy revisionBreadthPolicy;
    private final SectorMarketEvidenceRepository sectorMarketEvidence;

    public EvaluateCurrentSectorRotationService(
            LoadResearchCatalogPort catalog,
            SectorRotationPolicy policy
    ) {
        this(catalog, policy, LoadSectorEarningsRevisionBreadthPort.unavailable(),
                new SectorEarningsRevisionBreadthPolicy(), SectorMarketEvidenceRepository.unavailable());
    }

    public EvaluateCurrentSectorRotationService(
            LoadResearchCatalogPort catalog,
            SectorRotationPolicy policy,
            LoadSectorEarningsRevisionBreadthPort revisionBreadth,
            SectorEarningsRevisionBreadthPolicy revisionBreadthPolicy
    ) {
        this(catalog, policy, revisionBreadth, revisionBreadthPolicy,
                SectorMarketEvidenceRepository.unavailable());
    }

    public EvaluateCurrentSectorRotationService(
            LoadResearchCatalogPort catalog,
            SectorRotationPolicy policy,
            LoadSectorEarningsRevisionBreadthPort revisionBreadth,
            SectorEarningsRevisionBreadthPolicy revisionBreadthPolicy,
            SectorMarketEvidenceRepository sectorMarketEvidence
    ) {
        this.catalog = Objects.requireNonNull(catalog);
        this.policy = Objects.requireNonNull(policy);
        this.revisionBreadth = Objects.requireNonNull(revisionBreadth);
        this.revisionBreadthPolicy = Objects.requireNonNull(revisionBreadthPolicy);
        this.sectorMarketEvidence = Objects.requireNonNull(sectorMarketEvidence);
    }

    @Override
    public CurrentSectorRotationAssessment evaluate(CurrentSectorRotationCommand command) {
        var references = referenceProfiles();
        if (references.isEmpty()) {
            throw new CurrentSectorRotationUnavailableException("sector rotation universe is empty");
        }

        // A universe-level coverage threshold is not enough on its own. Feeding
        // an uncovered sector into the policy would turn the missing momentum
        // into the neutral default and could still rank a stale structural seed
        // as a current leader. Only sectors with both current horizons are
        // eligible for the current ranking; uncovered sectors remain visible as
        // unavailable reference rows in the read model.
        var eligibleReferences = references.values().stream()
                .filter(reference -> hasCurrentMomentum(reference, command.derivedValues()))
                .toList();
        var standardReferences = references.values().stream()
                .filter(reference -> STANDARD_SECTOR_KEYS.contains(reference.key()))
                .toList();
        if (standardReferences.isEmpty()) {
            throw new CurrentSectorRotationUnavailableException("standard sector rotation universe is empty");
        }
        var eligibleStandardReferences = eligibleReferences.stream()
                .filter(reference -> STANDARD_SECTOR_KEYS.contains(reference.key()))
                .toList();
        var standardCoveragePct = eligibleStandardReferences.size() * 100 / standardReferences.size();
        if (standardCoveragePct < MIN_CURRENT_MOMENTUM_COVERAGE_PCT) {
            throw new CurrentSectorRotationUnavailableException(
                    "current standard-sector momentum coverage is insufficient: "
                            + eligibleStandardReferences.size() + "/" + standardReferences.size());
        }
        var macroCoverage = currentMacroCoverage(command);
        if (macroCoverage < MIN_CURRENT_MACRO_INPUTS) {
            throw new CurrentSectorRotationUnavailableException(
                    "current sector macro coverage is insufficient: " + macroCoverage + "/7");
        }
        var market = marketEvidence(command);
        var currentAsOfDate = asOfDate(command.calculatedAt());
        var revisionEvidence = loadRevisionEvidence(references, currentAsOfDate);
        var marketEvidenceBySector = loadSectorMarketEvidence(references, currentAsOfDate);
        // Standard sectors are mutually exclusive GICS sleeves and own the public
        // sector-rotation summary. Strategic ETFs overlap those sleeves (for
        // example SOXX/SMH with XLK), so evaluating all sixteen together used to
        // dilute standard-sector percentiles and leak theme labels into the
        // "표준 11개 섹터" page. Evaluate each universe independently and merge only
        // their item projections.
        var view = policy.evaluate(new SectorRotationInput(
                market,
                eligibleStandardReferences.stream()
                        .map(reference -> evidence(
                                reference, command.derivedValues(), revisionEvidence.get(reference.key()),
                                marketEvidenceBySector.get(reference.key())))
                        .toList(),
                Map.of()
        ));
        var items = new LinkedHashMap<String, io.macrosquare.research.domain.rotation.SectorRotationItem>();
        view.sectors().forEach(item -> items.put(item.key(), item));
        var eligibleStrategicReferences = eligibleReferences.stream()
                .filter(reference -> !STANDARD_SECTOR_KEYS.contains(reference.key()))
                .toList();
        if (!eligibleStrategicReferences.isEmpty()) {
            policy.evaluate(new SectorRotationInput(
                    market,
                    eligibleStrategicReferences.stream()
                            .map(reference -> evidence(
                                    reference, command.derivedValues(), revisionEvidence.get(reference.key()),
                                    marketEvidenceBySector.get(reference.key())))
                            .toList(),
                    Map.of()
            )).sectors().forEach(item -> items.put(item.key(), item));
        }
        var profiles = new LinkedHashMap<String, CurrentSectorProfile>();
        references.forEach((key, reference) -> {
            var item = items.get(key);
            if (item == null) return;
            profiles.put(key, new CurrentSectorProfile(
                    key,
                    reference.label(),
                    reference.classification(),
                    number(command.derivedValues(), shortRelativeStrengthKey(key)),
                    number(command.derivedValues(), "SECTOR_RS_" + suffix(key)),
                    reference.qualityScore(),
                    reference.policySupport(),
                    reference.structuralDemand(),
                    reference.supplyTightness(),
                    reference.marketConcentration(),
                    reference.appealScore(),
                    reference.crowdingScore(),
                    reference.buyScore(),
                    reference.buyLabel(),
                    reference.valuationScore(),
                    reference.earningsRevisionScore(),
                    revisionEvidence.get(key),
                    currentFundFlow(marketEvidenceBySector.get(key)),
                    currentPriceBreadth(marketEvidenceBySector.get(key)),
                    item
            ));
        });
        return new CurrentSectorRotationAssessment(
                command.calculatedAt(), view, profiles, eligibleStandardReferences.size(),
                (int) standardReferences.stream()
                        .map(ReferenceProfile::key)
                        .filter(key -> Double.compare(command.derivedValues().getOrDefault(
                                "SECTOR_TR_READY_" + suffix(key), 0d), 1d) == 0)
                        .count(),
                standardReferences.size(), command.rawObservedOn(), command.derivedObservedOn());
    }

    private static boolean hasCurrentMomentum(
            ReferenceProfile reference,
            Map<String, Double> derived
    ) {
        return number(derived, shortRelativeStrengthKey(reference.key())) != null
                && number(derived, "SECTOR_RS_" + suffix(reference.key())) != null
                && number(derived, "SECTOR_MOMENTUM_SCORE_" + suffix(reference.key())) != null
                && number(derived, "SECTOR_ABSOLUTE_TREND_" + suffix(reference.key())) != null;
    }

    private static int currentMacroCoverage(CurrentSectorRotationCommand command) {
        var raw = command.rawValues();
        var derived = command.derivedValues();
        return (int) java.util.stream.Stream.of(
                number(derived, "LIQUIDITY_DIRECTION"),
                number(derived, "REAL_YIELD"),
                number(raw, "T10Y2Y"),
                number(raw, "WTI"),
                number(raw, "DXY"),
                number(raw, "STLFSI4"),
                number(raw, "BAMLH0A0HYM2")
        ).filter(Objects::nonNull).count();
    }

    private Map<String, ReferenceProfile> referenceProfiles() {
        var result = new LinkedHashMap<String, ReferenceProfile>();
        var sectors = catalog.loadSectors();
        sectors.sectors().forEach(sector -> {
            var score = sector.sectorSummary() == null ? null : sector.sectorSummary().topSector();
            merge(result, score, sector.rotation(), sector.tickers());
        });
        for (var theme : catalog.loadThemes().themes()) {
            try {
                var detail = catalog.loadTheme(theme.id(), "buy", "priority");
                detail.sectorScores().forEach(score -> {
                    // A thematic projection may reference a standard GICS key,
                    // but it must not replace that key's canonical sector row.
                    // Strategic-theme-only keys can still merge by completeness.
                    if (STANDARD_SECTOR_KEYS.contains(score.key()) && result.containsKey(score.key())) return;
                    merge(result, score, null, List.of());
                });
            } catch (RuntimeException ignored) {
                // A missing optional theme projection must not remove standard sectors.
            }
        }
        return Map.copyOf(result);
    }

    private static void merge(
            Map<String, ReferenceProfile> result,
            SectorScore score,
            RotationSector rotation,
            List<String> tickers
    ) {
        if (score == null) return;
        var candidate = new ReferenceProfile(
                score.key(), score.label(), score.classification(), score.qualityScore(),
                score.policySupport(), score.structuralDemand(), score.supplyTightness(),
                score.marketConcentration(), score.appealScore(), score.crowdingScore(),
                score.buyScore(), score.buyLabel(),
                rotation == null ? null : rotation.valuationScore(),
                rotation == null ? null : rotation.earningsRevisionScore(),
                tickers == null ? List.of() : List.copyOf(tickers)
        );
        result.merge(score.key(), candidate, EvaluateCurrentSectorRotationService::moreComplete);
    }

    private static ReferenceProfile moreComplete(ReferenceProfile left, ReferenceProfile right) {
        return completeness(right) > completeness(left) ? right : left;
    }

    private static int completeness(ReferenceProfile value) {
        return (int) java.util.stream.Stream.of(
                value.qualityScore(), value.policySupport(), value.structuralDemand(),
                value.supplyTightness(), value.marketConcentration(), value.appealScore(),
                value.crowdingScore(), value.buyScore(), value.valuationScore(),
                value.earningsRevisionScore()).filter(Objects::nonNull).count();
    }

    private static SectorRotationEvidence evidence(
            ReferenceProfile reference,
            Map<String, Double> derived,
            CurrentRevisionBreadth revision,
            CurrentSectorMarketEvidence currentMarketEvidence
    ) {
        var fundFlow = currentMarketEvidence == null ? null : currentMarketEvidence.fundFlow();
        var priceBreadth = currentMarketEvidence == null ? null : currentMarketEvidence.priceBreadth();
        return new SectorRotationEvidence(
                reference.key(),
                reference.label(),
                SectorClassification.valueOf(reference.classification().toUpperCase(Locale.ROOT)),
                number(derived, "SECTOR_RS_" + suffix(reference.key())),
                number(derived, shortRelativeStrengthKey(reference.key())),
                integer(derived, "SECTOR_MOMENTUM_SCORE_" + suffix(reference.key())),
                enabledNullable(derived, "SECTOR_ABSOLUTE_TREND_" + suffix(reference.key())),
                reference.qualityScore(),
                reference.appealScore(),
                reference.valuationScore(),
                revision == null ? null : revision.score(),
                reference.crowdingScore(),
                reference.buyScore(),
                revision == null ? null : revision.latestObservedOn(),
                revision == null ? null : revision.coveragePct(),
                fundFlow == null ? null : fundFlow.score(),
                fundFlow == null ? null : fundFlow.observedOn(),
                priceBreadth == null ? null : priceBreadth.score(),
                priceBreadth == null ? null : priceBreadth.latestObservedOn(),
                priceBreadth == null ? null : priceBreadth.coveragePct()
        );
    }

    private Map<String, CurrentSectorMarketEvidence> loadSectorMarketEvidence(
            Map<String, ReferenceProfile> references,
            LocalDate asOfDate
    ) {
        var result = new LinkedHashMap<String, CurrentSectorMarketEvidence>();
        references.values().stream()
                .filter(reference -> STANDARD_SECTOR_KEYS.contains(reference.key()))
                .forEach(reference -> {
                    var evidence = sectorMarketEvidence.loadCurrent(
                            reference.key(), asOfDate, MAX_SECTOR_MARKET_EVIDENCE_AGE_DAYS);
                    if (evidence != null && !evidence.empty()) result.put(reference.key(), evidence);
                });
        return Map.copyOf(result);
    }

    private static CurrentFundFlow currentFundFlow(CurrentSectorMarketEvidence value) {
        if (value == null || value.fundFlow() == null) return null;
        var flow = value.fundFlow();
        return new CurrentFundFlow(
                flow.observedOn(), flow.nav(), flow.totalNetAssets(), flow.flow1dUsd(),
                flow.flow5dUsd(), flow.flow20dUsd(), flow.flow5dPct(), flow.flow20dPct(), flow.score());
    }

    private static CurrentPriceBreadth currentPriceBreadth(CurrentSectorMarketEvidence value) {
        if (value == null || value.priceBreadth() == null) return null;
        var breadth = value.priceBreadth();
        return new CurrentPriceBreadth(
                breadth.asOfDate(), breadth.oldestObservedOn(), breadth.latestObservedOn(),
                breadth.constituentCount(), breadth.coveredCount(), breadth.coveragePct(),
                breadth.aboveMa20Pct(), breadth.aboveMa50Pct(), breadth.aboveMa200Pct(), breadth.score());
    }

    private Map<String, CurrentRevisionBreadth> loadRevisionEvidence(
            Map<String, ReferenceProfile> references,
            LocalDate asOfDate
    ) {
        var result = new LinkedHashMap<String, CurrentRevisionBreadth>();
        references.values().stream()
                .filter(reference -> STANDARD_SECTOR_KEYS.contains(reference.key()))
                .filter(reference -> !reference.tickers().isEmpty())
                .forEach(reference -> revisionBreadth.load(
                                reference.key(), reference.tickers(), asOfDate, MAX_REVISION_AGE_DAYS)
                        .ifPresent(evidence -> revisionBreadthPolicy.score(evidence).ifPresent(score ->
                                result.put(reference.key(), new CurrentRevisionBreadth(
                                        evidence.asOfDate(), evidence.oldestObservedOn(),
                                        evidence.latestObservedOn(), evidence.constituentCount(),
                                        evidence.coveredCount(), evidence.coveragePct(),
                                        evidence.revisedUpPct(), evidence.revisedDownPct(), score)))));
        return Map.copyOf(result);
    }

    private static LocalDate asOfDate(String calculatedAt) {
        try {
            return LocalDate.ofInstant(Instant.parse(calculatedAt), ZoneOffset.UTC);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("calculatedAt must be an ISO-8601 instant", error);
        }
    }

    private static RotationMarketEvidence marketEvidence(CurrentSectorRotationCommand command) {
        var raw = command.rawValues();
        var derived = command.derivedValues();
        return new RotationMarketEvidence(
                number(derived, "LIQUIDITY_DIRECTION"),
                number(derived, "REAL_YIELD"),
                number(raw, "T10Y2Y"),
                number(raw, "WTI"),
                number(raw, "DXY"),
                number(raw, "STLFSI4"),
                number(raw, "BAMLH0A0HYM2"),
                number(derived, "CREDIT_HY_OAS_BP"),
                enabledNullable(derived, "OVERHEATED"),
                enabledNullable(derived, "COPPER_GOLD_RATIO_UPTURN"),
                MacroRegime.valueOf(command.macroRegime()),
                number(derived, "INSTITUTIONAL_SECTOR_TECH_FLOW"),
                number(derived, "INSTITUTIONAL_SECTOR_FIN_FLOW"),
                number(derived, "INSTITUTIONAL_SECTOR_ENERGY_FLOW")
        );
    }

    private static Double number(Map<String, Double> values, String key) {
        var value = values.get(key);
        return value != null && Double.isFinite(value) ? value : null;
    }

    private static Boolean enabledNullable(Map<String, Double> values, String key) {
        var value = number(values, key);
        return value == null ? null : Double.compare(value, 1d) == 0;
    }

    private static Integer integer(Map<String, Double> values, String key) {
        var value = number(values, key);
        return value == null ? null : (int) Math.round(Math.max(0, Math.min(100, value)));
    }

    private static String suffix(String key) {
        return key.startsWith("SECTOR_") ? key.substring("SECTOR_".length()) : key;
    }

    private static String shortRelativeStrengthKey(String sectorKey) {
        return "SECTOR_REL_1M_" + suffix(sectorKey);
    }

    private record ReferenceProfile(
            String key,
            String label,
            String classification,
            Integer qualityScore,
            Integer policySupport,
            Integer structuralDemand,
            Integer supplyTightness,
            Integer marketConcentration,
            Integer appealScore,
            Integer crowdingScore,
            Integer buyScore,
            String buyLabel,
            Integer valuationScore,
            Integer earningsRevisionScore,
            List<String> tickers
    ) {
        private ReferenceProfile {
            tickers = List.copyOf(tickers == null ? List.of() : tickers);
        }
    }
}
