package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.port.in.EnrichCompanyResearchUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyFilingDetailParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyRevenueMixParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanySubmissionsParityUseCase;
import io.macrosquare.company.application.port.out.LoadCompanySectorAssessmentPort;
import io.macrosquare.shared.application.port.out.OperationalEventSink;
import io.macrosquare.company.domain.horizon.CompanyHorizonSignalPolicy;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecisionPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Bounded stale-while-revalidate cache for direct Spring company evidence.
 * Each evidence source fails independently, preserving the complete captured
 * projection while avoiding network latency on the UI request thread.
 */
public final class EnrichCompanyResearchService implements EnrichCompanyResearchUseCase {

    private static final int MAX_ENTRIES = 128;

    private final EvaluateCompanyResearchParityUseCase coreResearch;
    private final EvaluateCompanyPriceSignalParityUseCase priceSignals;
    private final EvaluateCompanySubmissionsParityUseCase submissions;
    private final EvaluateCompanyFilingDetailParityUseCase filingDetails;
    private final EvaluateCompanyRevenueMixParityUseCase revenueMix;
    private final CompanyRevenueMixComposer revenueMixComposer;
    private final CompanyHorizonSignalPolicy horizonPolicy;
    private final LoadCompanySectorAssessmentPort sectorAssessment;
    private final CompanyInvestmentDecisionComposer investmentDecisionComposer;
    private final Executor executor;
    private final boolean asynchronous;
    private final Clock clock;
    private final Duration cacheTtl;
    private final OperationalEventSink operationalEvents;
    private final ConcurrentHashMap<String, CachedResearch> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public EnrichCompanyResearchService(
            EvaluateCompanyResearchParityUseCase coreResearch,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            Clock clock,
            Duration cacheTtl
    ) {
        this(coreResearch, priceSignals, null, null, null, new CompanyRevenueMixComposer(),
                new CompanyHorizonSignalPolicy(),
                null, new CompanyInvestmentDecisionPolicy(),
                Runnable::run, false, clock, cacheTtl, OperationalEventSink.noop());
    }

    public EnrichCompanyResearchService(
            EvaluateCompanyResearchParityUseCase coreResearch,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            EvaluateCompanySubmissionsParityUseCase submissions,
            EvaluateCompanyFilingDetailParityUseCase filingDetails,
            EvaluateCompanyRevenueMixParityUseCase revenueMix,
            CompanyRevenueMixComposer revenueMixComposer,
            Executor executor,
            Clock clock,
            Duration cacheTtl
    ) {
        this(coreResearch, priceSignals, submissions, filingDetails, revenueMix, revenueMixComposer,
                new CompanyHorizonSignalPolicy(),
                null, new CompanyInvestmentDecisionPolicy(),
                executor, true, clock, cacheTtl, OperationalEventSink.noop());
    }

    public EnrichCompanyResearchService(
            EvaluateCompanyResearchParityUseCase coreResearch,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            EvaluateCompanySubmissionsParityUseCase submissions,
            EvaluateCompanyFilingDetailParityUseCase filingDetails,
            EvaluateCompanyRevenueMixParityUseCase revenueMix,
            CompanyRevenueMixComposer revenueMixComposer,
            Executor executor,
            Clock clock,
            Duration cacheTtl,
            OperationalEventSink operationalEvents
    ) {
        this(coreResearch, priceSignals, submissions, filingDetails, revenueMix, revenueMixComposer,
                new CompanyHorizonSignalPolicy(),
                null, new CompanyInvestmentDecisionPolicy(),
                executor, true, clock, cacheTtl, operationalEvents);
    }

    public EnrichCompanyResearchService(
            EvaluateCompanyResearchParityUseCase coreResearch,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            EvaluateCompanySubmissionsParityUseCase submissions,
            EvaluateCompanyFilingDetailParityUseCase filingDetails,
            EvaluateCompanyRevenueMixParityUseCase revenueMix,
            CompanyRevenueMixComposer revenueMixComposer,
            CompanyHorizonSignalPolicy horizonPolicy,
            Executor executor,
            Clock clock,
            Duration cacheTtl,
            OperationalEventSink operationalEvents
    ) {
        this(coreResearch, priceSignals, submissions, filingDetails, revenueMix, revenueMixComposer,
                horizonPolicy, null, new CompanyInvestmentDecisionPolicy(),
                executor, true, clock, cacheTtl, operationalEvents);
    }

    public EnrichCompanyResearchService(
            EvaluateCompanyResearchParityUseCase coreResearch,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            EvaluateCompanySubmissionsParityUseCase submissions,
            EvaluateCompanyFilingDetailParityUseCase filingDetails,
            EvaluateCompanyRevenueMixParityUseCase revenueMix,
            CompanyRevenueMixComposer revenueMixComposer,
            CompanyHorizonSignalPolicy horizonPolicy,
            LoadCompanySectorAssessmentPort sectorAssessment,
            CompanyInvestmentDecisionPolicy investmentDecisionPolicy,
            Executor executor,
            Clock clock,
            Duration cacheTtl,
            OperationalEventSink operationalEvents
    ) {
        this(coreResearch, priceSignals, submissions, filingDetails, revenueMix, revenueMixComposer,
                horizonPolicy, sectorAssessment, investmentDecisionPolicy,
                executor, true, clock, cacheTtl, operationalEvents);
    }

    private EnrichCompanyResearchService(
            EvaluateCompanyResearchParityUseCase coreResearch,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            EvaluateCompanySubmissionsParityUseCase submissions,
            EvaluateCompanyFilingDetailParityUseCase filingDetails,
            EvaluateCompanyRevenueMixParityUseCase revenueMix,
            CompanyRevenueMixComposer revenueMixComposer,
            CompanyHorizonSignalPolicy horizonPolicy,
            LoadCompanySectorAssessmentPort sectorAssessment,
            CompanyInvestmentDecisionPolicy investmentDecisionPolicy,
            Executor executor,
            boolean asynchronous,
            Clock clock,
            Duration cacheTtl,
            OperationalEventSink operationalEvents
    ) {
        this.coreResearch = Objects.requireNonNull(coreResearch);
        this.priceSignals = Objects.requireNonNull(priceSignals);
        this.submissions = submissions;
        this.filingDetails = filingDetails;
        this.revenueMix = revenueMix;
        this.revenueMixComposer = Objects.requireNonNull(revenueMixComposer);
        this.horizonPolicy = Objects.requireNonNull(horizonPolicy);
        this.sectorAssessment = sectorAssessment;
        this.investmentDecisionComposer = new CompanyInvestmentDecisionComposer(
                Objects.requireNonNull(investmentDecisionPolicy));
        this.executor = Objects.requireNonNull(executor);
        this.asynchronous = asynchronous;
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtl = Objects.requireNonNull(cacheTtl);
        this.operationalEvents = Objects.requireNonNull(operationalEvents);
        if (cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
    }

    @Override
    public Research enrich(String ticker, Research baseline) {
        var key = normalize(ticker);
        var now = clock.instant();
        var current = cache.get(key);
        if (current != null && now.isBefore(current.loadedAt().plus(cacheTtl))) return current.value();

        if (asynchronous) {
            var cold = current == null;
            var seed = cold
                    ? initialSeed(key, Objects.requireNonNull(baseline))
                    : CompanyResearchProjectionComposer.pendingCurrentDecision(current.value());
            // Core financials and scores are correctness-critical. Returning a
            // captured legacy projection for the first request exposed stale
            // 200~300% growth rates and split-corrupted valuation multiples.
            // Resolve the bounded core and present-tense price decision inputs
            // synchronously once. Filing and mix enrichment remains
            // asynchronous. A stale bottom/reversal state is as dangerous as a
            // stale score because both can authorize an entry.
            if (cold) {
                seed = refreshCoreFailClosed(key, seed);
                seed = refreshPriceSignalsFailClosed(key, "price-signals-current", seed);
                // Guidance, sector context and filing evidence also participate
                // in the execution decision. Until the asynchronous supporting
                // refresh completes, never let captured supporting evidence
                // authorize today's entry action.
                seed = CompanyResearchProjectionComposer.pendingCurrentDecision(seed);
            }
            // Publish the fail-closed seed before scheduling. Concurrent reads
            // and executor rejection must both observe HOLD rather than the
            // previously cached BUY action.
            cache.put(key, new CachedResearch(seed, now));
            evictOldest();
            var refreshSeed = seed;
            if (inFlight.putIfAbsent(key, Boolean.TRUE) == null) {
                try {
                    executor.execute(() -> {
                        try {
                            var refreshed = cold
                                    ? refreshSupportingEvidence(key, refreshSeed)
                                    : refresh(key, refreshSeed);
                            cache.put(key, new CachedResearch(refreshed, clock.instant()));
                            evictOldest();
                        } catch (RuntimeException error) {
                            operationalEvents.degraded("company-research", "async-enrichment", key, error);
                            cache.put(key, new CachedResearch(
                                    CompanyResearchProjectionComposer.pendingCurrentDecision(refreshSeed),
                                    clock.instant()));
                            evictOldest();
                        } finally {
                            inFlight.remove(key);
                        }
                    });
                } catch (RuntimeException rejected) {
                    inFlight.remove(key);
                    operationalEvents.degraded("company-research", "async-submit", key, rejected);
                    // `seed` is already fail-closed and persisted in the cache.
                }
            }
            return seed;
        }

        var enriched = refresh(key, Objects.requireNonNull(baseline));
        cache.put(key, new CachedResearch(enriched, now));
        evictOldest();
        return enriched;
    }

    private Research refresh(String key, Research baseline) {
        var enriched = refreshCoreFailClosed(key, baseline);
        return refreshSupportingEvidence(key, enriched);
    }

    private Research refreshSupportingEvidence(String key, Research baseline) {
        var enriched = baseline;
        enriched = refreshPriceSignalsFailClosed(key, "price-signals", enriched);
        var complete = true;
        if (submissions != null) {
            var result = attemptWithStatus(key, "submissions", enriched,
                    value -> CompanyResearchProjectionComposer.submissions(value, submissions.evaluate(key)));
            enriched = result.value();
            complete &= result.successful();
        }
        if (filingDetails != null) {
            var result = attemptWithStatus(key, "filing-details", enriched,
                    value -> CompanyResearchProjectionComposer.filingDetails(value, filingDetails.evaluate(key)));
            enriched = result.value();
            complete &= result.successful();
        }
        if (revenueMix != null) {
            var result = attemptWithStatus(key, "revenue-mix", enriched,
                    value -> revenueMixComposer.compose(value, revenueMix.evaluate(key).spring()).enrichedDetail());
            enriched = result.value();
            complete &= result.successful();
        }
        if (sectorAssessment != null) {
            var result = attemptWithStatus(key, "sector-context", enriched,
                    value -> sectorAssessment.load(key)
                            .map(assessment -> CompanyResearchProjectionComposer.sectorContext(value, assessment))
                            .orElse(value));
            enriched = result.value();
            complete &= result.successful();
        }
        if (!complete) return CompanyResearchProjectionComposer.pendingCurrentDecision(enriched);
        enriched = refreshDecisionFailClosed(key, "investment-decision", enriched);
        return enriched;
    }

    private Research refreshCoreFailClosed(String key, Research current) {
        try {
            return CompanyResearchProjectionComposer.core(current, coreResearch.evaluate(key));
        } catch (RuntimeException error) {
            operationalEvents.degraded("company-research", "fundamentals", key, error);
            return CompanyResearchProjectionComposer.pendingCurrentCore(current);
        }
    }

    private Research refreshPriceSignalsFailClosed(String key, String operation, Research current) {
        try {
            return CompanyResearchProjectionComposer.priceSignals(
                    current, priceSignals.evaluate(key), horizonPolicy);
        } catch (RuntimeException error) {
            operationalEvents.degraded("company-research", operation, key, error);
            return CompanyResearchProjectionComposer.pendingCurrentPriceSignals(current);
        }
    }

    private Research refreshDecisionFailClosed(String key, String operation, Research current) {
        try {
            return investmentDecisionComposer.compose(current, java.time.LocalDate.now(clock));
        } catch (RuntimeException error) {
            operationalEvents.degraded("company-research", operation, key, error);
            return CompanyResearchProjectionComposer.pendingCurrentDecision(current);
        }
    }

    private Research initialSeed(String key, Research baseline) {
        var enriched = attempt(key, "legacy-seed-normalization", baseline,
                CompanyResearchProjectionComposer::legacySeed);
        enriched = attempt(key, "revenue-mix-seed-validation", enriched,
                revenueMixComposer::sanitizeBaseline);
        enriched = CompanyResearchProjectionComposer.pendingCurrentCore(enriched);
        enriched = CompanyResearchProjectionComposer.pendingCurrentPriceSignals(enriched);
        if (sectorAssessment != null) {
            enriched = attempt(key, "sector-context-seed", enriched,
                    value -> sectorAssessment.load(key)
                            .map(assessment -> CompanyResearchProjectionComposer.sectorContext(value, assessment))
                            .orElse(value));
        }
        return attempt(
                key,
                "investment-decision-baseline",
                enriched,
                value -> investmentDecisionComposer.compose(
                        value, java.time.LocalDate.now(clock)));
    }

    private Research attempt(String key, String operation, Research current, UnaryOperator<Research> enrichment) {
        try {
            return enrichment.apply(current);
        } catch (RuntimeException error) {
            operationalEvents.degraded("company-research", operation, key, error);
            return current;
        }
    }

    private EnrichmentAttempt attemptWithStatus(
            String key,
            String operation,
            Research current,
            UnaryOperator<Research> enrichment
    ) {
        try {
            return new EnrichmentAttempt(enrichment.apply(current), true);
        } catch (RuntimeException error) {
            operationalEvents.degraded("company-research", operation, key, error);
            return new EnrichmentAttempt(current, false);
        }
    }

    private void evictOldest() {
        while (cache.size() > MAX_ENTRIES) {
            var oldest = cache.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().loadedAt()))
                    .orElse(null);
            if (oldest == null) return;
            cache.remove(oldest.getKey(), oldest.getValue());
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("ticker is required");
        return value.trim().toUpperCase(Locale.ROOT).replace('.', '-');
    }

    private record CachedResearch(Research value, Instant loadedAt) {
    }

    private record EnrichmentAttempt(Research value, boolean successful) {
        private EnrichmentAttempt {
            Objects.requireNonNull(value, "value");
        }
    }
}
