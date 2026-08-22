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
import io.macrosquare.market.application.model.MarketCollectionStatus;
import io.macrosquare.market.application.port.in.MarketSnapshotRefreshReport;
import io.macrosquare.market.application.port.in.RefreshMarketSnapshotUseCase;
import io.macrosquare.market.application.port.out.BuildCurrentExecutionPlansPort;
import io.macrosquare.market.application.port.out.EvaluateCurrentTopdownPort;
import io.macrosquare.market.application.port.out.LoadMarketSnapshotProjectionPort;
import io.macrosquare.market.application.port.out.MarketObservationRepository;
import io.macrosquare.market.application.port.out.MarketCollectionStatusRepository;
import io.macrosquare.market.application.port.out.ResolveAutomaticPolicyDirectionPort;
import io.macrosquare.market.application.port.out.SaveMarketSnapshotProjectionPort;
import io.macrosquare.market.domain.allocation.CoreAllocationPolicy;
import io.macrosquare.market.domain.calendar.MarketCalendarEvent;
import io.macrosquare.market.domain.calendar.MarketCalendarPolicy;
import io.macrosquare.market.domain.indicator.CoreDerivedIndicatorPolicy;
import io.macrosquare.market.domain.indicator.MarketSeriesPoint;
import io.macrosquare.market.domain.observation.MarketDataSource;
import io.macrosquare.market.domain.observation.MarketInputFreshnessPolicy;
import io.macrosquare.market.domain.observation.MarketObservation;
import io.macrosquare.market.domain.regime.MacroRegimeEvidence;
import io.macrosquare.market.domain.regime.MacroRegimePolicy;
import io.macrosquare.market.domain.signal.CoreAssetSignalPolicy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Native snapshot orchestration. The evolving public document stays in the application
 * projection while all financial decisions are delegated to framework-free domain policies.
 */
public final class RefreshMarketSnapshotService implements RefreshMarketSnapshotUseCase {

    private static final Set<String> FRED_HISTORY_KEYS = Set.of(
            "DGS10", "DGS30", "T10YIE", "T10Y2Y", "VIXCLS", "BAMLH0A0HYM2", "STLFSI4",
            "WALCL", "WRESBAL", "RRPONTSYD", "WDTGAL", "WTREGEN", "WRMFNS", "M2SL", "WM2NS",
            "TREASURY_MARKETABLE_ISSUANCE",
            "UNRATE", "ICSA", "SOFR", "EFFR", "IORB", "INDPRO", "CPI", "PCE",
            "FEDERAL_DEBT_GDP", "FEDERAL_DEFICIT_GDP"
    );
    private static final Set<String> YAHOO_HISTORY_KEYS = Set.of(
            "SP500", "NASDAQ", "KOSPI", "KOSDAQ", "GOLD", "SILVER", "COPPER", "WTI", "DXY",
            "USDJPY", "USDKRW", "EWZ", "INDA", "VNM", "EWJ", "XLK", "XLF", "XLE", "XLV",
            "XLI", "XLY", "XLC", "XLB", "XLRE", "XLU", "XLP", "SOXX", "SMH", "ITA",
            "GRID", "IGF", "TQQQ", "NQ_FUTURES", "ES_FUTURES", "SKEW", "VVIX", "OVX", "HYG", "IEF",
            "BTC", "ETH", "SOL", "XRP", "BNB",
            "SPY_TR", "XLK_TR", "XLF_TR", "XLE_TR", "XLV_TR", "XLI_TR", "XLY_TR", "XLC_TR",
            "XLB_TR", "XLRE_TR", "XLU_TR", "XLP_TR", "SOXX_TR", "SMH_TR", "ITA_TR", "GRID_TR", "IGF_TR"
    );
    private static final Set<String> KRX_HISTORY_KEYS = Set.of(
            "KOSPI_FOREIGN_NET_1D", "KOSPI_INDIVIDUAL_NET_1D",
            "KOSPI_INSTITUTION_NET_1D", "KOSPI_PENSION_NET_1D"
    );
    private static final Set<String> SENTIMENT_HISTORY_KEYS = Set.of(
            "PC_RATIO", "AAII_BULL_BEAR_SPREAD", "NAAIM_EXPOSURE"
    );
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final MarketCalendarPolicy CALENDAR_POLICY = new MarketCalendarPolicy();

    private final LoadMarketSnapshotProjectionPort loadProjection;
    private final SaveMarketSnapshotProjectionPort saveProjection;
    private final MarketObservationRepository repository;
    private final CoreDerivedIndicatorPolicy derivedPolicy;
    private final MacroRegimePolicy regimePolicy;
    private final CoreAssetSignalPolicy signalPolicy;
    private final CoreAllocationPolicy allocationPolicy;
    private final Clock clock;
    private final Duration cacheTtl;
    private final ResolveAutomaticPolicyDirectionPort automaticPolicyDirection;
    private final MarketInputFreshnessPolicy freshnessPolicy;
    private final EvaluateCurrentTopdownPort currentTopdown;
    private final BuildCurrentExecutionPlansPort currentExecutionPlans;
    private final MarketCollectionStatusRepository collectionStatuses;
    private final Map<MarketDataSource, Duration> maximumCollectionSilence;

    public RefreshMarketSnapshotService(
            LoadMarketSnapshotProjectionPort loadProjection,
            SaveMarketSnapshotProjectionPort saveProjection,
            MarketObservationRepository repository,
            CoreDerivedIndicatorPolicy derivedPolicy,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock,
            Duration cacheTtl
    ) {
        this(
                loadProjection, saveProjection, repository, derivedPolicy, regimePolicy,
                signalPolicy, allocationPolicy, clock, cacheTtl, java.util.Optional::empty);
    }

    public RefreshMarketSnapshotService(
            LoadMarketSnapshotProjectionPort loadProjection,
            SaveMarketSnapshotProjectionPort saveProjection,
            MarketObservationRepository repository,
            CoreDerivedIndicatorPolicy derivedPolicy,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock,
            Duration cacheTtl,
            ResolveAutomaticPolicyDirectionPort automaticPolicyDirection
    ) {
        this(loadProjection, saveProjection, repository, derivedPolicy, regimePolicy,
                signalPolicy, allocationPolicy, clock, cacheTtl, automaticPolicyDirection,
                new MarketInputFreshnessPolicy());
    }

    public RefreshMarketSnapshotService(
            LoadMarketSnapshotProjectionPort loadProjection,
            SaveMarketSnapshotProjectionPort saveProjection,
            MarketObservationRepository repository,
            CoreDerivedIndicatorPolicy derivedPolicy,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock,
            Duration cacheTtl,
            ResolveAutomaticPolicyDirectionPort automaticPolicyDirection,
            MarketInputFreshnessPolicy freshnessPolicy
    ) {
        this(loadProjection, saveProjection, repository, derivedPolicy, regimePolicy,
                signalPolicy, allocationPolicy, clock, cacheTtl, automaticPolicyDirection,
                freshnessPolicy, null, context -> null, MarketCollectionStatusRepository.none());
    }

    public RefreshMarketSnapshotService(
            LoadMarketSnapshotProjectionPort loadProjection,
            SaveMarketSnapshotProjectionPort saveProjection,
            MarketObservationRepository repository,
            CoreDerivedIndicatorPolicy derivedPolicy,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock,
            Duration cacheTtl,
            ResolveAutomaticPolicyDirectionPort automaticPolicyDirection,
            MarketInputFreshnessPolicy freshnessPolicy,
            EvaluateCurrentTopdownPort currentTopdown,
            BuildCurrentExecutionPlansPort currentExecutionPlans
    ) {
        this(loadProjection, saveProjection, repository, derivedPolicy, regimePolicy,
                signalPolicy, allocationPolicy, clock, cacheTtl, automaticPolicyDirection,
                freshnessPolicy, currentTopdown, currentExecutionPlans, MarketCollectionStatusRepository.none(),
                defaultMaximumCollectionSilence());
    }

    public RefreshMarketSnapshotService(
            LoadMarketSnapshotProjectionPort loadProjection,
            SaveMarketSnapshotProjectionPort saveProjection,
            MarketObservationRepository repository,
            CoreDerivedIndicatorPolicy derivedPolicy,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock,
            Duration cacheTtl,
            ResolveAutomaticPolicyDirectionPort automaticPolicyDirection,
            MarketInputFreshnessPolicy freshnessPolicy,
            EvaluateCurrentTopdownPort currentTopdown,
            BuildCurrentExecutionPlansPort currentExecutionPlans,
            MarketCollectionStatusRepository collectionStatuses
    ) {
        this(loadProjection, saveProjection, repository, derivedPolicy, regimePolicy,
                signalPolicy, allocationPolicy, clock, cacheTtl, automaticPolicyDirection,
                freshnessPolicy, currentTopdown, currentExecutionPlans, collectionStatuses,
                defaultMaximumCollectionSilence());
    }

    public RefreshMarketSnapshotService(
            LoadMarketSnapshotProjectionPort loadProjection,
            SaveMarketSnapshotProjectionPort saveProjection,
            MarketObservationRepository repository,
            CoreDerivedIndicatorPolicy derivedPolicy,
            MacroRegimePolicy regimePolicy,
            CoreAssetSignalPolicy signalPolicy,
            CoreAllocationPolicy allocationPolicy,
            Clock clock,
            Duration cacheTtl,
            ResolveAutomaticPolicyDirectionPort automaticPolicyDirection,
            MarketInputFreshnessPolicy freshnessPolicy,
            EvaluateCurrentTopdownPort currentTopdown,
            BuildCurrentExecutionPlansPort currentExecutionPlans,
            MarketCollectionStatusRepository collectionStatuses,
            Map<MarketDataSource, Duration> maximumCollectionSilence
    ) {
        this.loadProjection = Objects.requireNonNull(loadProjection);
        this.saveProjection = Objects.requireNonNull(saveProjection);
        this.repository = Objects.requireNonNull(repository);
        this.derivedPolicy = Objects.requireNonNull(derivedPolicy);
        this.regimePolicy = Objects.requireNonNull(regimePolicy);
        this.signalPolicy = Objects.requireNonNull(signalPolicy);
        this.allocationPolicy = Objects.requireNonNull(allocationPolicy);
        this.clock = Objects.requireNonNull(clock);
        this.cacheTtl = Objects.requireNonNull(cacheTtl);
        this.automaticPolicyDirection = Objects.requireNonNull(automaticPolicyDirection);
        this.freshnessPolicy = Objects.requireNonNull(freshnessPolicy);
        this.currentTopdown = currentTopdown;
        this.currentExecutionPlans = Objects.requireNonNull(currentExecutionPlans);
        this.collectionStatuses = Objects.requireNonNull(collectionStatuses);
        var silence = new EnumMap<MarketDataSource, Duration>(MarketDataSource.class);
        silence.putAll(Objects.requireNonNull(maximumCollectionSilence));
        for (var source : MarketDataSource.values()) {
            var duration = silence.get(source);
            if (duration == null || duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("maximum collection silence is required for " + source);
            }
        }
        this.maximumCollectionSilence = Map.copyOf(silence);
        if (cacheTtl.isNegative() || cacheTtl.isZero()) throw new IllegalArgumentException("cacheTtl must be positive");
    }

    @Override
    public MarketSnapshotRefreshReport refresh() {
        var startedAt = clock.instant();
        var seed = loadProjection.loadCurrentOrSeed();
        var root = mutable(seed.root());
        var asOf = LocalDate.ofInstant(startedAt, ZoneOffset.UTC);

        var latestBySource = new EnumMap<MarketDataSource, List<MarketObservation>>(MarketDataSource.class);
        for (var source : MarketDataSource.values()) latestBySource.put(source, repository.loadLatest(source));
        var rawProjection = mutableObject(root.get("raw"), "raw");
        latestBySource.values().forEach(items -> items.forEach(item -> rawProjection.put(item.key(), rawPoint(item, asOf))));
        copyRaw(rawProjection, "SP500", "SP500_SPOT");
        copyRaw(rawProjection, "NASDAQ", "NASDAQ_SPOT");
        annotateFreshness(rawProjection, asOf, true);
        root.put("raw", new ObjectValue(rawProjection));
        var rawInputs = usableNumberChildren(rawProjection, asOf, true);
        var rawNumbers = rawInputs.values();

        var histories = histories(asOf);
        var computed = derivedPolicy.evaluate(rawNumbers, histories, asOf);
        var derivedProjection = mutableObject(root.get("derived"), "derived");
        computed.forEach((key, value) -> {
            // Insufficient freshly seeded history must never erase a last-valid
            // projection. A non-null replacement, however, proves that all raw/history
            // inputs required by this calculation passed the current freshness policy.
            // Its date is therefore the current evaluation date even when a discrete
            // state/percentile happens to be numerically unchanged. Keeping the old
            // date made unchanged sector states expire after seven days and dropped
            // the complete 11-sector universe from the read model.
            if (value.value() == null && hasNumericIndicator(derivedProjection.get(key))) return;
            derivedProjection.put(key, indicator(
                    value.name(), value.value(), value.date().toString(), value.formula()));
        });
        annotateFreshness(derivedProjection, asOf, false);
        var derivedInputs = usableNumberChildren(derivedProjection, asOf, false);
        var derivedNumbers = derivedInputs.values();

        var previousMeta = mutableObject(root.get("meta"), "meta");
        var autoInputs = new LinkedHashMap<>(optionalObject(previousMeta.get("autoInputs")));
        automaticPolicyDirection.resolve().ifPresent(value -> {
            autoInputs.put("policyDirection", number(value.direction()));
            autoInputs.put("policyConfidence", number(value.confidence()));
            autoInputs.put("policySource", text(value.source()));
            autoInputs.put("policyAsOf", text(value.asOf().toString()));
        });
        previousMeta.put("autoInputs", new ObjectValue(autoInputs));
        var policyDirection = integer(autoInputs.get("policyDirection"), 0);
        var geoRisk = integer(autoInputs.get("geoRisk"), 2);
        var smartMoney = optionalObject(previousMeta.get("smartMoney"));
        var smartMoneyInput = SmartMoneyFreshnessResolver.resolve(smartMoney, asOf, freshnessPolicy);
        previousMeta.put("smartMoneyFreshness", SmartMoneyFreshnessResolver.metadata(smartMoneyInput));
        var regime = regimePolicy.evaluate(
                new MacroRegimeEvidence(
                        rawNumbers, derivedNumbers, policyDirection, geoRisk, smartMoneyInput.scoreForDecision()), asOf);
        var signals = signalPolicy.evaluate(rawNumbers, derivedNumbers, regime, asOf);

        var profile = optionalObject(previousMeta.get("profile"));
        var horizon = text(profile.get("investmentHorizon"), "long");
        var leverageEnabled = bool(profile.get("leverageEnabled"), true);
        var includeKorea = bool(profile.get("includeKR"), true);
        var allocation = allocationPolicy.evaluate(regime, signals, rawNumbers, derivedNumbers,
                horizon, leverageEnabled, includeKorea, asOf);

        var decisionContext = decisionContext(startedAt, regime.regime().name(), regime.score(),
                rawNumbers, derivedNumbers, rawInputs.observedOn(), derivedInputs.observedOn(),
                allocation.allocations(), signals);
        var nativePlans = currentExecutionPlans.build(decisionContext);
        if (nativePlans != null) {
            previousMeta.put("executionPlans", nativePlans);
            previousMeta.put("executionPlanFreshness", executionPlanFreshness(startedAt));
        }
        refreshCurrentTopdown(previousMeta, decisionContext);

        root.put("timestamp", text(startedAt.toString()));
        root.put("derived", new ObjectValue(derivedProjection));
        root.put("regime", regime(regime));
        root.put("signals", signals(signals));
        root.put("allocation", allocation(allocation));
        root.put("meta", meta(previousMeta, latestBySource, computed.size(), startedAt,
                regime.regime().name(), rawInputs, derivedInputs));
        var result = new Document(new ObjectValue(root));
        saveProjection.save(result);
        return new MarketSnapshotRefreshReport(startedAt, clock.instant(), rawProjection.size(),
                derivedProjection.size(), computed.size(), regime.regime().name(), regime.score(), result);
    }

    private Map<String, List<MarketSeriesPoint>> histories(LocalDate asOf) {
        var result = new LinkedHashMap<String, List<MarketSeriesPoint>>();
        FRED_HISTORY_KEYS.forEach(key -> result.put("FRED:" + key,
                usablePoints(key, repository.loadHistory(MarketDataSource.FRED, key), asOf)));
        YAHOO_HISTORY_KEYS.forEach(key -> result.put("YAHOO:" + key,
                usablePoints(key, repository.loadHistory(MarketDataSource.YAHOO, key), asOf)));
        KRX_HISTORY_KEYS.forEach(key -> result.put("KRX:" + key,
                usablePoints(key, repository.loadHistory(MarketDataSource.KRX, key), asOf)));
        SENTIMENT_HISTORY_KEYS.forEach(key -> result.put("SENTIMENT:" + key,
                usablePoints(key, repository.loadHistory(MarketDataSource.SENTIMENT, key), asOf)));
        return Map.copyOf(result);
    }

    private List<MarketSeriesPoint> usablePoints(
            String key,
            List<MarketObservation> observations,
            LocalDate asOf
    ) {
        if (observations.isEmpty()) return List.of();
        var latest = observations.stream().map(MarketObservation::observationDate)
                .max(LocalDate::compareTo).orElse(null);
        return freshnessPolicy.usableRaw(key, latest, asOf) ? points(observations) : List.of();
    }

    private static List<MarketSeriesPoint> points(List<MarketObservation> values) {
        return values.stream().map(item -> new MarketSeriesPoint(item.observationDate(), item.value())).toList();
    }

    private ObjectValue meta(
            LinkedHashMap<String, StructuredValue> previous,
            Map<MarketDataSource, List<MarketObservation>> latest,
            int coreDerivedCount,
            java.time.Instant refreshedAt,
            String regime,
            FreshInputs rawInputs,
            FreshInputs derivedInputs
    ) {
        var meta = new LinkedHashMap<>(previous);
        meta.put("fetchedAt", text(refreshedAt.toString()));
        meta.put("cacheTtlMs", number(cacheTtl.toMillis()));
        meta.put("nextRefreshAt", text(refreshedAt.plus(cacheTtl).toString()));
        meta.put("usPriceSource", text("spot"));
        var sourceFrequencies = new LinkedHashMap<>(optionalObject(meta.get("sourceFrequencies")));
        sourceFrequencies.put("NAVER_FINANCE", text("일간 / 서버 30분 수집 / 최근 60영업일 멱등 갱신"));
        meta.put("sourceFrequencies", new ObjectValue(sourceFrequencies));
        var historyGuarantee = new LinkedHashMap<>(optionalObject(meta.get("historyGuarantee")));
        historyGuarantee.put("NAVER_FINANCE", text("최근 60영업일 + 일일 누적"));
        meta.put("historyGuarantee", new ObjectValue(historyGuarantee));
        var dates = new LinkedHashMap<String, StructuredValue>();
        latest.forEach((source, values) -> dates.put(sourceLabel(source), text(values.stream()
                .map(MarketObservation::observationDate).max(LocalDate::compareTo).map(LocalDate::toString).orElse(""))));
        dates.put("DERIVED", text(LocalDate.ofInstant(refreshedAt, ZoneOffset.UTC).toString()));
        meta.put("latestDates", new ObjectValue(dates));
        meta.put("staleness", staleness(latest, refreshedAt));
        meta.put("inputFreshness", inputFreshness(rawInputs, derivedInputs));
        meta.put("collectionHealth", collectionHealth(refreshedAt));
        normalizeCalendar(meta, LocalDate.ofInstant(refreshedAt, SEOUL), refreshedAt);
        if (meta.containsKey("executionPlans") && !sourceIs(meta.get("executionPlanFreshness"), "spring-native")) {
            var planFreshness = new LinkedHashMap<String, StructuredValue>();
            planFreshness.put("source", text("legacy-handoff"));
            planFreshness.put("eligibleForExecution", new BooleanValue(false));
            planFreshness.put("reason", text("Spring 전환 seed의 과거 계산값이며 현재 산출시점·입력 신선도를 증명할 수 없어 자동 집행에서 제외"));
            meta.put("executionPlanFreshness", new ObjectValue(planFreshness));
        }
        if (meta.containsKey("topdown") && !sourceIs(meta.get("topdownFreshness"), "spring-native")) {
            var topdownFreshness = new LinkedHashMap<String, StructuredValue>();
            topdownFreshness.put("source", text("legacy-handoff"));
            topdownFreshness.put("eligibleForCurrentRanking", new BooleanValue(false));
            topdownFreshness.put("reason", text("Spring 전환 seed의 과거 요약·순위이며 현재 섹터 모멘텀과 별도 참고 자료로만 보존"));
            meta.put("topdownFreshness", new ObjectValue(topdownFreshness));
        }
        var nativeState = new LinkedHashMap<String, StructuredValue>();
        nativeState.put("runtime", text("java-21-spring-boot-4.1"));
        nativeState.put("calculationOwner", text("spring"));
        nativeState.put("refreshedAt", text(refreshedAt.toString()));
        nativeState.put("coreDerivedCount", number(coreDerivedCount));
        nativeState.put("longTailPolicy", text("last-valid-observation-with-original-date"));
        nativeState.put("regime", text(regime));
        meta.put("nativeComputation", new ObjectValue(nativeState));
        return new ObjectValue(meta);
    }

    private ObjectValue collectionHealth(java.time.Instant refreshedAt) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("asOf", text(refreshedAt.toString()));
        fields.put("usedForInvestmentScores", new BooleanValue(false));
        fields.put("policy", text("수집 실행 상태는 운영 진단용이며 값의 발표일 신선도 게이트를 대체하지 않음"));
        try {
            var statuses = collectionStatuses.loadLatest();
            var sources = new LinkedHashMap<String, StructuredValue>();
            var degraded = 0;
            var failed = 0;
            var limited = 0;
            for (var source : MarketDataSource.values()) {
                var status = statuses.get(source);
                if (status == null) continue;
                var ageMinutes = Math.max(0, ChronoUnit.MINUTES.between(status.completedAt(), refreshedAt));
                var maximumSilenceMinutes = maximumCollectionSilence.get(source).toMinutes();
                var staleRun = ageMinutes > maximumSilenceMinutes;
                var policyLimited = status.state() == MarketCollectionStatus.State.DEGRADED
                        && "PROVIDER_POLICY_UNAVAILABLE".equals(status.failureType());
                if (status.state() != MarketCollectionStatus.State.FAILED
                        && (!policyLimited && status.state() == MarketCollectionStatus.State.DEGRADED || staleRun)) {
                    degraded++;
                }
                if (policyLimited && !staleRun) limited++;
                if (status.state() == MarketCollectionStatus.State.FAILED) failed++;
                var sourceFields = new LinkedHashMap<String, StructuredValue>();
                sourceFields.put("status", text(status.state() == MarketCollectionStatus.State.FAILED
                        ? "FAILED" : staleRun ? "STALE" : policyLimited ? "LIMITED" : status.state().name()));
                sourceFields.put("lastAttemptStatus", text(status.state().name()));
                sourceFields.put("attemptedAt", text(status.attemptedAt().toString()));
                sourceFields.put("completedAt", text(status.completedAt().toString()));
                sourceFields.put("ageMinutes", number(ageMinutes));
                sourceFields.put("maximumSilenceMinutes", number(maximumSilenceMinutes));
                sourceFields.put("collected", number(status.collected()));
                sourceFields.put("persisted", number(status.persisted()));
                sourceFields.put("failureKeys", texts(status.failureKeys()));
                sourceFields.put("failureType", text(status.failureType()));
                sources.put(source.name(), new ObjectValue(sourceFields));
            }
            fields.put("status", text(failed > 0 ? "FAILED" : degraded > 0 ? "DEGRADED" : limited > 0 ? "LIMITED"
                    : sources.isEmpty() ? "UNKNOWN" : "HEALTHY"));
            fields.put("sourceCount", number(sources.size()));
            fields.put("degradedCount", number(degraded));
            fields.put("limitedCount", number(limited));
            fields.put("failedCount", number(failed));
            fields.put("sources", new ObjectValue(sources));
        } catch (RuntimeException error) {
            fields.put("status", text("UNAVAILABLE"));
            fields.put("sourceCount", number(0));
            fields.put("degradedCount", number(0));
            fields.put("limitedCount", number(0));
            fields.put("failedCount", number(0));
            fields.put("sources", new ObjectValue(Map.of()));
            fields.put("reason", text("수집 상태 원장을 읽지 못해 개별 값의 날짜 신선도만 적용"));
        }
        return new ObjectValue(fields);
    }

    private static Map<MarketDataSource, Duration> defaultMaximumCollectionSilence() {
        return Map.of(
                MarketDataSource.FRED, Duration.ofHours(13),
                MarketDataSource.YAHOO, Duration.ofMinutes(35),
                MarketDataSource.FEAR_GREED, Duration.ofHours(3),
                MarketDataSource.SENTIMENT, Duration.ofHours(13),
                MarketDataSource.STABLECOIN, Duration.ofHours(13),
                MarketDataSource.KRX, Duration.ofMinutes(65)
        );
    }

    private static void normalizeCalendar(
            LinkedHashMap<String, StructuredValue> meta,
            LocalDate asOf,
            java.time.Instant calculatedAt
    ) {
        var normalized = CALENDAR_POLICY.evaluate(calendarEvents(meta.get("calendar")), asOf);
        var rows = new ArrayList<StructuredValue>();
        for (var event : normalized) {
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("date", text(event.date().toString()));
            fields.put("name", text(event.name()));
            fields.put("category", text(event.category()));
            fields.put("daysUntil", number(ChronoUnit.DAYS.between(asOf, event.date())));
            fields.put("importance", text(event.importance().name().toLowerCase(java.util.Locale.ROOT)));
            fields.put("source", text(event.origin() == MarketCalendarEvent.Origin.SPRING_RULE
                    ? "spring-rule" : "persisted-source"));
            fields.put("estimated", new BooleanValue(event.estimated()));
            rows.add(new ObjectValue(fields));
        }
        meta.put("calendar", new ArrayValue(rows));
        var methodology = new LinkedHashMap<String, StructuredValue>();
        methodology.put("calculatedAt", text(calculatedAt.toString()));
        methodology.put("dayCountOwner", text("spring-native-kst"));
        methodology.put("directionalSignal", new BooleanValue(false));
        methodology.put("summary", text("과거 이벤트 제거·D-day 재계산·미국 월간 OPEX와 KRX 파생 만기 참고 구간 추가"));
        methodology.put("limitations", texts(List.of(
                "만기 일정은 변동성 경고이며 상승·하락 방향 신호가 아님",
                "KRX 만기 예정일은 휴장·임시 변경을 공식 거래소 일정에서 최종 확인",
                "외국인 선물 순매수와 베이시스 직접 데이터는 현재 점수에 대체 추정하지 않음"
        )));
        meta.put("calendarMethodology", new ObjectValue(methodology));
    }

    private static List<MarketCalendarEvent> calendarEvents(StructuredValue source) {
        if (!(source instanceof ArrayValue array)) return List.of();
        var result = new ArrayList<MarketCalendarEvent>();
        for (var value : array.values()) {
            if (!(value instanceof ObjectValue row)) continue;
            var date = localDate(row.fields().get("date"));
            var name = text(row.fields().get("name"), "");
            var category = text(row.fields().get("category"), "OTHER");
            if (date == null || name.isBlank()) continue;
            var importance = "high".equalsIgnoreCase(text(row.fields().get("importance"), "medium"))
                    ? MarketCalendarEvent.Importance.HIGH : MarketCalendarEvent.Importance.MEDIUM;
            var origin = "spring-rule".equals(text(row.fields().get("source"), ""))
                    ? MarketCalendarEvent.Origin.SPRING_RULE : MarketCalendarEvent.Origin.PERSISTED_SOURCE;
            result.add(new MarketCalendarEvent(
                    date, name, category, importance, origin, bool(row.fields().get("estimated"), false)));
        }
        return List.copyOf(result);
    }

    private void refreshCurrentTopdown(
            LinkedHashMap<String, StructuredValue> meta,
            CurrentMarketDecisionContext context
    ) {
        if (currentTopdown == null) return;
        try {
            var projection = currentTopdown.evaluate(context);
            var minimum = (int) Math.ceil(projection.universeSize() * .7);
            if (projection.currentMomentumCoverage() < minimum) {
                throw new IllegalStateException("current sector momentum coverage is below 70%");
            }
            meta.put("topdown", projection.topdown());
            var freshness = new LinkedHashMap<String, StructuredValue>();
            freshness.put("source", text("spring-native"));
            freshness.put("eligibleForCurrentRanking", new BooleanValue(true));
            freshness.put("calculatedAt", text(context.calculatedAt().toString()));
            freshness.put("currentMomentumCoverage", number(projection.currentMomentumCoverage()));
            freshness.put("universeSize", number(projection.universeSize()));
            freshness.put("reason", text("현재 거시·ETF 모멘텀으로 재계산; 품질·밸류·실적 수정은 저빈도 구조 reference"));
            meta.put("topdownFreshness", new ObjectValue(freshness));
        } catch (RuntimeException error) {
            var freshness = new LinkedHashMap<String, StructuredValue>();
            freshness.put("source", text("unavailable"));
            freshness.put("eligibleForCurrentRanking", new BooleanValue(false));
            freshness.put("reason", text("현재 섹터 입력 커버리지가 부족해 과거 순위를 자동 승격하지 않음"));
            meta.put("topdownFreshness", new ObjectValue(freshness));
        }
    }

    static ObjectValue executionPlanFreshness(java.time.Instant calculatedAt) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("source", text("spring-native"));
        fields.put("eligibleForExecution", new BooleanValue(true));
        fields.put("calculatedAt", text(calculatedAt.toString()));
        fields.put("reason", text("현재 신호·가격·목표 배분으로 계산한 수동 체크리스트이며 주문 전송이 아님"));
        return new ObjectValue(fields);
    }

    private static boolean sourceIs(StructuredValue value, String expected) {
        if (!(value instanceof ObjectValue object)) return false;
        var source = object.fields().get("source");
        return source instanceof TextValue text && expected.equals(text.value());
    }

    private ObjectValue staleness(
            Map<MarketDataSource, List<MarketObservation>> latest,
            java.time.Instant now
    ) {
        var values = new LinkedHashMap<String, StructuredValue>();
        latest.values().stream().flatMap(List::stream).forEach(item -> {
            if (!Set.of("ICSA", "UNRATE", "M2SL", "DGS10", "VIXCLS", "T10Y2Y", "BAMLH0A0HYM2",
                    "WALCL", "WRESBAL", "RRPONTSYD", "WDTGAL", "WTREGEN", "WRMFNS",
                    "TREASURY_MARKETABLE_ISSUANCE",
                    "KOSPI_FOREIGN_NET_1D", "PC_RATIO", "AAII_BULL_BEAR_SPREAD", "NAAIM_EXPOSURE")
                    .contains(item.key())) return;
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("date", text(item.observationDate().toString()));
            fields.put("daysAgo", number(Math.max(0, ChronoUnit.DAYS.between(
                    item.observationDate(), LocalDate.ofInstant(now, ZoneOffset.UTC)))));
            fields.put("frequency", text(frequency(item.key())));
            fields.put("maximumAgeDays", number(freshnessPolicy.maximumRawAgeDays(item.key())));
            fields.put("eligibleForSignals", new BooleanValue(
                    freshnessPolicy.usableRaw(item.key(), item.observationDate(),
                            LocalDate.ofInstant(now, ZoneOffset.UTC))));
            values.put(item.key(), new ObjectValue(fields));
        });
        return new ObjectValue(values);
    }

    private static String frequency(String key) {
        if (Set.of("TREASURY_MARKETABLE_ISSUANCE", "FEDERAL_DEBT_GDP", "FEDERAL_DEFICIT_GDP")
                .contains(key)) return "분기";
        if ("WM2NS".equals(key)) return "주간 관측·월간 발표";
        if (Set.of("UNRATE", "M2SL", "INDPRO", "CPI", "PCE").contains(key)) return "월간";
        if (Set.of("ICSA", "WALCL", "WRESBAL", "WDTGAL", "WTREGEN", "WRMFNS", "STLFSI4",
                "AAII_BULL_BEAR_SPREAD", "NAAIM_EXPOSURE").contains(key)) return "주간";
        return "일간";
    }

    private static ObjectValue inputFreshness(FreshInputs raw, FreshInputs derived) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("rawUsable", number(raw.values().size()));
        fields.put("rawExcluded", number(raw.excluded().size()));
        fields.put("derivedUsable", number(derived.values().size()));
        fields.put("derivedExcluded", number(derived.excluded().size()));
        var excluded = new ArrayList<String>();
        raw.excluded().stream().sorted().limit(20).map(key -> "RAW:" + key).forEach(excluded::add);
        derived.excluded().stream().sorted().limit(40).map(key -> "DERIVED:" + key).forEach(excluded::add);
        fields.put("excludedKeys", texts(excluded));
        fields.put("policy", text("발표주기별 신선도 초과 입력은 화면에 보존하되 신호 산식에서 제외"));
        return new ObjectValue(fields);
    }

    private ObjectValue rawPoint(MarketObservation item, LocalDate asOf) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("code", text(item.providerCode()));
        fields.put("value", number(item.value()));
        fields.put("date", text(item.observationDate().toString()));
        fields.put("source", text(sourceLabel(item.source(), item.key())));
        fields.put("maximumAgeDays", number(freshnessPolicy.maximumRawAgeDays(item.key())));
        fields.put("eligibleForSignals", new BooleanValue(
                freshnessPolicy.usableRaw(item.key(), item.observationDate(), asOf)));
        return new ObjectValue(fields);
    }

    private void annotateFreshness(
            Map<String, StructuredValue> projection,
            LocalDate asOf,
            boolean raw
    ) {
        projection.replaceAll((key, value) -> {
            if (!(value instanceof ObjectValue object)) return value;
            var fields = new LinkedHashMap<>(object.fields());
            var date = localDate(fields.get("date"));
            var eligible = raw ? freshnessPolicy.usableRaw(key, date, asOf)
                    : freshnessPolicy.usableDerived(key, date, asOf);
            fields.put("maximumAgeDays", number(raw
                    ? freshnessPolicy.maximumRawAgeDays(key)
                    : freshnessPolicy.maximumDerivedAgeDays(key)));
            fields.put("eligibleForSignals", new BooleanValue(eligible));
            return new ObjectValue(fields);
        });
    }

    private static String sourceLabel(MarketDataSource source) {
        return sourceLabel(source, "");
    }

    private static String sourceLabel(MarketDataSource source, String key) {
        return switch (source) {
            case FRED -> "FRED";
            case YAHOO -> "YAHOO";
            case FEAR_GREED -> "CRYPTO_FEAR_GREED".equals(key) ? "ALTERNATIVE_ME" : "CNN";
            case SENTIMENT -> switch (key) {
                case "PC_RATIO" -> "CBOE_OPTION_CHAIN";
                case "AAII_BULL_BEAR_SPREAD" -> "AAII_PUBLIC_FEED";
                case "NAAIM_EXPOSURE" -> "NAAIM";
                default -> "CALC";
            };
            case STABLECOIN -> "CALC";
            case KRX -> "NAVER_FINANCE";
        };
    }

    private static void copyRaw(Map<String, StructuredValue> raw, String source, String target) {
        var value = raw.get(source);
        if (value instanceof ObjectValue object) raw.put(target, object);
    }

    private static ObjectValue indicator(String name, Double value, String date, String formula) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("name", text(name));
        fields.put("value", value == null ? NullValue.INSTANCE : number(value));
        fields.put("date", text(date));
        fields.put("formula", text(formula));
        return new ObjectValue(fields);
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

    private static CurrentMarketDecisionContext decisionContext(
            java.time.Instant calculatedAt,
            String regime,
            int regimeScore,
            Map<String, Double> raw,
            Map<String, Double> derived,
            Map<String, LocalDate> rawObservedOn,
            Map<String, LocalDate> derivedObservedOn,
            Map<String, Integer> allocations,
            List<io.macrosquare.market.domain.signal.CoreAssetSignal> signals
    ) {
        return new CurrentMarketDecisionContext(
                calculatedAt, regime, regimeScore, raw, derived, rawObservedOn, derivedObservedOn, allocations,
                signals.stream().map(signal -> new CurrentMarketDecisionContext.Signal(
                        signal.asset(), signal.action().name(), signal.dataCoveragePct(),
                        signal.reasons(), signal.unmetReasons())).toList());
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

    private FreshInputs usableNumberChildren(
            Map<String, StructuredValue> objects,
            LocalDate asOf,
            boolean raw
    ) {
        var values = new LinkedHashMap<String, Double>();
        var observedOn = new LinkedHashMap<String, LocalDate>();
        var excluded = new ArrayList<String>();
        objects.forEach((key, rawValue) -> {
            if (!(rawValue instanceof ObjectValue object)) return;
            var value = object.fields().get("value");
            if (!(value instanceof NumberValue number)) return;
            var date = localDate(object.fields().get("date"));
            var usable = raw ? freshnessPolicy.usableRaw(key, date, asOf)
                    : freshnessPolicy.usableDerived(key, date, asOf);
            if (usable) {
                values.put(key, number.value().doubleValue());
                if (date != null) observedOn.put(key, date);
            }
            else excluded.add(key);
        });
        return new FreshInputs(Map.copyOf(values), Map.copyOf(observedOn), List.copyOf(excluded));
    }

    private static LocalDate localDate(StructuredValue value) {
        if (!(value instanceof TextValue text)) return null;
        try {
            return LocalDate.parse(text.value());
        } catch (java.time.format.DateTimeParseException ignored) {
            return null;
        }
    }

    private record FreshInputs(
            Map<String, Double> values,
            Map<String, LocalDate> observedOn,
            List<String> excluded
    ) {
    }

    private static boolean hasNumericIndicator(StructuredValue value) {
        if (!(value instanceof ObjectValue object)) return false;
        return object.fields().get("value") instanceof NumberValue;
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

    private static String text(StructuredValue value, String fallback) {
        return value instanceof TextValue text ? text.value() : fallback;
    }

    private static boolean bool(StructuredValue value, boolean fallback) {
        return value instanceof BooleanValue bool ? bool.value() : fallback;
    }

    private static TextValue text(String value) { return new TextValue(value); }
    private static NumberValue number(long value) { return new NumberValue(value); }
    private static NumberValue number(double value) { return new NumberValue(BigDecimal.valueOf(value)); }
    private static ArrayValue texts(List<String> values) {
        return new ArrayValue(values.stream().map(RefreshMarketSnapshotService::text).map(StructuredValue.class::cast).toList());
    }
}
