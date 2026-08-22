package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyResearchSummarySnapshot;
import io.macrosquare.company.application.model.CompanyMacdTimingSnapshot;
import io.macrosquare.company.application.port.in.EvaluateCompanyPriceSignalParityUseCase;
import io.macrosquare.company.application.port.in.EvaluateCompanyResearchParityUseCase;
import io.macrosquare.company.application.port.in.RefreshCompanyResearchSummariesUseCase;
import io.macrosquare.company.application.port.out.CompanyResearchSummaryRepository;
import io.macrosquare.company.application.port.out.LoadCompanyAnalystUniversePort;
import io.macrosquare.company.application.port.out.LoadCompanyReadPort;
import io.macrosquare.company.application.port.out.LoadCompanySectorAssessmentPort;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.domain.horizon.CompanyHorizonSignalPolicy;
import io.macrosquare.company.domain.investment.CompanyInvestmentDecisionPolicy;
import io.macrosquare.shared.application.port.out.OperationalEventSink;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Rebuilds the persisted company-list projection only from current Spring evidence. */
public final class RefreshCompanyResearchSummariesService implements RefreshCompanyResearchSummariesUseCase {

    private final LoadCompanyAnalystUniversePort universe;
    private final EvaluateCompanyResearchParityUseCase research;
    private final EvaluateCompanyPriceSignalParityUseCase priceSignals;
    private final CompanyResearchSummaryRepository repository;
    private final Executor executor;
    private final Clock clock;
    private final OperationalEventSink operationalEvents;
    private final LoadCompanyReadPort companyRead;
    private final LoadCompanySectorAssessmentPort sectorAssessment;
    private final CompanyHorizonSignalPolicy horizonPolicy;
    private final CompanyInvestmentDecisionComposer investmentDecisionComposer;

    public RefreshCompanyResearchSummariesService(
            LoadCompanyAnalystUniversePort universe,
            EvaluateCompanyResearchParityUseCase research,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            CompanyResearchSummaryRepository repository,
            Executor executor,
            Clock clock,
            OperationalEventSink operationalEvents
    ) {
        this(universe, research, priceSignals, repository, executor, clock, operationalEvents,
                null, null, new CompanyHorizonSignalPolicy(), new CompanyInvestmentDecisionPolicy());
    }

    public RefreshCompanyResearchSummariesService(
            LoadCompanyAnalystUniversePort universe,
            EvaluateCompanyResearchParityUseCase research,
            EvaluateCompanyPriceSignalParityUseCase priceSignals,
            CompanyResearchSummaryRepository repository,
            Executor executor,
            Clock clock,
            OperationalEventSink operationalEvents,
            LoadCompanyReadPort companyRead,
            LoadCompanySectorAssessmentPort sectorAssessment,
            CompanyHorizonSignalPolicy horizonPolicy,
            CompanyInvestmentDecisionPolicy investmentDecisionPolicy
    ) {
        this.universe = Objects.requireNonNull(universe);
        this.research = Objects.requireNonNull(research);
        this.priceSignals = Objects.requireNonNull(priceSignals);
        this.repository = Objects.requireNonNull(repository);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.operationalEvents = Objects.requireNonNull(operationalEvents);
        this.companyRead = companyRead;
        this.sectorAssessment = sectorAssessment;
        this.horizonPolicy = Objects.requireNonNull(horizonPolicy);
        this.investmentDecisionComposer = new CompanyInvestmentDecisionComposer(
                Objects.requireNonNull(investmentDecisionPolicy));
    }

    @Override
    public RefreshReport refreshAll() {
        // A quarantined price bundle is retried before healthy rows. Submission
        // order is preserved by the bounded FIFO executor, so one provider gap
        // cannot wait behind the entire universe on the next refresh cycle.
        var tickers = prioritizeMissingPriceSignals(universe.loadTickers(), repository.findAll());
        var outcomes = new ArrayList<CompletableFuture<Outcome>>(tickers.size());
        for (var ticker : tickers) {
            try {
                outcomes.add(CompletableFuture.supplyAsync(() -> refreshOne(ticker), executor));
            } catch (RuntimeException rejected) {
                // Submission failure is a failed current refresh too. Keeping
                // the prior row would leave an old BUY/bottom signal live until
                // the age monitor eventually catches it.
                quarantineExisting(ticker, rejected);
                operationalEvents.degraded("company-summary", "refresh-submit", ticker, rejected);
                outcomes.add(CompletableFuture.completedFuture(
                        new Outcome(ticker, rejected.getClass().getSimpleName())));
            }
        }
        var failures = new ArrayList<String>();
        var written = 0;
        for (var outcome : outcomes) {
            try {
                var result = outcome.join();
                if (result.failure() == null) written++;
                else failures.add(result.ticker() + ":" + result.failure());
            } catch (RuntimeException error) {
                failures.add("UNKNOWN:" + error.getClass().getSimpleName());
            }
        }
        failures.sort(Comparator.naturalOrder());
        return new RefreshReport(tickers.size(), written, failures);
    }

    static List<String> prioritizeMissingPriceSignals(
            List<String> tickers,
            Map<String, CompanyResearchSummarySnapshot> current
    ) {
        Objects.requireNonNull(tickers, "tickers");
        Objects.requireNonNull(current, "current");
        var ordered = new ArrayList<>(tickers);
        // ArrayList.sort is stable: healthy rows retain the universe's deliberate
        // provider pacing order, while missing/incomplete rows move as one group.
        ordered.sort(Comparator.comparingInt(ticker ->
                hasCompletePriceSignals(current.get(normalizeTicker(ticker))) ? 1 : 0));
        return List.copyOf(ordered);
    }

    private static boolean hasCompletePriceSignals(CompanyResearchSummarySnapshot snapshot) {
        return snapshot != null
                && snapshot.priceBottomScore() != null
                && snapshot.volumeConfirmationScore() != null
                && snapshot.failureRiskScore() != null
                && snapshot.confirmedBottomScore() != null
                && snapshot.confirmedBottomState() != null;
    }

    private static String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        return ticker.trim().toUpperCase(java.util.Locale.ROOT).replace('.', '-');
    }

    private Outcome refreshOne(String ticker) {
        try {
            var report = research.evaluate(ticker);
            var fundamentals = report.springFundamentals();
            var score = report.springScore();
            var buy = report.springBuyScore();
            var valuation = fundamentals.valuationQuality();
            // A score assembled from analyst/price fragments while the issuer's
            // core revenue model is unavailable is not comparable with a fully
            // covered company. Persist the available raw observations, but fail
            // closed on company/buy scores until a sector/currency-aware model
            // can calculate the core fundamentals correctly.
            var coreComparable = report.scoreComparable();
            var freshness = report.fundamentalsFreshness();
            var summary = new CompanyResearchSummarySnapshot(
                    ticker,
                    parseDate(fundamentals.asOf()),
                    fundamentals.marketCap(),
                    fundamentals.revenueGrowthYoY(),
                    fundamentals.operatingMargin(),
                    fundamentals.evToSales(),
                    coreComparable ? score.totalScore() : null,
                    coreComparable ? score.growth().value() : null,
                    coreComparable ? score.quality().value() : null,
                    coreComparable ? score.valuation().value() : null,
                    coreComparable ? score.balanceSheet().value() : null,
                    coreComparable ? buy.buyScore() : null,
                    coreComparable ? buyLabel(buy.label()) : null,
                    coreComparable ? buy.appealScore() : null,
                    coreComparable ? buy.crowdingScore() : null,
                    valuation.basis().name(),
                    valuation.valuationEligible(),
                    valuation.warnings(),
                    freshness.status().name(),
                    freshness.latestPeriodicReportDate(),
                    freshness.latestPeriodicFilingDate(),
                    freshness.latestPeriodicForm(),
                    freshness.lagDays(),
                    report.scoreWarnings(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    "HOLD",
                    clock.instant()
            );
            try {
                // The list projection consumes current evidence only. Running
                // the five-year causal backtest for all 277 companies every
                // thirty minutes burned a full CPU core without changing any
                // persisted summary field.
                var directReport = priceSignals.evaluateCurrent(ticker);
                var direct = directReport.spring();
                var confirmedBottom = direct.confirmedBottom();
                var reversal = direct.reversalConfirmation();
                summary = summary.withPriceSignals(
                        direct.priceSignal().priceBottomScore(),
                        direct.priceSignal().volumeConfirmationScore(),
                        direct.priceSignal().failureRiskScore(),
                        confirmedBottom.score(),
                        confirmedBottom.state().name(),
                        confirmedBottom.signalDate(),
                        reversal.status().name(),
                        reversal.score(),
                        priceSignalReasons(confirmedBottom.reasons(), reversal.reasons(), reversal.cautions()),
                        macdTiming(direct.macdMomentum()),
                        clock.instant()
                );
                summary = summary.withExecutionAction(
                        resolveExecutionAction(ticker, report, directReport), clock.instant());
            } catch (RuntimeException error) {
                // Never roll a previous successful price signal into a new
                // fundamentals snapshot when the current chart/basis check failed.
                summary = summary.withoutPriceSignals(clock.instant());
                operationalEvents.degraded("company-summary", "price-signals", ticker, error);
            }
            repository.save(summary);
            return new Outcome(ticker, null);
        } catch (RuntimeException error) {
            quarantineExisting(ticker, error);
            operationalEvents.degraded("company-summary", "refresh", ticker, error);
            return new Outcome(ticker, error.getClass().getSimpleName());
        }
    }

    /**
     * Resolves the same authoritative action used by the company detail view,
     * synchronously from the already-refreshed core and price evidence. The
     * notification path must never infer an execution action from B score alone.
     */
    private String resolveExecutionAction(
            String ticker,
            io.macrosquare.company.application.port.in.CompanyResearchParityReport core,
            io.macrosquare.company.application.port.in.CompanyPriceSignalParityReport price
    ) {
        if (companyRead == null) return "HOLD";
        try {
            var current = CompanyResearchProjectionComposer.legacySeed(companyRead.detail(ticker));
            current = CompanyResearchProjectionComposer.pendingCurrentCore(current);
            current = CompanyResearchProjectionComposer.core(current, core);
            current = CompanyResearchProjectionComposer.priceSignals(current, price, horizonPolicy);
            if (sectorAssessment != null) {
                var beforeSector = current;
                current = sectorAssessment.load(ticker)
                        .map(value -> CompanyResearchProjectionComposer.sectorContext(beforeSector, value))
                        .orElse(beforeSector);
            }
            current = investmentDecisionComposer.compose(current, LocalDate.now(clock));
            if (!(current.positionSizing() instanceof ObjectValue sizing)) return "HOLD";
            var value = sizing.fields().get("action");
            return value instanceof TextValue text ? text.value() : "HOLD";
        } catch (RuntimeException error) {
            operationalEvents.degraded("company-summary", "execution-action", ticker, error);
            return "HOLD";
        }
    }

    private void quarantineExisting(String ticker, RuntimeException error) {
        try {
            repository.findHistoricalForQuarantine(ticker).ifPresent(existing -> repository.save(existing.quarantined(
                    "현재 원천 데이터 갱신 실패(" + error.getClass().getSimpleName()
                            + ")로 기존 점수와 가격 신호를 무효화함",
                    clock.instant()
            )));
        } catch (RuntimeException persistenceError) {
            operationalEvents.degraded("company-summary", "quarantine", ticker, persistenceError);
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String buyLabel(io.macrosquare.company.domain.model.CompanyBuyLabel value) {
        return switch (value) {
            case FAVORABLE -> "매수 우호";
            case SELECTIVE -> "선별 접근";
            case CHASE_RISK -> "추격 주의";
        };
    }

    private static List<String> priceSignalReasons(
            List<String> bottomReasons,
            List<String> reversalReasons,
            List<String> reversalCautions
    ) {
        var reasons = new java.util.LinkedHashSet<String>();
        if (bottomReasons != null) bottomReasons.stream().filter(Objects::nonNull)
                .filter(value -> !value.isBlank()).limit(2).forEach(reasons::add);
        if (reversalReasons != null) reversalReasons.stream().filter(Objects::nonNull)
                .filter(value -> !value.isBlank()).limit(2).forEach(reasons::add);
        if (reversalCautions != null) reversalCautions.stream().filter(Objects::nonNull)
                .filter(value -> !value.isBlank()).limit(1).forEach(reasons::add);
        return List.copyOf(reasons);
    }

    private static CompanyMacdTimingSnapshot macdTiming(
            io.macrosquare.technical.domain.MacdMultiTimeframeAnalysis value
    ) {
        if (value == null) return null;
        return new CompanyMacdTimingSnapshot(
                macdTimeframe(value.daily()),
                macdTimeframe(value.weekly()),
                value.currentWeekProvisional()
        );
    }

    private static CompanyMacdTimingSnapshot.Timeframe macdTimeframe(
            io.macrosquare.technical.domain.MacdSignalAnalysis value
    ) {
        return new CompanyMacdTimingSnapshot.Timeframe(
                value.asOf(),
                value.position().name(),
                value.latestCross().name(),
                value.crossDate(),
                value.sessionsSinceCross(),
                value.histogramState().name(),
                value.divergence().name(),
                value.divergenceConfirmedDate(),
                value.sessionsSinceDivergence(),
                value.divergenceActive()
        );
    }

    private record Outcome(String ticker, String failure) {
    }
}
