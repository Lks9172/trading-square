package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanySectorAssessment;
import io.macrosquare.company.application.port.in.CompanyPriceSignalParityReport;
import io.macrosquare.company.application.port.in.CompanyResearchParityReport;
import io.macrosquare.company.application.port.in.CompanyFilingDetailParityReport;
import io.macrosquare.company.application.port.in.CompanySubmissionsParityReport;
import io.macrosquare.company.application.model.CompanySubmissionsSnapshot;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import io.macrosquare.company.domain.model.CompanyBuyScore;
import io.macrosquare.company.domain.model.CompanyExpectationAssessment;
import io.macrosquare.company.domain.model.CompanyFinancialQualityAssessment;
import io.macrosquare.company.domain.model.CompanyFundamentalsSnapshot;
import io.macrosquare.company.domain.model.CompanyGuidanceAnalysis;
import io.macrosquare.company.domain.model.CompanyGuidanceMetric;
import io.macrosquare.company.domain.model.CompanyGuidanceMetricValue;
import io.macrosquare.company.domain.model.CompanyGuidanceSummary;
import io.macrosquare.company.domain.model.CompanyIrMaterial;
import io.macrosquare.company.domain.model.CompanyScore;
import io.macrosquare.company.domain.model.ScoreBreakdown;
import io.macrosquare.company.domain.service.CompanyExpectationAssessmentPolicy;
import io.macrosquare.company.domain.service.CompanyFinancialQualityPolicy;
import io.macrosquare.company.domain.bottom.BottomActionBias;
import io.macrosquare.company.domain.bottom.BottomPriceContext;
import io.macrosquare.company.domain.bottom.BottomPriceSignal;
import io.macrosquare.company.domain.bottom.BottomStructureState;
import io.macrosquare.company.domain.bottom.DeepBottomSignal;
import io.macrosquare.company.domain.bottom.FibonacciRetracementAnalysis;
import io.macrosquare.company.domain.bottom.PriceStructureAnalysis;
import io.macrosquare.company.domain.bottom.ReversalConfirmation;
import io.macrosquare.company.domain.bottom.VolumePriceAnalysis;
import io.macrosquare.company.domain.horizon.CompanyHorizonAction;
import io.macrosquare.company.domain.horizon.CompanyHorizonEvidence;
import io.macrosquare.company.domain.horizon.CompanyHorizonSignal;
import io.macrosquare.company.domain.horizon.CompanyHorizonSignalPolicy;
import io.macrosquare.company.domain.horizon.CompanyHorizonWeights;
import io.macrosquare.company.domain.horizon.CompanyWalkForwardValidation;
import io.macrosquare.company.domain.horizon.HorizonWalkForwardMetric;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Framework-free composer for the public company document.
 *
 * <p>The baseline owns long-tail research fields. Direct Spring evidence only
 * replaces fields for which the Java service has a current source and policy.</p>
 */
final class CompanyResearchProjectionComposer {

    private static final Map<String, Double> BOTTOM_METRIC_WEIGHTS = Map.of(
            "valuation", 0.14, "business", 0.18, "expectation", 0.14,
            "price", 0.12, "pattern", 0.14, "volume", 0.20,
            "guidance", 0.04, "narrative", 0.04
    );

    private CompanyResearchProjectionComposer() {
    }

    static Research core(Research baseline, CompanyResearchParityReport report) {
        var financials = fields(baseline.financials());
        putFundamentals(financials, report.springFundamentals());
        financials.put("analystScore", number(report.springAnalystConsensus().analystScore()));
        financials.put("estimateUpsidePct", number(report.springExpectations().estimateUpsidePct()));
        financials.put("estimateRevision7d", number(report.springExpectations().estimateRevision7d()));
        financials.put("estimateRevision30d", number(report.springExpectations().estimateRevision30d()));
        financials.put("estimateRevision90d", number(report.springExpectations().estimateRevision90d()));
        financials.put("targetUpsideChange30d", number(report.springExpectations().targetUpsideChange30d()));
        // Only the 30-day analyst-rating change has a native history source.
        // Clearing legacy 7/90-day fields prevents stale seed values from
        // re-entering the current verdict.
        financials.put("analystScoreRevision7d", NullValue.INSTANCE);
        financials.put("analystScoreRevision30d", number(report.springExpectations().analystScoreRevision30d()));
        financials.put("analystScoreRevision90d", NullValue.INSTANCE);
        putAnalystHistory(financials, report.analystHistory().history());
        putFreshness(financials, report);

        var buy = report.springBuyScore();
        if (!report.scoreComparable()) {
            var warnings = report.scoreWarnings();
            return copy(
                    baseline,
                    quote(report),
                    new ObjectValue(financials),
                    withheldScore(report.springScore(), warnings),
                    withheldBuyScore(warnings),
                    baseline.reversalConfirmation(),
                    baseline.bottomSignal(),
                    patchPositionSizing(baseline.positionSizing(), "HOLD"),
                    patchExecutionBridge(baseline.executionBridge(), "HOLD"),
                    withheldVerdicts(baseline.verdicts(), warnings)
            );
        }
        var action = action(buy.buyScore());
        var direct = copy(
                baseline,
                quote(report),
                new ObjectValue(financials),
                score(report.springScore()),
                buyScore(buy),
                baseline.reversalConfirmation(),
                baseline.bottomSignal(),
                patchPositionSizing(baseline.positionSizing(), action),
                patchExecutionBridge(baseline.executionBridge(), action),
                patchVerdicts(baseline.verdicts(), report.springScore(), report.springFundamentals(), buy)
        );
        return refreshDerivedInsights(
                direct,
                report.springFundamentals(),
                report.springExpectations(),
                report.springScore(),
                buy
        );
    }

    /**
     * Makes the immediately returned stale-while-revalidate seed semantically
     * safe. Legacy snapshots stored target-upside deltas in fields named as EPS
     * revisions; those values are moved to their real meaning and never exposed
     * as current earnings revisions while direct collection is still running.
     */
    static Research legacySeed(Research baseline) {
        var financials = fields(baseline.financials());
        var targetDelta = decimal(financials.get("targetUpsideChange30d"));
        if (targetDelta == null) targetDelta = decimal(financials.get("estimateRevision30d"));
        financials.put("estimateRevision7d", NullValue.INSTANCE);
        financials.put("estimateRevision30d", NullValue.INSTANCE);
        financials.put("estimateRevision90d", NullValue.INSTANCE);
        financials.put("targetUpsideChange30d", number(targetDelta));
        financials.put("analystScoreRevision7d", NullValue.INSTANCE);
        financials.put("analystScoreRevision30d", NullValue.INSTANCE);
        financials.put("analystScoreRevision90d", NullValue.INSTANCE);
        financials.put("estimateUpsideHistory", new ArrayValue(List.of()));
        financials.put("analystScoreHistory", new ArrayValue(List.of()));
        var cleaned = new Research(
                baseline.profile(), baseline.quote(), new ObjectValue(financials), baseline.score(), baseline.buyScore(),
                baseline.filings(), baseline.irMaterials(), unavailableHighlights(baseline.highlights()), baseline.peerGroup(),
                baseline.bottleneck(), baseline.narrative(), patchCapitalFlow(
                        baseline.capitalFlow(), new io.macrosquare.company.domain.model.CompanyMarketExpectations(
                                decimal(financials.get("estimateUpsidePct")), null, null, null, targetDelta, null)),
                baseline.cashFlowQuality(),
                baseline.multipleInsight(), baseline.guidanceInsight(), baseline.timeframeView(),
                baseline.correctionAssessment(), baseline.thesisMonitor(), baseline.reversalConfirmation(),
                baseline.sectorContext(), baseline.verdicts(), unavailableBottomExpectation(baseline.bottomSignal()),
                baseline.positionSizing(),
                baseline.executionBridge(), baseline.peers()
        );
        CompanyResearchCoreProjection projection;
        try {
            projection = CompanyResearchCoreProjection.from(baseline);
        } catch (RuntimeException malformedLegacyDocument) {
            return cleaned;
        }
        var expectations = new io.macrosquare.company.domain.model.CompanyMarketExpectations(
                projection.expectations().estimateUpsidePct(),
                null, null, null,
                targetDelta,
                null
        );
        return refreshDerivedInsights(
                cleaned,
                projection.fundamentals(),
                expectations,
                projection.score(),
                projection.buyScore()
        );
    }

    private static void putAnalystHistory(
            LinkedHashMap<String, StructuredValue> financials,
            List<CompanyAnalystHistoryPoint> history
    ) {
        financials.put("estimateUpsideHistory", historySeries(
                history, CompanyAnalystHistoryPoint::upsidePct));
        financials.put("analystScoreHistory", historySeries(
                history, CompanyAnalystHistoryPoint::analystScore));
    }

    private static ArrayValue historySeries(
            List<CompanyAnalystHistoryPoint> history,
            java.util.function.Function<CompanyAnalystHistoryPoint, Double> valueExtractor
    ) {
        return new ArrayValue(history.stream()
                .filter(point -> valueExtractor.apply(point) != null)
                .map(point -> {
                    var fields = new LinkedHashMap<String, StructuredValue>();
                    fields.put("date", text(point.date().toString()));
                    fields.put("value", number(valueExtractor.apply(point)));
                    return (StructuredValue) new ObjectValue(fields);
                })
                .toList());
    }

    /** Removes captured calculation values while a direct current-core request is unavailable. */
    static Research pendingCurrentCore(Research baseline) {
        var financials = fields(baseline.financials());
        List.of(
                "revenueTtm", "operatingIncomeTtm", "netIncomeTtm", "freeCashFlowTtm",
                "cash", "debt", "currentAssets", "currentLiabilities", "receivables", "inventory",
                "capexTtm", "operatingCashFlowTtm", "sharesOutstanding", "marketCap", "enterpriseValue",
                "revenueGrowthYoY", "operatingMargin", "operatingMarginTrend", "freeCashFlowMargin",
                "netDebtToRevenue", "evToSales", "evToFcf", "shareDilutionYoY", "stockCompToRevenue",
                "roe", "currentRatio", "receivablesToRevenue", "inventoryToRevenue", "roic",
                "effectiveTaxRate", "shareDilution3yCagr", "accrualRatio"
        ).forEach(field -> financials.put(field, NullValue.INSTANCE));
        financials.put("roicEstimated",
                new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(false));
        financials.put("estimateUpsideHistory", new ArrayValue(List.of()));
        financials.put("analystScoreHistory", new ArrayValue(List.of()));
        financials.put("valuationBasis", text("UNAVAILABLE"));
        financials.put("valuationEligible",
                new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(false));
        financials.put("valuationWarnings", texts(List.of("현재 기업 지표 계산 대기 중")));
        financials.put("fundamentalsStatus", text("PENDING"));

        var ticker = textValue(financials.get("ticker"));
        var quote = fields(object(baseline.quote()));
        if (!quote.containsKey("symbol") || textValue(quote.get("symbol")).isBlank()) {
            quote.put("symbol", text(ticker));
        }
        // The quote belongs to the same direct Yahoo core request as the
        // fundamentals.  Keeping a captured migration price when that request
        // fails makes an old close look live and can also mislead downstream
        // price labels.  Preserve identity only; current value and date must be
        // supplied by a successful direct collection.
        quote.put("price", NullValue.INSTANCE);
        quote.put("date", NullValue.INSTANCE);
        var score = new ObjectValue(Map.of(
                "ticker", text(ticker),
                "totalScore", NullValue.INSTANCE,
                "growth", emptyBreakdown(),
                "quality", emptyBreakdown(),
                "valuation", emptyBreakdown(),
                "balanceSheet", emptyBreakdown(),
                "reasons", texts(List.of("현재 기업 지표 계산 대기 중"))
        ));
        var buy = new ObjectValue(Map.of(
                "appealScore", NullValue.INSTANCE,
                "crowdingScore", NullValue.INSTANCE,
                "buyScore", NullValue.INSTANCE,
                "label", NullValue.INSTANCE,
                "reasons", texts(List.of("현재 기업 지표 계산 전에는 진입 신호로 사용하지 않습니다."))
        ));
        return new Research(
                baseline.profile(), new ObjectValue(quote), new ObjectValue(financials), score, buy,
                baseline.filings(), baseline.irMaterials(), baseline.highlights(), baseline.peerGroup(),
                baseline.bottleneck(), baseline.narrative(), baseline.capitalFlow(), baseline.cashFlowQuality(),
                baseline.multipleInsight(), baseline.guidanceInsight(), baseline.timeframeView(),
                baseline.correctionAssessment(), baseline.thesisMonitor(), baseline.reversalConfirmation(),
                baseline.sectorContext(), withheldVerdicts(
                        baseline.verdicts(), List.of("현재 기업 지표 계산 실패로 액션을 보류함")),
                baseline.bottomSignal(),
                patchPositionSizing(baseline.positionSizing(), "HOLD"),
                patchExecutionBridge(baseline.executionBridge(), "HOLD"), baseline.peers()
        );
    }

    /**
     * Removes captured present-tense price signals before a direct Yahoo
     * history calculation has succeeded.
     *
     * <p>The long-tail legacy document is useful as a display seed, but a
     * previously captured bottom/reversal state must never authorize today's
     * entry. Historical chart and timing values are therefore withheld rather
     * than served stale. A successful {@link #priceSignals(Research,
     * CompanyPriceSignalParityReport, CompanyHorizonSignalPolicy)} call
     * replaces every field cleared here.</p>
     */
    static Research pendingCurrentPriceSignals(Research baseline) {
        var bottom = fields(object(baseline.bottomSignal()));
        bottom.put("score", NullValue.INSTANCE);
        bottom.put("state", text("데이터 부족"));
        bottom.put("actionBias", text("대기"));
        bottom.put("summary", text("현재 가격·거래량 신호 계산 대기 중이며 진입 근거로 사용하지 않습니다."));
        bottom.put("priceBottomScore", NullValue.INSTANCE);
        bottom.put("volumeConfirmationScore", NullValue.INSTANCE);
        bottom.put("failureRiskScore", NullValue.INSTANCE);
        bottom.put("metrics", unavailablePriceMetrics(bottom.get("metrics")));
        bottom.put("chart", emptyChart());
        bottom.put("confirmedBottom", NullValue.INSTANCE);
        bottom.put("technicalConfirmation", NullValue.INSTANCE);
        bottom.put("macdMomentum", NullValue.INSTANCE);
        bottom.put("priceStructure", NullValue.INSTANCE);
        bottom.put("reasons", new ArrayValue(List.of()));
        bottom.put("cautions", texts(List.of("현재 가격·거래량 계산이 끝나기 전에는 과거 주의 문구를 현재 판단으로 사용하지 않습니다.")));
        bottom.put("failureSignals", texts(List.of("현재 가격·거래량 계산 대기 중")));

        return new Research(
                baseline.profile(), baseline.quote(), baseline.financials(), baseline.score(), baseline.buyScore(),
                baseline.filings(), baseline.irMaterials(), baseline.highlights(), baseline.peerGroup(),
                baseline.bottleneck(), baseline.narrative(), baseline.capitalFlow(), baseline.cashFlowQuality(),
                baseline.multipleInsight(), baseline.guidanceInsight(), NullValue.INSTANCE,
                baseline.correctionAssessment(), baseline.thesisMonitor(), NullValue.INSTANCE,
                baseline.sectorContext(), withheldVerdicts(
                        baseline.verdicts(), List.of("현재 가격·거래량 계산 실패로 액션을 보류함")),
                new ObjectValue(bottom),
                patchPositionSizing(baseline.positionSizing(), "HOLD"),
                patchExecutionBridge(baseline.executionBridge(), "HOLD"), baseline.peers()
        );
    }

    /** Keeps display evidence but prevents a stale action surviving a failed decision composition. */
    static Research pendingCurrentDecision(Research baseline) {
        return new Research(
                baseline.profile(), baseline.quote(), baseline.financials(), baseline.score(), baseline.buyScore(),
                baseline.filings(), baseline.irMaterials(), baseline.highlights(), baseline.peerGroup(),
                baseline.bottleneck(), baseline.narrative(), baseline.capitalFlow(), baseline.cashFlowQuality(),
                baseline.multipleInsight(), baseline.guidanceInsight(), baseline.timeframeView(),
                baseline.correctionAssessment(), baseline.thesisMonitor(), baseline.reversalConfirmation(),
                baseline.sectorContext(), withheldVerdicts(
                        baseline.verdicts(), List.of("현재 실행 판단 계산 실패로 액션을 보류함")),
                baseline.bottomSignal(),
                patchPositionSizing(baseline.positionSizing(), "HOLD"),
                patchExecutionBridge(baseline.executionBridge(), "HOLD"), baseline.peers()
        );
    }

    private static ArrayValue unavailablePriceMetrics(StructuredValue source) {
        if (!(source instanceof ArrayValue metrics)) return new ArrayValue(List.of());
        return new ArrayValue(metrics.values().stream().map(item -> {
            if (!(item instanceof ObjectValue metric)) return item;
            var key = textValue(metric.fields().get("key"));
            if (!List.of("price", "pattern", "volume", "absorption").contains(key)) return item;
            var fields = fields(metric);
            fields.put("score", NullValue.INSTANCE);
            fields.put("status", text("neutral"));
            fields.put("detail", text("현재 가격·거래량 계산 대기 중"));
            return (StructuredValue) new ObjectValue(fields);
        }).toList());
    }

    private static ObjectValue emptyChart() {
        return new ObjectValue(Map.of(
                "points", new ArrayValue(List.of()),
                "markers", new ArrayValue(List.of())
        ));
    }

    private static ObjectValue emptyBreakdown() {
        return new ObjectValue(Map.of(
                "value", NullValue.INSTANCE,
                "reasons", texts(List.of("현재 계산 대기 중"))
        ));
    }

    private static ArrayValue unavailableHighlights(ArrayValue source) {
        var values = source.values().stream()
                .filter(TextValue.class::isInstance)
                .map(TextValue.class::cast)
                .map(TextValue::value)
                .filter(value -> !value.contains("업사이드 변화") && !value.contains("컨센서스"))
                .collect(Collectors.toCollection(ArrayList::new));
        values.add("직접 EPS 추정치 갱신 중");
        return texts(values.stream().distinct().limit(7).toList());
    }

    private static StructuredValue unavailableBottomExpectation(StructuredValue source) {
        var bottom = fields(object(source));
        if (bottom.get("metrics") instanceof ArrayValue array) {
            var metrics = array.values().stream().map(item -> {
                if (!(item instanceof ObjectValue metric)
                        || !"expectation".equals(textValue(metric.fields().get("key")))) return item;
                return (StructuredValue) metric(metric, 50,
                        "직접 수집한 EPS 추정치 갱신 전이라 기대 방향을 중립으로 둡니다.");
            }).toList();
            bottom.put("metrics", new ArrayValue(metrics));
        }
        bottom.put("earningsBottomScore", NullValue.INSTANCE);
        if (bottom.get("reasons") instanceof ArrayValue reasons) {
            bottom.put("reasons", new ArrayValue(reasons.values().stream()
                    .filter(TextValue.class::isInstance)
                    .map(TextValue.class::cast)
                    .filter(value -> !value.value().contains("컨센서스")
                            && !value.value().contains("업사이드 변화"))
                    .map(StructuredValue.class::cast)
                    .toList()));
        }
        bottom.put("expectationEvidenceNotice", text(
                "목표가 상승여력 변화는 EPS 추정치 변화로 사용하지 않습니다."));
        return new ObjectValue(bottom);
    }

    static Research priceSignals(Research baseline, CompanyPriceSignalParityReport report) {
        return priceSignals(baseline, report, new CompanyHorizonSignalPolicy());
    }

    static Research priceSignals(
            Research baseline,
            CompanyPriceSignalParityReport report,
            CompanyHorizonSignalPolicy horizonPolicy
    ) {
        var direct = report.spring();
        var signal = direct.priceSignal();
        var confirmed = direct.confirmedBottom();
        var bottom = fields(object(baseline.bottomSignal()));
        bottom.put("state", text(structureLabel(signal.structureState())));
        bottom.put("actionBias", text(actionBiasLabel(confirmed.actionBias())));
        bottom.put("priceBottomScore", number(signal.priceBottomScore()));
        bottom.put("volumeConfirmationScore", number(signal.volumeConfirmationScore()));
        bottom.put("failureRiskScore", number(signal.failureRiskScore()));
        bottom.put("metrics", patchBottomMetrics(bottom.get("metrics"), signal, report.springContext()));
        refreshBottomComposite(bottom);
        bottom.put("summary", text("가격 구조는 %s이며, 확신형 바닥 판정은 %s(%d/100)입니다. %s".formatted(
                structureLabel(signal.structureState()),
                switch (confirmed.state()) {
                    case UNMET -> "미충족";
                    case CANDIDATE -> "후보";
                    case CONVICTION -> "확신";
                },
                confirmed.score(),
                confirmed.summary()
        )));
        var currentCautions = new ArrayList<String>();
        currentCautions.addAll(confirmed.cautions());
        currentCautions.addAll(direct.reversalConfirmation().cautions());
        if (direct.priceStructure() != null) currentCautions.addAll(direct.priceStructure().cautions());
        var uniqueCautions = currentCautions.stream().filter(value -> value != null && !value.isBlank())
                .distinct().limit(6).toList();
        bottom.put("cautions", texts(uniqueCautions));
        bottom.put("failureSignals", texts(uniqueCautions));
        bottom.put("chart", chart(report));
        bottom.put("confirmedBottom", confirmedBottom(confirmed));
        if (direct.technicalConfirmation() != null) {
            bottom.put("technicalConfirmation", technicalConfirmation(direct.technicalConfirmation()));
        }
        if (direct.priceStructure() != null) {
            bottom.put("priceStructure", priceStructure(direct.priceStructure()));
        }
        if (direct.macdMomentum() != null) {
            bottom.put("macdMomentum", macdMomentum(direct.macdMomentum()));
        }

        var updated = copy(
                baseline,
                baseline.quote(), baseline.financials(), baseline.score(), baseline.buyScore(),
                reversal(direct.reversalConfirmation()),
                new ObjectValue(bottom),
                patchPositionSizingForReversal(baseline.positionSizing(), direct.reversalConfirmation()),
                baseline.executionBridge(), baseline.verdicts()
        );
        return withTimeframe(updated, timeframe(updated, report, horizonPolicy));
    }

    static Research submissions(Research baseline, CompanySubmissionsParityReport report) {
        var direct = report.spring();
        var profile = fields(baseline.profile());
        profile.put("ticker", text(direct.profile().ticker()));
        profile.put("cik", text(direct.profile().cik()));
        profile.put("name", text(direct.profile().name()));
        profile.put("exchange", nullableText(direct.profile().exchange()));
        profile.put("sic", nullableText(direct.profile().sic()));

        var filings = direct.filings().stream()
                .map(CompanyResearchProjectionComposer::filing)
                .map(StructuredValue.class::cast)
                .toList();
        return withEvidence(
                baseline,
                new ObjectValue(profile),
                new ArrayValue(filings),
                baseline.irMaterials(),
                baseline.guidanceInsight()
        );
    }

    static Research filingDetails(Research baseline, CompanyFilingDetailParityReport report) {
        var materials = report.spring().stream()
                .map(CompanyResearchProjectionComposer::material)
                .map(StructuredValue.class::cast)
                .toList();
        var selectedGuidance = latestBestGuidance(report.guidance());
        StructuredValue latestGuidance = selectedGuidance
                .map(value -> (StructuredValue) guidanceInsight(value))
                .orElse(baseline.guidanceInsight());
        var updated = withEvidence(
                baseline,
                baseline.profile(),
                baseline.filings(),
                materials.isEmpty() ? baseline.irMaterials() : new ArrayValue(materials),
                latestGuidance
        );
        return selectedGuidance
                .map(value -> patchBottomGuidance(updated, value.summary()))
                .orElse(updated);
    }

    /** Latest filing wins; same-day ties prefer explicit, structured, attachment-backed guidance. */
    static java.util.Optional<CompanyGuidanceAnalysis> latestBestGuidance(
            List<CompanyGuidanceAnalysis> analyses
    ) {
        return analyses.stream().max(
                java.util.Comparator.comparing(CompanyGuidanceAnalysis::filingDate)
                        .thenComparingInt(CompanyResearchProjectionComposer::guidanceQuality)
                        .thenComparing(CompanyGuidanceAnalysis::url)
        );
    }

    private static int guidanceQuality(CompanyGuidanceAnalysis analysis) {
        var explicit = analysis.summary().stance() == CompanyGuidanceSummary.Stance.UNCLEAR ? 0 : 100;
        var structured = analysis.summary().structuredMetricCount() * 10;
        var attachment = "8-K".equals(analysis.form()) || "6-K".equals(analysis.form()) ? 5 : 0;
        return explicit + structured + attachment + Math.min(4, analysis.summary().evidence().size());
    }

    static Research sectorContext(Research baseline, CompanySectorAssessment assessment) {
        var current = object(baseline.sectorContext());
        var result = fields(current);
        result.put("sectorId", text(assessment.sectorId()));
        result.put("label", text(assessment.label()));
        result.put("sectorKey", text(assessment.sectorKey()));
        result.put("classification", text(assessment.classification()));
        result.put("buyScore", number(assessment.buyScore()));
        result.put("qualityScore", number(assessment.qualityScore()));
        result.put("appealScore", number(assessment.appealScore()));
        result.put("crowdingScore", number(assessment.crowdingScore()));
        result.put("valuationScore", number(assessment.valuationScore()));
        result.put("earningsRevisionScore", number(assessment.earningsRevisionScore()));
        result.put("earningsRevisionReferenceScore", number(assessment.referenceEarningsRevisionScore()));
        result.put("rotationScore", number(assessment.rotationScore()));
        result.put("rotationRank", number(assessment.rotationRank()));
        result.put("rotationUniverseSize", number(assessment.rotationUniverseSize()));
        result.put("rotationPercentile", number(assessment.rotationPercentile()));
        result.put("macroFitScore", number(assessment.macroFitScore()));
        result.put("relativeStrengthScore", number(assessment.relativeStrengthScore()));
        result.put("fundamentalScore", number(assessment.fundamentalScore()));
        result.put("flowScore", number(assessment.flowScore()));
        result.put("flowProxyScore", number(assessment.proxyFlowScore()));
        result.put("sectorEvidenceSummary", text(
                "순환·상대강도는 현재 시장값입니다. 이익추정은 기준일 없는 저빈도 참고값이며, "
                        + "독립 수급이 없는 섹터의 스타일 프록시는 최종 확인축에서 제외합니다."));
        result.put("stance", text(assessment.stance()));
        result.put("rotationState", text(assessment.rotationState()));
        result.put("rotationLabel", text(assessment.rotationLabel()));
        result.put("expectedLeadershipWindow", text(assessment.expectedLeadershipWindow()));
        result.put("expectedLeadershipMessage", text(assessment.expectedLeadershipMessage()));
        result.put("rotationReasons", texts(assessment.reasons()));
        var thesis = assessment.expectedLeadershipMessage().isBlank()
                ? assessment.reasons().stream().findFirst().orElse("섹터 판단 근거가 아직 제한적입니다.")
                : assessment.expectedLeadershipMessage();
        result.put("thesis", text(thesis));
        return new Research(
                baseline.profile(), baseline.quote(), baseline.financials(), baseline.score(), baseline.buyScore(),
                baseline.filings(), baseline.irMaterials(), baseline.highlights(), baseline.peerGroup(),
                baseline.bottleneck(), baseline.narrative(), baseline.capitalFlow(), baseline.cashFlowQuality(),
                baseline.multipleInsight(), baseline.guidanceInsight(), baseline.timeframeView(),
                baseline.correctionAssessment(), baseline.thesisMonitor(), baseline.reversalConfirmation(),
                new ObjectValue(result), baseline.verdicts(), baseline.bottomSignal(), baseline.positionSizing(),
                baseline.executionBridge(), baseline.peers()
        );
    }

    private static Research refreshDerivedInsights(
            Research source,
            CompanyFundamentalsSnapshot fundamentals,
            io.macrosquare.company.domain.model.CompanyMarketExpectations expectations,
            CompanyScore companyScore,
            CompanyBuyScore buyScore
    ) {
        var expectation = new CompanyExpectationAssessmentPolicy().evaluate(
                expectations, buyScore.crowdingScore());
        var quality = new CompanyFinancialQualityPolicy().evaluate(fundamentals, companyScore);
        var multiple = patchMultipleInsight(source.multipleInsight(), fundamentals);
        var cashFlow = cashFlowQuality(quality);
        var bottom = patchBottomFundamentals(
                source.bottomSignal(), expectation, quality, multiple, companyScore);
        return new Research(
                source.profile(), source.quote(), source.financials(), source.score(), source.buyScore(),
                source.filings(), source.irMaterials(), highlights(fundamentals, expectations, companyScore),
                source.peerGroup(), source.bottleneck(), source.narrative(),
                patchCapitalFlow(source.capitalFlow(), expectations), cashFlow, multiple,
                source.guidanceInsight(), source.timeframeView(), source.correctionAssessment(),
                source.thesisMonitor(), source.reversalConfirmation(), source.sectorContext(), source.verdicts(),
                bottom, source.positionSizing(), source.executionBridge(), source.peers()
        );
    }

    private static ArrayValue highlights(
            CompanyFundamentalsSnapshot fundamentals,
            io.macrosquare.company.domain.model.CompanyMarketExpectations expectations,
            CompanyScore score
    ) {
        var values = new ArrayList<String>();
        if (fundamentals.revenueGrowthYoY() != null) {
            values.add("매출 YoY " + format1(fundamentals.revenueGrowthYoY()) + "% 성장");
        }
        if (expectations.estimateRevision7d() != null) {
            values.add("7일 EPS 추정치 " + signed1(expectations.estimateRevision7d()) + "%");
        }
        if (expectations.estimateRevision30d() != null) {
            values.add("30일 EPS 추정치 " + signed1(expectations.estimateRevision30d()) + "%");
        }
        if (expectations.estimateRevision90d() != null) {
            values.add("90일 EPS 추정치 " + signed1(expectations.estimateRevision90d()) + "%");
        }
        if (expectations.estimateUpsidePct() != null) {
            values.add("현재 목표가 상승여력 " + signed1(expectations.estimateUpsidePct()) + "%");
        }
        if (expectations.targetUpsideChange30d() != null) {
            values.add("목표가 상승여력 30일 변화 " + signed1(expectations.targetUpsideChange30d()) + "%p");
        }
        values.add("종합 점수 " + score.totalScore() + "/100");
        return texts(values.stream().limit(7).toList());
    }

    private static ObjectValue cashFlowQuality(CompanyFinancialQualityAssessment value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("cashConversionScore", number(value.cashConversionScore()));
        result.put("earningsQualityScore", number(value.earningsQualityScore()));
        result.put("accrualRisk", text(switch (value.accrualRisk()) {
            case LOW -> "낮음";
            case MODERATE -> "보통";
            case HIGH -> "높음";
            case UNAVAILABLE -> "자료부족";
        }));
        result.put("ocfToNetIncome", number(value.operatingCashFlowToNetIncome()));
        result.put("receivablesRisk", text("자료부족"));
        result.put("inventoryRisk", text("자료부족"));
        result.put("liquidityLabel", text(value.liquidityLabel()));
        result.put("summary", text(value.summary()));
        result.put("reasons", texts(value.reasons()));
        result.put("evidenceBasis", text("현재 SEC companyfacts 기반 재계산"));
        return new ObjectValue(result);
    }

    private static StructuredValue patchCapitalFlow(
            StructuredValue source,
            io.macrosquare.company.domain.model.CompanyMarketExpectations expectations
    ) {
        var result = fields(object(source));
        var drivers = new ArrayList<String>();
        var prior = result.get("fundingDrivers");
        if (prior instanceof ArrayValue array) {
            for (var item : array.values()) {
                if (!(item instanceof TextValue text)) continue;
                var value = text.value();
                if (value.contains("컨센서스") || value.contains("애널리스트 업사이드")
                        || value.contains("EPS 추정치") || value.contains("목표가 상승여력")) continue;
                drivers.add(value);
            }
        }
        if (expectations.estimateRevision30d() != null) {
            drivers.add("30일 EPS 추정치 " + signed1(expectations.estimateRevision30d()) + "%");
        }
        if (expectations.estimateUpsidePct() != null && expectations.estimateUpsidePct() > 8) {
            drivers.add("현재 목표가 상승여력 " + signed1(expectations.estimateUpsidePct()) + "%");
        }
        result.put("fundingDrivers", texts(drivers.stream().distinct().limit(5).toList()));
        result.put("flowEvidenceType", text("STRUCTURAL_PROXY"));
        result.put("evidenceNotice", text(
                "ETF 편입·정책·내러티브는 자금 유입 가능성의 프록시이며 실제 펀드 순유입 데이터가 아닙니다."));
        return new ObjectValue(result);
    }

    private static StructuredValue patchMultipleInsight(
            StructuredValue source,
            CompanyFundamentalsSnapshot fundamentals
    ) {
        var result = fields(object(source));
        var peerAverage = decimal(result.get("peerAverageEvToSales"));
        var peerMedian = decimal(result.get("peerMedianEvToSales"));
        var evToSales = fundamentals.evToSales();
        var premiumAverage = premiumPct(evToSales, peerAverage);
        var premiumMedian = premiumPct(evToSales, peerMedian);
        var preferredComparison = premiumMedian != null ? premiumMedian : premiumAverage;
        var relative = preferredComparison == null ? "판단불가"
                : preferredComparison <= -10 ? "할인"
                : preferredComparison >= 15 ? "프리미엄" : "중립";
        var compressionRisk = (fundamentals.evToFcf() != null && fundamentals.evToFcf() >= 45)
                || (evToSales != null && evToSales >= 12) ? "높음"
                : (fundamentals.evToFcf() != null && fundamentals.evToFcf() >= 28)
                || (evToSales != null && evToSales >= 7) ? "보통" : "낮음";
        result.put("valuationVsPeer", text(relative));
        result.put("currentEvToSales", number(evToSales));
        result.put("currentEvToFcf", number(fundamentals.evToFcf()));
        result.put("multipleCompressionRisk", text(compressionRisk));
        result.put("premiumPctVsPeer", number(premiumAverage));
        result.put("premiumPctVsPeerMedian", number(premiumMedian));
        var summary = switch (relative) {
            case "할인" -> "현재 EV/Sales가 동종 기준 대비 할인권입니다.";
            case "프리미엄" -> "현재 EV/Sales가 동종 기준 대비 프리미엄이라 성장 지속 여부가 중요합니다.";
            case "중립" -> "현재 EV/Sales가 동종 기준과 비슷한 범위입니다.";
            default -> "동종 멀티플 기준이 부족해 상대가치를 확정하지 않습니다.";
        };
        result.put("summary", text(summary));
        var reasons = new ArrayList<String>();
        if (evToSales != null) reasons.add("현재 EV/Sales " + format1(evToSales) + "배");
        if (peerAverage != null) reasons.add("peer 평균 " + format1(peerAverage) + "배 대비 " + formatSignedPct(premiumAverage));
        if (peerMedian != null) reasons.add("peer 중앙값 " + format1(peerMedian) + "배 대비 " + formatSignedPct(premiumMedian));
        reasons.add("멀티플 압축 위험 " + compressionRisk);
        result.put("reasons", texts(reasons));
        result.put("evidenceBasis", text("현재 기업 멀티플과 카탈로그 peer 기준 비교"));
        return new ObjectValue(result);
    }

    private static StructuredValue patchBottomFundamentals(
            StructuredValue source,
            CompanyExpectationAssessment expectation,
            CompanyFinancialQualityAssessment quality,
            StructuredValue multipleInsight,
            CompanyScore companyScore
    ) {
        var bottom = fields(object(source));
        var metrics = bottom.get("metrics") instanceof ArrayValue array
                ? new ArrayList<>(array.values()) : new ArrayList<StructuredValue>();
        var businessScore = clampScore(companyScore.totalScore() * 0.55 + quality.cashConversionScore() * 0.45);
        var multiple = object(multipleInsight);
        var relative = textValue(multiple.fields().get("valuationVsPeer"));
        var evToSales = decimal(multiple.fields().get("currentEvToSales"));
        var valuationScore = "할인".equals(relative) ? 78 : "중립".equals(relative) ? 60
                : "프리미엄".equals(relative) ? 38 : evToSales == null ? 55
                : clampScore(Math.max(25, Math.min(82, 82 - evToSales * 4)));

        var updated = new ArrayList<StructuredValue>();
        var foundExpectation = false;
        var foundBusiness = false;
        var foundValuation = false;
        for (var item : metrics) {
            if (!(item instanceof ObjectValue metric)) {
                updated.add(item);
                continue;
            }
            var key = textValue(metric.fields().get("key"));
            if ("expectation".equals(key)) {
                updated.add(metric(metric, expectation.score(), expectation.summary()));
                foundExpectation = true;
            } else if ("business".equals(key)) {
                updated.add(metric(metric, businessScore, quality.summary()));
                foundBusiness = true;
            } else if ("valuation".equals(key)) {
                updated.add(metric(metric, valuationScore, textValue(multiple.fields().get("summary"))));
                foundValuation = true;
            } else updated.add(item);
        }
        if (!foundExpectation) updated.add(metric("expectation", "기대치 정리", expectation.score(), expectation.summary()));
        if (!foundBusiness) updated.add(metric("business", "사업 체력", businessScore, quality.summary()));
        if (!foundValuation) updated.add(metric("valuation", "밸류 리셋", valuationScore,
                textValue(multiple.fields().get("summary"))));
        bottom.put("metrics", new ArrayValue(updated));

        var guidanceScore = metricScore(updated, "guidance", 52);
        var earningsBottom = clampScore(
                expectation.score() * 0.38 + guidanceScore * 0.24
                        + businessScore * 0.24 + valuationScore * 0.14);
        bottom.put("earningsBottomScore", number(earningsBottom));
        refreshBottomComposite(bottom);
        bottom.put("expectationEvidenceNotice", text(
                "기대치 점수는 EPS 추정치 변화만 사용하며 목표가 상승여력 변화는 별도로 표시합니다."));
        return new ObjectValue(bottom);
    }

    /** Recomputes the display composite after either fundamentals or price metrics change. */
    private static void refreshBottomComposite(Map<String, StructuredValue> bottom) {
        if (!(bottom.get("metrics") instanceof ArrayValue array)) {
            bottom.put("score", NullValue.INSTANCE);
            bottom.put("reasons", new ArrayValue(List.of()));
            return;
        }
        var metrics = array.values();
        var weighted = 0.0;
        var totalWeight = 0.0;
        for (var entry : BOTTOM_METRIC_WEIGHTS.entrySet()) {
            var value = metricScore(metrics, entry.getKey(), -1);
            if (value < 0) continue;
            weighted += value * entry.getValue();
            totalWeight += entry.getValue();
        }
        bottom.put("score", totalWeight > 0
                ? number(clampScore(weighted / totalWeight))
                : NullValue.INSTANCE);
        var reasons = metrics.stream()
                .filter(ObjectValue.class::isInstance)
                .map(ObjectValue.class::cast)
                .filter(value -> integer(value, "score") != null && integer(value, "score") >= 68)
                .map(value -> textValue(value.fields().get("label")) + ": "
                        + textValue(value.fields().get("detail")))
                .filter(value -> !value.isBlank())
                .limit(4)
                .toList();
        bottom.put("reasons", texts(reasons));
    }

    private static ObjectValue metric(ObjectValue source, int score, String detail) {
        var result = fields(source);
        result.put("score", number(score));
        result.put("status", text(metricStatus(score)));
        result.put("detail", text(detail == null || detail.isBlank() ? "근거가 제한적입니다." : detail));
        return new ObjectValue(result);
    }

    private static ObjectValue metric(String key, String label, int score, String detail) {
        return new ObjectValue(Map.of(
                "key", text(key), "label", text(label), "score", number(score),
                "status", text(metricStatus(score)), "detail", text(detail == null ? "" : detail)
        ));
    }

    private static int metricScore(List<StructuredValue> metrics, String key, int fallback) {
        for (var item : metrics) {
            if (!(item instanceof ObjectValue metric)) continue;
            if (key.equals(textValue(metric.fields().get("key")))) {
                var score = integer(metric, "score");
                return score == null ? fallback : score;
            }
        }
        return fallback;
    }

    private static String metricStatus(int score) {
        return score >= 68 ? "positive" : score >= 45 ? "neutral" : "negative";
    }

    private static Research withEvidence(
            Research source,
            ObjectValue profile,
            ArrayValue filings,
            ArrayValue irMaterials,
            StructuredValue guidanceInsight
    ) {
        return new Research(
                profile, source.quote(), source.financials(), source.score(), source.buyScore(),
                filings, irMaterials, source.highlights(), source.peerGroup(), source.bottleneck(),
                source.narrative(), source.capitalFlow(), source.cashFlowQuality(), source.multipleInsight(),
                guidanceInsight, source.timeframeView(), source.correctionAssessment(), source.thesisMonitor(),
                source.reversalConfirmation(), source.sectorContext(), source.verdicts(), source.bottomSignal(),
                source.positionSizing(), source.executionBridge(), source.peers()
        );
    }

    private static ObjectValue filing(CompanySubmissionsSnapshot.Filing value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("accessionNumber", text(value.accessionNumber()));
        result.put("filingDate", date(value.filingDate()));
        result.put("form", text(value.form()));
        result.put("primaryDocument", nullableText(value.primaryDocument()));
        result.put("primaryDocDescription", nullableText(value.primaryDocumentDescription()));
        result.put("isEarningsRelated", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.earningsRelated()));
        result.put("filingUrl", nullableText(value.filingUrl()));
        return new ObjectValue(result);
    }

    private static ObjectValue material(CompanyIrMaterial value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("title", text(value.title()));
        result.put("form", text(value.form()));
        result.put("filingDate", date(value.filingDate()));
        result.put("url", text(value.url()));
        result.put("type", text(value.type().value()));
        result.put("source", text(value.source().value()));
        result.put("contentType", text(value.contentType().value()));
        result.put("summary", nullableText(value.summary()));
        return new ObjectValue(result);
    }

    private static ObjectValue guidanceInsight(CompanyGuidanceAnalysis analysis) {
        var value = analysis.summary();
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("stance", text(value.stance().value()));
        result.put("actionBias", text(switch (value.stance()) {
            case RAISED -> "공격 가능";
            case AFFIRMED, MIXED -> "선별 접근";
            case LOWERED, UNCLEAR -> "보수 접근";
        }));
        var concrete = java.util.stream.Stream.of(
                        value.revenue(), value.margin(), value.capex(), value.freeCashFlow())
                .filter(java.util.Objects::nonNull)
                .anyMatch(CompanyGuidanceMetric::structured);
        result.put("summary", text(guidanceSummary(value.stance(), concrete)));
        putMetric(result, "revenue", "revenueValue", value.revenue());
        putMetric(result, "margin", "marginValue", value.margin());
        putMetric(result, "capex", "capexValue", value.capex());
        putMetric(result, "fcf", "fcfValue", value.freeCashFlow());
        result.put("evidence", texts(value.evidence().stream().limit(4).toList()));
        result.put("filingDate", date(analysis.filingDate()));
        result.put("sourceUrl", text(analysis.url()));
        return new ObjectValue(result);
    }

    private static void putMetric(
            LinkedHashMap<String, StructuredValue> target,
            String textField,
            String valueField,
            CompanyGuidanceMetric metric
    ) {
        target.put(textField, metric == null ? NullValue.INSTANCE : text(metric.text()));
        target.put(valueField, metric == null || metric.value() == null
                ? NullValue.INSTANCE : guidanceMetricValue(metric.value()));
    }

    private static ObjectValue guidanceMetricValue(CompanyGuidanceMetricValue value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("raw", text(value.raw()));
        result.put("min", value.min() == null ? NullValue.INSTANCE : new NumberValue(value.min()));
        result.put("max", value.max() == null ? NullValue.INSTANCE : new NumberValue(value.max()));
        result.put("unit", text(value.unit().value()));
        return new ObjectValue(result);
    }

    private static String guidanceSummary(CompanyGuidanceSummary.Stance stance, boolean concrete) {
        return switch (stance) {
            case RAISED -> concrete
                    ? "최근 가이던스가 상향됐고 숫자 레벨도 실적 기대를 뒷받침합니다."
                    : "최근 가이던스가 상향되며 실적 기대를 지지합니다.";
            case AFFIRMED -> concrete
                    ? "가이던스는 유지됐습니다. 숫자는 견조하지만 추가 상향 여지는 더 확인해야 합니다."
                    : "가이던스는 유지됐습니다. 추가 서프라이즈는 더 확인해야 합니다.";
            case LOWERED -> concrete
                    ? "최근 가이던스가 하향됐고 숫자 레벨도 낮아져 신규 진입은 더 보수적으로 봐야 합니다."
                    : "최근 가이던스가 하향돼 신규 진입은 보수적으로 봐야 합니다.";
            case MIXED -> "가이던스가 엇갈립니다. 일부 지표는 좋지만 바로 확신하기는 이릅니다.";
            case UNCLEAR -> "가이던스 방향이 불명확합니다. 후속 실적과 수급 확인이 중요합니다.";
        };
    }

    private static Research patchBottomGuidance(Research source, CompanyGuidanceSummary guidance) {
        if (!(source.bottomSignal() instanceof ObjectValue bottom)) return source;
        var score = switch (guidance.stance()) {
            case RAISED -> 82;
            case AFFIRMED -> 66;
            case MIXED -> 48;
            case LOWERED -> 28;
            case UNCLEAR -> 52;
        };
        var fields = fields(bottom);
        if (fields.get("metrics") instanceof ArrayValue metrics) {
            var updated = metrics.values().stream().map(item -> {
                if (!(item instanceof ObjectValue metric)
                        || !(metric.fields().get("key") instanceof TextValue key)
                        || !"guidance".equals(key.value())) return item;
                var metricFields = fields(metric);
                metricFields.put("score", number(score));
                metricFields.put("status", text(score >= 68 ? "positive" : score >= 45 ? "neutral" : "negative"));
                metricFields.put("detail", text(guidanceSummary(guidance.stance(), guidance.structuredMetricCount() > 0)));
                return (StructuredValue) new ObjectValue(metricFields);
            }).toList();
            fields.put("metrics", new ArrayValue(updated));
        }
        return new Research(
                source.profile(), source.quote(), source.financials(), source.score(), source.buyScore(),
                source.filings(), source.irMaterials(), source.highlights(), source.peerGroup(), source.bottleneck(),
                source.narrative(), source.capitalFlow(), source.cashFlowQuality(), source.multipleInsight(),
                source.guidanceInsight(), source.timeframeView(), source.correctionAssessment(), source.thesisMonitor(),
                source.reversalConfirmation(), source.sectorContext(), source.verdicts(), new ObjectValue(fields),
                source.positionSizing(), source.executionBridge(), source.peers()
        );
    }

    private static Research copy(
            Research source,
            ObjectValue quote,
            ObjectValue financials,
            ObjectValue score,
            ObjectValue buyScore,
            StructuredValue reversal,
            StructuredValue bottom,
            StructuredValue positionSizing,
            StructuredValue executionBridge,
            StructuredValue verdicts
    ) {
        return new Research(
                source.profile(), quote, financials, score, buyScore,
                source.filings(), source.irMaterials(), source.highlights(), source.peerGroup(),
                source.bottleneck(), source.narrative(), source.capitalFlow(), source.cashFlowQuality(),
                source.multipleInsight(), source.guidanceInsight(), source.timeframeView(),
                source.correctionAssessment(), source.thesisMonitor(), reversal, source.sectorContext(),
                verdicts, bottom, positionSizing, executionBridge, source.peers()
        );
    }

    private static Research withTimeframe(Research source, StructuredValue timeframe) {
        return new Research(
                source.profile(), source.quote(), source.financials(), source.score(), source.buyScore(),
                source.filings(), source.irMaterials(), source.highlights(), source.peerGroup(),
                source.bottleneck(), source.narrative(), source.capitalFlow(), source.cashFlowQuality(),
                source.multipleInsight(), source.guidanceInsight(), timeframe,
                source.correctionAssessment(), source.thesisMonitor(), source.reversalConfirmation(),
                source.sectorContext(), source.verdicts(), source.bottomSignal(), source.positionSizing(),
                source.executionBridge(), source.peers()
        );
    }

    private static ObjectValue timeframe(
            Research source,
            CompanyPriceSignalParityReport report,
            CompanyHorizonSignalPolicy policy
    ) {
        var score = source.score();
        var buy = source.buyScore();
        var direct = report.spring();
        var technical = direct.technicalConfirmation();
        var view = policy.evaluate(new CompanyHorizonEvidence(
                integer(score, "totalScore"),
                nestedInteger(score, "quality", "value"),
                nestedInteger(score, "growth", "value"),
                nestedInteger(score, "valuation", "value"),
                nestedInteger(score, "balanceSheet", "value"),
                integer(buy, "buyScore"),
                direct.confirmedBottom().score(),
                direct.reversalConfirmation().score(),
                technical == null || technical.state()
                        == io.macrosquare.company.domain.bottom.VolumePriceConfirmationState.UNAVAILABLE
                        ? null : technical.score()
        ));
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("shortTerm", horizonSignal(view.shortTerm()));
        result.put("swingTerm", horizonSignal(view.swingTerm()));
        result.put("longTerm", horizonSignal(view.longTerm()));
        if (direct.walkForwardValidation() != null) {
            result.put("validation", walkForward(direct.walkForwardValidation()));
        }
        return new ObjectValue(result);
    }

    private static ObjectValue horizonSignal(CompanyHorizonSignal value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("stance", text(horizonAction(value.action())));
        result.put("score", number(value.score()));
        result.put("confidence", number(value.confidence()));
        result.put("summary", text(value.summary()));
        result.put("reasons", texts(value.reasons()));
        result.put("weights", horizonWeights(value.weights()));
        return new ObjectValue(result);
    }

    private static ObjectValue horizonWeights(CompanyHorizonWeights value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("company", number(value.company()));
        result.put("quality", number(value.quality()));
        result.put("growth", number(value.growth()));
        result.put("valuation", number(value.valuation()));
        result.put("balanceSheet", number(value.balanceSheet()));
        result.put("buy", number(value.buy()));
        result.put("bottom", number(value.bottom()));
        result.put("reversal", number(value.reversal()));
        result.put("technical", number(value.technical()));
        return new ObjectValue(result);
    }

    private static ObjectValue walkForward(CompanyWalkForwardValidation value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("firstDate", date(value.firstDate()));
        result.put("lastDate", date(value.lastDate()));
        result.put("historyPointCount", number(value.historyPointCount()));
        result.put("methodology", text(value.methodology()));
        result.put("horizons", new ArrayValue(value.horizons().stream()
                .map(CompanyResearchProjectionComposer::walkForwardMetric)
                .map(StructuredValue.class::cast)
                .toList()));
        return new ObjectValue(result);
    }

    private static ObjectValue walkForwardMetric(HorizonWalkForwardMetric value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("horizon", text(value.horizon().name()));
        result.put("forwardTradingDays", number(value.forwardTradingDays()));
        result.put("targetReturnPct", number(value.targetReturnPct()));
        result.put("signalThreshold", number(value.signalThreshold()));
        result.put("signalCount", number(value.signalCount()));
        result.put("positiveHitRatePct", number(value.positiveHitRatePct()));
        result.put("targetHitRatePct", number(value.targetHitRatePct()));
        result.put("averageReturnPct", number(value.averageReturnPct()));
        result.put("medianReturnPct", number(value.medianReturnPct()));
        result.put("averageDaysToTarget", number(value.averageDaysToTarget()));
        result.put("averageMaxDrawdownPct", number(value.averageMaxDrawdownPct()));
        return new ObjectValue(result);
    }

    private static String horizonAction(CompanyHorizonAction value) {
        return switch (value) {
            case STRONG_BUY -> "STRONG BUY";
            case BUY -> "BUY";
            case HOLD -> "HOLD";
            case REDUCE -> "REDUCE";
            case SELL -> "SELL";
        };
    }

    private static ObjectValue quote(CompanyResearchParityReport report) {
        var value = report.springQuote();
        var fields = new LinkedHashMap<String, StructuredValue>();
        fields.put("symbol", text(value.symbol()));
        fields.put("price", number(value.price()));
        fields.put("date", date(value.date()));
        return new ObjectValue(fields);
    }

    private static void putFundamentals(LinkedHashMap<String, StructuredValue> target,
                                        CompanyFundamentalsSnapshot value) {
        target.put("ticker", text(value.ticker().value()));
        target.put("cik", text(value.cik()));
        target.put("asOf", text(value.asOf()));
        target.put("revenueTtm", number(value.revenueTtm()));
        target.put("operatingIncomeTtm", number(value.operatingIncomeTtm()));
        target.put("netIncomeTtm", number(value.netIncomeTtm()));
        target.put("freeCashFlowTtm", number(value.freeCashFlowTtm()));
        target.put("cash", number(value.cash()));
        target.put("debt", number(value.debt()));
        target.put("currentAssets", number(value.currentAssets()));
        target.put("currentLiabilities", number(value.currentLiabilities()));
        target.put("receivables", number(value.receivables()));
        target.put("inventory", number(value.inventory()));
        target.put("capexTtm", number(value.capexTtm()));
        target.put("operatingCashFlowTtm", number(value.operatingCashFlowTtm()));
        target.put("sharesOutstanding", number(value.sharesOutstanding()));
        target.put("marketCap", number(value.marketCap()));
        target.put("enterpriseValue", number(value.enterpriseValue()));
        target.put("revenueGrowthYoY", number(value.revenueGrowthYoY()));
        target.put("operatingMargin", number(value.operatingMargin()));
        target.put("operatingMarginTrend", number(value.operatingMarginTrend()));
        target.put("freeCashFlowMargin", number(value.freeCashFlowMargin()));
        target.put("netDebtToRevenue", number(value.netDebtToRevenue()));
        target.put("evToSales", number(value.evToSales()));
        target.put("evToFcf", number(value.evToFcf()));
        target.put("shareDilutionYoY", number(value.shareDilutionYoY()));
        target.put("stockCompToRevenue", number(value.stockCompToRevenue()));
        target.put("roe", number(value.roe()));
        target.put("currentRatio", number(value.currentRatio()));
        target.put("receivablesToRevenue", number(value.receivablesToRevenue()));
        target.put("inventoryToRevenue", number(value.inventoryToRevenue()));
        target.put("roic", number(value.roic()));
        target.put("effectiveTaxRate", number(value.effectiveTaxRate()));
        target.put("roicEstimated", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.roicEstimated()));
        target.put("shareDilution3yCagr", number(value.shareDilution3yCagr()));
        target.put("accrualRatio", number(value.accrualRatio()));
        var valuation = value.valuationQuality();
        target.put("valuationBasis", text(valuation.basis().name()));
        target.put("valuationEligible", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                valuation.valuationEligible()));
        target.put("marketCapAsOf", date(valuation.marketCapAsOf()));
        target.put("secSharesAsOf", date(valuation.secSharesAsOf()));
        target.put("rawSecShares", number(valuation.rawSecShares()));
        target.put("resolvedShares", number(valuation.resolvedShares()));
        target.put("sharesDivergencePct", number(valuation.sharesDivergencePct()));
        target.put("detectedSplitFactor", number(valuation.detectedSplitFactor()));
        target.put("valuationWarnings", texts(valuation.warnings()));
    }

    private static void putFreshness(
            LinkedHashMap<String, StructuredValue> target,
            CompanyResearchParityReport report
    ) {
        var value = report.fundamentalsFreshness();
        target.put("fundamentalsStatus", text(value.status().name()));
        target.put("latestPeriodicReportDate", date(value.latestPeriodicReportDate()));
        target.put("latestPeriodicFilingDate", date(value.latestPeriodicFilingDate()));
        target.put("latestPeriodicForm", nullableText(value.latestPeriodicForm()));
        target.put("fundamentalsLagDays", number(value.lagDays()));
        target.put("scoreComparable", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                report.scoreComparable()));
        target.put("scoreWarnings", texts(report.scoreWarnings()));
    }

    private static ObjectValue score(CompanyScore value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("ticker", text(value.ticker().value()));
        result.put("totalScore", number(value.totalScore()));
        result.put("growth", breakdown(value.growth()));
        result.put("quality", breakdown(value.quality()));
        result.put("valuation", breakdown(value.valuation()));
        result.put("balanceSheet", breakdown(value.balanceSheet()));
        result.put("reasons", texts(value.reasons()));
        return new ObjectValue(result);
    }

    private static ObjectValue withheldScore(CompanyScore value, List<String> warnings) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("ticker", text(value.ticker().value()));
        result.put("totalScore", NullValue.INSTANCE);
        result.put("growth", withheldBreakdown(value.growth(), "성장"));
        result.put("quality", withheldBreakdown(value.quality(), "수익성"));
        result.put("valuation", withheldBreakdown(value.valuation(), "밸류"));
        result.put("balanceSheet", withheldBreakdown(value.balanceSheet(), "재무"));
        result.put("reasons", texts(warnings));
        return new ObjectValue(result);
    }

    private static ObjectValue withheldBreakdown(ScoreBreakdown value, String axis) {
        var reasons = value.reasons().isEmpty()
                ? List.of(axis + " 점수 근거 부족")
                : value.reasons();
        return new ObjectValue(Map.of(
                "value", NullValue.INSTANCE,
                "reasons", texts(reasons)
        ));
    }

    private static ObjectValue breakdown(ScoreBreakdown value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("value", number(value.value()));
        result.put("reasons", texts(value.reasons()));
        return new ObjectValue(result);
    }

    private static ObjectValue buyScore(CompanyBuyScore value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("appealScore", number(value.appealScore()));
        result.put("crowdingScore", number(value.crowdingScore()));
        result.put("buyScore", number(value.buyScore()));
        result.put("label", text(switch (value.label()) {
            case FAVORABLE -> "매수 우호";
            case SELECTIVE -> "선별 접근";
            case CHASE_RISK -> "추격 주의";
        }));
        result.put("reasons", texts(value.reasons()));
        return new ObjectValue(result);
    }

    private static ObjectValue withheldBuyScore(List<String> warnings) {
        return new ObjectValue(Map.of(
                "appealScore", NullValue.INSTANCE,
                "crowdingScore", NullValue.INSTANCE,
                "buyScore", NullValue.INSTANCE,
                "label", NullValue.INSTANCE,
                "reasons", texts(warnings)
        ));
    }

    private static ArrayValue patchBottomMetrics(
            StructuredValue value,
            BottomPriceSignal signal,
            BottomPriceContext context
    ) {
        var source = value instanceof ArrayValue array ? array.values() : List.<StructuredValue>of();
        var result = new ArrayList<StructuredValue>(Math.max(source.size(), 4));
        var patchedKeys = new java.util.HashSet<String>();
        for (var item : source) {
            if (!(item instanceof ObjectValue metric) || !(metric.fields().get("key") instanceof TextValue key)) {
                result.add(item);
                continue;
            }
            Integer score = switch (key.value()) {
                case "price" -> signal.priceResetScore();
                case "pattern" -> signal.patternScore();
                case "absorption" -> signal.absorptionScore();
                case "volume" -> signal.volumeConfirmationScore();
                default -> null;
            };
            if (score == null) result.add(item);
            else {
                patchedKeys.add(key.value());
                var updated = fields(metric);
                updated.put("score", number(score));
                updated.put("status", text(score >= 68 ? "positive" : score >= 45 ? "neutral" : "negative"));
                updated.put("detail", text(bottomMetricDetail(key.value(), context)));
                result.add(new ObjectValue(updated));
            }
        }
        for (var key : List.of("price", "pattern", "volume", "absorption")) {
            if (patchedKeys.contains(key)) continue;
            var score = switch (key) {
                case "price" -> signal.priceResetScore();
                case "pattern" -> signal.patternScore();
                case "volume" -> signal.volumeConfirmationScore();
                case "absorption" -> signal.absorptionScore();
                default -> throw new IllegalStateException("Unexpected price metric " + key);
            };
            result.add(metric(key, bottomMetricLabel(key), score, bottomMetricDetail(key, context)));
        }
        return new ArrayValue(result);
    }

    private static String bottomMetricLabel(String key) {
        return switch (key) {
            case "price" -> "가격 리셋";
            case "pattern" -> "바닥 패턴";
            case "volume" -> "거래량 동반";
            case "absorption" -> "하락 흡수";
            default -> key;
        };
    }

    private static String bottomMetricDetail(String key, BottomPriceContext context) {
        return switch (key) {
            case "price" -> "고점대비 %s · 저점대비 %s · 거래량 추세 %s".formatted(
                    percent(context.drawdownFromHighPct()),
                    percent(context.reboundFromLowPct()),
                    percent(context.volumeTrend20dPct()));
            case "pattern" -> switch (context.pattern().phase()) {
                case CONFIRM -> "저점 후보 이후 12% 이상 반등해 1차 가격 확인이 나왔습니다.";
                case RETEST -> "첫 반등 뒤 저점 재시험 구간입니다.";
                case CANDIDATE -> "저점 후보에서 반등했지만 가격 확인선은 아직 넘지 못했습니다.";
                case DECLINE -> "아직 하락 정지보다 낙하 구간에 가깝습니다.";
            };
            case "volume" -> "저점후보 거래량 %s · 확인 거래량 %s".formatted(
                    ratio(context.candidateVolumeRatio()), ratio(context.confirmVolumeRatio()));
            case "absorption" -> "최근 2~3봉 대비 %s · 현재 하락 %s · 이전 하락 %s".formatted(
                    ratio(context.absorptionVolumeVsRecent3dRatio()),
                    percent(context.absorptionDropPct()),
                    percent(context.priorDeclineDropPct()));
            default -> "직접 수집한 현재 가격·거래량 기준으로 재계산했습니다.";
        };
    }

    private static String percent(Double value) {
        return value == null ? "—" : signed1(value) + "%";
    }

    private static String ratio(Double value) {
        return value == null ? "—" : format1(value) + "배";
    }

    private static ObjectValue chart(CompanyPriceSignalParityReport report) {
        var technicalByDate = report.spring().technicalConfirmation() == null
                ? Map.<LocalDate, io.macrosquare.company.domain.bottom.VolumePricePoint>of()
                : report.spring().technicalConfirmation().points().stream()
                .collect(Collectors.toMap(
                        io.macrosquare.company.domain.bottom.VolumePricePoint::date,
                        value -> value,
                        (left, right) -> right
                ));
        var structureByDate = report.spring().priceStructure() == null
                ? Map.<LocalDate, PriceStructureAnalysis.PriceStructurePoint>of()
                : report.spring().priceStructure().points().stream()
                .collect(Collectors.toMap(
                        PriceStructureAnalysis.PriceStructurePoint::date,
                        value -> value,
                        (left, right) -> right
                ));
        var points = report.springContext().chartPoints().stream().map(point -> {
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("date", text(point.date().toString()));
            fields.put("value", number(point.close()));
            var technical = technicalByDate.get(point.date());
            fields.put("vwap20", number(technical == null ? null : technical.vwap20()));
            fields.put("obvPressure20Pct", number(technical == null ? null : technical.obvPressure20Pct()));
            var structure = structureByDate.get(point.date());
            fields.put("sma20", number(structure == null ? null : structure.sma20()));
            fields.put("sma50", number(structure == null ? null : structure.sma50()));
            fields.put("sma100", number(structure == null ? null : structure.sma100()));
            fields.put("sma200", number(structure == null ? null : structure.sma200()));
            fields.put("channelLower", number(structure == null ? null : structure.channelLower()));
            fields.put("channelMid", number(structure == null ? null : structure.channelMid()));
            fields.put("channelUpper", number(structure == null ? null : structure.channelUpper()));
            return (StructuredValue) new ObjectValue(fields);
        }).toList();
        var markers = report.spring().markers().stream().map(marker -> {
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("kind", text(marker.kind()));
            fields.put("date", text(marker.date().toString()));
            fields.put("value", number(marker.value()));
            fields.put("label", text(markerLabel(marker.kind())));
            return (StructuredValue) new ObjectValue(fields);
        }).toList();
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("points", new ArrayValue(points));
        result.put("markers", new ArrayValue(markers));
        return new ObjectValue(result);
    }

    private static ObjectValue priceStructure(PriceStructureAnalysis value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("score", number(value.score()));
        result.put("trendState", text(value.trendState().name()));
        result.put("trendLabel", text(switch (value.trendState()) {
            case UPTREND -> "상승 구조";
            case RANGE -> "횡보 구조";
            case DOWNTREND -> "하락 구조";
            case TRANSITION -> "전환 구간";
            case UNAVAILABLE -> "데이터 부족";
        }));
        result.put("bearishReversalStage", text(value.bearishReversalStage().name()));
        result.put("bearishReversalLabel", text(switch (value.bearishReversalStage()) {
            case INTACT -> "훼손 없음";
            case MOMENTUM_WEAKENING -> "1단계 모멘텀 약화";
            case STRUCTURAL_CRACK -> "2단계 구조 균열";
            case PRIOR_LOW_BROKEN -> "3단계 이전 저점 이탈";
            case UNAVAILABLE -> "데이터 부족";
        }));
        result.put("recoveryStage", text(value.recoveryStage().name()));
        result.put("recoveryLabel", text(switch (value.recoveryStage()) {
            case NONE -> "회복 미확인";
            case BASE_BUILDING -> "바닥 다지기";
            case REBOUND -> "반등 진행";
            case STRUCTURE_BREAK -> "직전 고점 돌파";
            case RETEST_HELD -> "돌파 후 지지 확인";
            case UNAVAILABLE -> "데이터 부족";
        }));
        result.put("priceLocation", text(value.priceLocation().name()));
        result.put("priceLocationLabel", text(switch (value.priceLocation()) {
            case BREAKOUT -> "거래량 돌파";
            case LOWER_CHANNEL -> "채널 하단";
            case SUPPORT_ZONE -> "지지 구간";
            case MID_CHANNEL -> "채널 중단";
            case RESISTANCE_ZONE -> "저항 구간";
            case UPPER_CHANNEL -> "채널 상단";
            case BREAKDOWN -> "지지 이탈";
            case UNAVAILABLE -> "데이터 부족";
        }));
        result.put("movingAverageState", text(value.movingAverageState().name()));
        result.put("movingAverageLabel", text(switch (value.movingAverageState()) {
            case BULLISH_ALIGNED -> "상승 정배열";
            case CONVERGED -> "이평선 수렴";
            case TRANSITION -> "이평선 혼조";
            case BEARISH_ALIGNED -> "하락 역배열";
            case UNAVAILABLE -> "데이터 부족";
        }));
        result.put("rsi14", number(value.rsi14()));
        result.put("sma20", number(value.sma20()));
        result.put("sma50", number(value.sma50()));
        result.put("sma100", number(value.sma100()));
        result.put("sma200", number(value.sma200()));
        result.put("movingAverageConvergencePct", number(value.movingAverageConvergencePct()));
        result.put("channelLower", number(value.channelLower()));
        result.put("channelMid", number(value.channelMid()));
        result.put("channelUpper", number(value.channelUpper()));
        result.put("channelPositionPct", number(value.channelPositionPct()));
        result.put("channelAnnualizedSlopePct", number(value.channelAnnualizedSlopePct()));
        result.put("supportZone", priceZone(value.supportZone()));
        result.put("resistanceZone", priceZone(value.resistanceZone()));
        result.put("consolidationDays", number(value.consolidationDays()));
        result.put("consolidationRangePct", number(value.consolidationRangePct()));
        result.put("volumeBreakout", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.volumeBreakout()));
        result.put("stopHuntReclaim", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.stopHuntReclaim()));
        result.put("oversoldConfluence", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.oversoldConfluence()));
        result.put("fibonacci", fibonacci(value.fibonacci()));
        result.put("reasons", texts(value.reasons()));
        result.put("cautions", texts(value.cautions()));
        result.put("methodology", text(value.methodology()));
        return new ObjectValue(result);
    }

    private static StructuredValue fibonacci(FibonacciRetracementAnalysis value) {
        if (value == null) return NullValue.INSTANCE;
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("swingDirection", text(value.swingDirection().name()));
        result.put("swingDirectionLabel", text(switch (value.swingDirection()) {
            case UP_SWING -> "주요 저점→고점";
            case DOWN_SWING -> "주요 고점→저점";
            case UNAVAILABLE -> "파동 식별 불가";
        }));
        result.put("swingStartDate", date(value.swingStartDate()));
        result.put("swingEndDate", date(value.swingEndDate()));
        result.put("swingStartPrice", number(value.swingStartPrice()));
        result.put("swingEndPrice", number(value.swingEndPrice()));
        result.put("swingAmplitudePct", number(value.swingAmplitudePct()));
        result.put("currentPrice", number(value.currentPrice()));
        result.put("currentRetracementRatio", number(value.currentRetracementRatio()));
        result.put("levels", new ArrayValue(value.levels().stream().map(level -> {
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("ratio", number(level.ratio()));
            fields.put("price", number(level.price()));
            fields.put("label", text(level.label()));
            return (StructuredValue) new ObjectValue(fields);
        }).toList()));
        result.put("nearestRatio", number(value.nearestRatio()));
        result.put("nearestPrice", number(value.nearestPrice()));
        result.put("nearestGapPct", number(value.nearestGapPct()));
        result.put("timeframeReliability", text(value.timeframeReliability().name()));
        result.put("timeframeLabel", text(switch (value.timeframeReliability()) {
            case WEEKLY_CONFIRMED -> "주봉 별도 집계 합치";
            case DAILY_ONLY -> "일봉 기준";
            case UNAVAILABLE -> "데이터 부족";
        }));
        result.put("weeklyConfluence", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.weeklyConfluence()));
        result.put("supportResistanceConfluence", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.supportResistanceConfluence()));
        result.put("channelConfluence", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.channelConfluence()));
        result.put("confluenceScore", number(value.confluenceScore()));
        result.put("zoneState", text(value.zoneState().name()));
        result.put("zoneLabel", text(switch (value.zoneState()) {
            case EXTENSION -> "원 파동 확장";
            case SHALLOW_RETRACEMENT -> "얕은 되돌림";
            case MODERATE_RETRACEMENT -> "중간 되돌림";
            case DEEP_RETRACEMENT -> "깊은 되돌림";
            case LAST_DEFENSE -> "0.786 추세 훼손 경계";
            case LAST_DEFENSE_BROKEN -> "0.786 기준 이탈";
            case UNAVAILABLE -> "데이터 부족";
        }));
        result.put("summary", text(value.summary()));
        result.put("cautions", texts(value.cautions()));
        result.put("methodology", text(value.methodology()));
        return new ObjectValue(result);
    }

    private static StructuredValue priceZone(PriceStructureAnalysis.PriceZone value) {
        if (value == null) return NullValue.INSTANCE;
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("lower", number(value.lower()));
        result.put("upper", number(value.upper()));
        result.put("touches", number(value.touches()));
        result.put("strength", number(value.strength()));
        result.put("roleFlip", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.roleFlip()));
        return new ObjectValue(result);
    }

    private static ObjectValue technicalConfirmation(VolumePriceAnalysis value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("score", number(value.score()));
        result.put("state", text(switch (value.state()) {
            case ACCUMULATION -> "매집 우위";
            case NEUTRAL -> "중립";
            case DISTRIBUTION -> "분산 우위";
            case UNAVAILABLE -> "데이터 부족";
        }));
        result.put("vwap20", number(value.vwap20()));
        result.put("closeVsVwap20Pct", number(value.closeVsVwap20Pct()));
        result.put("vwapSlope5dPct", number(value.vwapSlope5dPct()));
        result.put("obvPressure20Pct", number(value.obvPressure20Pct()));
        result.put("reasons", texts(value.reasons()));
        result.put("cautions", texts(value.cautions()));
        result.put("methodology", text("일봉 고가·저가·종가와 거래량으로 계산한 20일 VWAP proxy 및 OBV 압력입니다. 기존 찐바닥 임계값과 독립된 보조 확인값입니다."));
        return new ObjectValue(result);
    }

    private static ObjectValue macdMomentum(io.macrosquare.technical.domain.MacdMultiTimeframeAnalysis value) {
        return new ObjectValue(Map.of(
                "daily", macdSignal(value.daily()),
                "weekly", macdSignal(value.weekly()),
                "currentWeekProvisional", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                        value.currentWeekProvisional())
        ));
    }

    private static ObjectValue macdSignal(io.macrosquare.technical.domain.MacdSignalAnalysis value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("asOf", date(value.asOf()));
        result.put("macd", number(value.macd()));
        result.put("signal", number(value.signal()));
        result.put("histogram", number(value.histogram()));
        result.put("position", text(value.position().name()));
        result.put("zeroRegime", text(value.zeroRegime().name()));
        result.put("latestCross", text(value.latestCross().name()));
        result.put("crossDate", date(value.crossDate()));
        result.put("sessionsSinceCross", value.sessionsSinceCross() == null
                ? NullValue.INSTANCE : number(value.sessionsSinceCross()));
        result.put("histogramState", text(value.histogramState().name()));
        result.put("divergence", text(value.divergence().name()));
        result.put("divergenceStartDate", date(value.divergenceStartDate()));
        result.put("divergenceEndDate", date(value.divergenceEndDate()));
        result.put("divergenceConfirmedDate", date(value.divergenceConfirmedDate()));
        result.put("sessionsSinceDivergence", value.sessionsSinceDivergence() == null
                ? NullValue.INSTANCE : number(value.sessionsSinceDivergence()));
        result.put("divergenceActive", new io.macrosquare.company.application.model.CompanyReadModels.BooleanValue(
                value.divergenceActive()));
        result.put("sourcePointCount", number(value.sourcePointCount()));
        result.put("methodology", text(value.methodology()));
        return new ObjectValue(result);
    }

    private static ObjectValue confirmedBottom(DeepBottomSignal value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("score", number(value.score()));
        result.put("state", text(switch (value.state()) {
            case UNMET -> "미충족";
            case CANDIDATE -> "후보";
            case CONVICTION -> "확신";
        }));
        result.put("actionBias", text(actionBiasLabel(value.actionBias())));
        result.put("signalDate", date(value.signalDate()));
        result.put("daysSinceSignal", value.daysSinceSignal() == null
                ? NullValue.INSTANCE : number(value.daysSinceSignal()));
        result.put("summary", text(value.summary()));
        result.put("recentVolumeRatio", number(value.recentVolumeRatio()));
        result.put("contractionRatio", number(value.contractionRatio()));
        result.put("drawdown120dPct", number(value.drawdown120dPct()));
        result.put("ma20GapPct", number(value.ma20GapPct()));
        result.put("recentDrop3dPct", number(value.recentDrop3dPct()));
        result.put("reasons", texts(value.reasons()));
        result.put("cautions", texts(value.cautions()));
        return new ObjectValue(result);
    }

    private static ObjectValue reversal(ReversalConfirmation value) {
        var result = new LinkedHashMap<String, StructuredValue>();
        result.put("status", text(value.status().name()));
        result.put("score", number(value.score()));
        result.put("signalDate", date(value.signalDate()));
        result.put("summary", text(value.summary()));
        result.put("reasons", texts(value.reasons()));
        result.put("cautions", texts(value.cautions()));
        return new ObjectValue(result);
    }

    private static StructuredValue patchPositionSizing(StructuredValue value, String action) {
        if (!(value instanceof ObjectValue object)) return value;
        var result = fields(object);
        var initial = switch (action) {
            case "STRONG BUY" -> 40;
            case "BUY" -> 30;
            case "HOLD" -> 20;
            case "REDUCE" -> 10;
            default -> 0;
        };
        result.put("action", text(action));
        result.put("initialEntryPctOfTarget", number(initial));
        result.put("reservePctOfTarget", number(100 - initial));
        return new ObjectValue(result);
    }

    private static StructuredValue patchPositionSizingForReversal(StructuredValue value,
                                                                    ReversalConfirmation reversal) {
        if (!(value instanceof ObjectValue object)) return value;
        var result = fields(object);
        if (result.get("reasons") instanceof ArrayValue array) {
            var reasons = array.values().stream().filter(item -> !(item instanceof TextValue text)
                    || !text.value().startsWith("반전확인 "))
                    .collect(Collectors.toCollection(ArrayList::new));
            reasons.add(text("반전확인 " + reversal.status().name() + " " + reversal.score() + "/100"));
            result.put("reasons", new ArrayValue(reasons));
        }
        return new ObjectValue(result);
    }

    private static StructuredValue patchExecutionBridge(StructuredValue value, String companyAction) {
        if (!(value instanceof ObjectValue object)) return value;
        var result = fields(object);
        result.put("companyAction", text(companyAction));
        result.put("companyActionLabel", text(actionLabel(companyAction)));
        if (result.get("action") instanceof TextValue assetAction) {
            var delta = Math.abs(actionRank(mapPlanAction(assetAction.value())) - actionRank(companyAction));
            result.put("alignment", text(delta == 0 ? "aligned" : delta == 1 ? "mixed" : "conflicted"));
        }
        return new ObjectValue(result);
    }

    private static StructuredValue patchVerdicts(StructuredValue value, CompanyScore score,
                                                   CompanyFundamentalsSnapshot financials,
                                                   CompanyBuyScore buy) {
        if (!(value instanceof ObjectValue object)) return value;
        var result = fields(object);
        var business = Math.round(score.totalScore() * .4 + score.quality().value() * .35
                + score.balanceSheet().value() * .25);
        var valuation = financials.evToSales() == null ? 58
                : Math.round(88 - Math.max(0, Math.min(20, financials.evToSales())) * 3);
        var timing = Math.round((100 - buy.crowdingScore()) * .55 + buy.buyScore() * .45);
        patchVerdict(result, "businessQuality", business);
        patchVerdict(result, "valuation", valuation);
        patchVerdict(result, "timing", timing);
        patchVerdict(result, "finalAction", buy.buyScore());
        return new ObjectValue(result);
    }

    private static StructuredValue withheldVerdicts(StructuredValue value, List<String> warnings) {
        if (!(value instanceof ObjectValue object)) return value;
        var result = fields(object);
        for (var key : List.of("businessQuality", "valuation", "timing", "finalAction")) {
            if (!(result.get(key) instanceof ObjectValue verdict)) continue;
            var fields = fields(verdict);
            fields.put("score", NullValue.INSTANCE);
            fields.put("label", text("데이터 검수 대기"));
            fields.put("summary", text(warnings.isEmpty() ? "점수 산출 근거를 확인 중입니다." : warnings.getFirst()));
            result.put(key, new ObjectValue(fields));
        }
        result.remove("investmentDecision");
        return new ObjectValue(result);
    }

    private static void patchVerdict(LinkedHashMap<String, StructuredValue> parent, String key, long score) {
        if (!(parent.get(key) instanceof ObjectValue object)) return;
        var result = fields(object);
        result.put("score", new NumberValue(score));
        result.put("label", text(score >= 72 ? "우호" : score >= 60 ? "양호" : score >= 45 ? "중립" : "주의"));
        parent.put(key, new ObjectValue(result));
    }

    private static String action(int score) {
        return score >= 80 ? "STRONG BUY" : score >= 70 ? "BUY" : score >= 55 ? "HOLD"
                : score >= 40 ? "REDUCE" : "SELL";
    }

    private static String actionLabel(String action) {
        return switch (action) {
            case "STRONG BUY" -> "적극 매수";
            case "BUY" -> "매수 가능";
            case "HOLD" -> "보유/관찰";
            case "REDUCE" -> "축소";
            default -> "매도/회피";
        };
    }

    private static String mapPlanAction(String action) {
        var normalized = action.toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("BUY")) return "BUY";
        if (normalized.contains("TAKE_PROFIT") || normalized.contains("REDUCE")) return "REDUCE";
        if (normalized.contains("SELL")) return "SELL";
        return "HOLD";
    }

    private static int actionRank(String action) {
        return switch (action) {
            case "STRONG BUY" -> 5;
            case "BUY" -> 4;
            case "HOLD" -> 3;
            case "REDUCE" -> 2;
            default -> 1;
        };
    }

    private static String structureLabel(BottomStructureState state) {
        return switch (state) {
            case NOT_BOTTOM -> "바닥 아님";
            case BOTTOM_ATTEMPT -> "바닥 시도";
            case RETEST -> "재시험 구간";
            case FIRST_CONFIRMATION -> "1차 확인";
            case STRUCTURAL_BOTTOM_POSSIBLE -> "구조적 바닥 가능";
        };
    }

    private static String actionBiasLabel(BottomActionBias value) {
        return switch (value) {
            case WAIT -> "대기";
            case OBSERVE_BUY -> "관찰 매수";
            case SCALE_IN_BUY -> "분할 매수";
        };
    }

    private static String markerLabel(String kind) {
        return switch (kind) {
            case "peak" -> "하락 시작 고점";
            case "candidate" -> "저점 후보";
            case "retest" -> "재시험 저점";
            case "confirm" -> "반전 확인";
            default -> "현재";
        };
    }

    private static ObjectValue object(StructuredValue value) {
        return value instanceof ObjectValue object ? object : new ObjectValue(Map.of());
    }

    private static LinkedHashMap<String, StructuredValue> fields(ObjectValue value) {
        return new LinkedHashMap<>(value.fields());
    }

    private static Integer integer(ObjectValue value, String field) {
        var item = value.fields().get(field);
        if (!(item instanceof NumberValue number)) return null;
        var converted = number.value().doubleValue();
        return Double.isFinite(converted) ? (int) Math.round(converted) : null;
    }

    private static Integer nestedInteger(ObjectValue value, String objectField, String numberField) {
        var nested = value.fields().get(objectField);
        return nested instanceof ObjectValue object ? integer(object, numberField) : null;
    }

    private static Double decimal(StructuredValue value) {
        if (!(value instanceof NumberValue number)) return null;
        var converted = number.value().doubleValue();
        return Double.isFinite(converted) ? converted : null;
    }

    private static String textValue(StructuredValue value) {
        return value instanceof TextValue text ? text.value() : "";
    }

    private static Double premiumPct(Double current, Double reference) {
        if (current == null || reference == null || reference <= 0) return null;
        return BigDecimal.valueOf((current / reference - 1) * 100)
                .setScale(1, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static int clampScore(double value) {
        return Math.max(0, Math.min(100, (int) Math.round(value)));
    }

    private static String format1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String signed1(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }

    private static String formatSignedPct(Double value) {
        return value == null ? "—" : signed1(value) + "%";
    }

    private static TextValue text(String value) {
        return new TextValue(value == null ? "" : value);
    }

    private static StructuredValue nullableText(String value) {
        return value == null ? NullValue.INSTANCE : text(value);
    }

    private static StructuredValue number(Double value) {
        return value == null || !Double.isFinite(value)
                ? NullValue.INSTANCE : new NumberValue(BigDecimal.valueOf(value));
    }

    private static StructuredValue number(Integer value) {
        return value == null ? NullValue.INSTANCE : number(value.intValue());
    }

    private static NumberValue number(int value) {
        return new NumberValue((long) value);
    }

    private static NumberValue number(double value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    private static StructuredValue date(LocalDate value) {
        return value == null ? NullValue.INSTANCE : text(value.toString());
    }

    private static ArrayValue texts(List<String> values) {
        return new ArrayValue(values.stream().map(CompanyResearchProjectionComposer::text)
                .map(StructuredValue.class::cast).toList());
    }
}
