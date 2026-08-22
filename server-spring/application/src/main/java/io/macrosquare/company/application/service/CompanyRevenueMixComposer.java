package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead;
import io.macrosquare.company.application.model.CompanyRevenueMixComposition;
import io.macrosquare.company.application.model.CompanyRevenueMixComposition.Source;
import io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis;
import io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure application-layer composition of direct SEC revenue mix into the
 * existing company-detail contract.
 */
public final class CompanyRevenueMixComposer {

    private static final String NOTE_FIELD = "segmentGeoMixNote";
    private static final String SEGMENT_FIELD = "segmentMix";
    private static final String GEOGRAPHY_FIELD = "geoMix";
    private static final BigDecimal MIN_FALLBACK_TOTAL = new BigDecimal("99.0");
    private static final BigDecimal MAX_FALLBACK_TOTAL = new BigDecimal("101.0");
    private static final Pattern ACCOUNTING_CONCEPT_LABEL = Pattern.compile(
            "(?i).*(?:deprecated|inventor(?:y|ies)|stockholders?[ -]?equity|current assets|"
                    + "current liabilities|accounts receivable|cash and cash equivalents|"
                    + "net income|operating income|costs? and expenses).*"
    );

    /** Applies the same fallback-quality gate before asynchronous SEC evidence arrives. */
    public Research sanitizeBaseline(Research baseline) {
        return compose(
                baseline,
                new CompanyRevenueMixAnalysis(null, null, 0, 0)
        ).enrichedDetail();
    }

    public CompanyRevenueMixComposition compose(Research baseline, CompanyRevenueMixAnalysis actual) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(actual, "actual");
        var baselineMix = CompanyRevenueMixLegacyProjection.from(baseline);
        var segment = resolve(actual.segment(), baselineMix.segment(), true);
        var geography = resolve(actual.geography(), baselineMix.geography(), false);
        var actualUsed = segment.source() == Source.DIRECT_SEC_ACTUAL
                || geography.source() == Source.DIRECT_SEC_ACTUAL;
        var baselineSanitized = !segment.entries().equals(baselineMix.segment())
                || !geography.entries().equals(baselineMix.geography());
        if (!actualUsed && !baselineSanitized) {
            return new CompanyRevenueMixComposition(
                    baseline,
                    baselineMix,
                    baselineMix,
                    segment.source(),
                    geography.source()
            );
        }
        var note = buildNote(segment, geography);
        var resolvedMix = new CompanyRevenueMixLegacyRead(note, segment.entries(), geography.entries());
        var enrichedFinancials = replaceMixFields(baseline.financials(), resolvedMix);
        var enrichedDetail = withFinancials(baseline, enrichedFinancials);
        return new CompanyRevenueMixComposition(
                enrichedDetail,
                baselineMix,
                resolvedMix,
                segment.source(),
                geography.source()
        );
    }

    private static ResolvedCategory resolve(
            CompanyRevenueMixBreakdown actual,
            List<CompanyRevenueMixLegacyRead.Entry> baseline,
            boolean segment
    ) {
        if (actual != null) {
            var entries = actual.entries().stream()
                    .map(entry -> new CompanyRevenueMixLegacyRead.Entry(
                            entry.label(), entry.value(), actual.unit(), entry.percentOfTotal()
                    ))
                    .toList();
            return new ResolvedCategory(Source.DIRECT_SEC_ACTUAL, entries, actual.periodEnd().toString());
        }
        if (validFallback(baseline, segment)) {
            return new ResolvedCategory(Source.BASELINE_FALLBACK, baseline, null);
        }
        if (!baseline.isEmpty()) {
            return new ResolvedCategory(Source.REJECTED_BASELINE, List.of(), null);
        }
        return new ResolvedCategory(Source.UNAVAILABLE, List.of(), null);
    }

    private static boolean validFallback(
            List<CompanyRevenueMixLegacyRead.Entry> entries,
            boolean segment
    ) {
        if (entries.isEmpty()) return false;
        var total = BigDecimal.ZERO;
        for (var entry : entries) {
            if (segment && invalidSegmentLabel(entry.label())) return false;
            var percent = entry.percentOfTotal();
            if (percent == null || percent.signum() <= 0
                    || percent.compareTo(BigDecimal.valueOf(100)) > 0) return false;
            total = total.add(percent);
        }
        return total.compareTo(MIN_FALLBACK_TOTAL) >= 0
                && total.compareTo(MAX_FALLBACK_TOTAL) <= 0;
    }

    private static boolean invalidSegmentLabel(String label) {
        if (label == null || label.isBlank()) return true;
        var normalized = label.trim().toLowerCase(Locale.ROOT);
        return ACCOUNTING_CONCEPT_LABEL.matcher(normalized).matches()
                || normalized.startsWith("revenue, net")
                || normalized.startsWith("sales revenue")
                || normalized.equals("revenue")
                || normalized.equals("revenues");
    }

    private static String buildNote(ResolvedCategory segment, ResolvedCategory geography) {
        var segmentNote = categoryNote("세그먼트", segment);
        var geographyNote = categoryNote("지역", geography);
        if (segmentNote == null) return geographyNote;
        if (geographyNote == null) return segmentNote;
        return segmentNote + " / " + geographyNote;
    }

    private static String categoryNote(String label, ResolvedCategory category) {
        if (category.source() == Source.UNAVAILABLE || category.source() == Source.REJECTED_BASELINE) return null;
        var source = category.source() == Source.DIRECT_SEC_ACTUAL
                ? "SEC Inline XBRL, " + category.periodEnd()
                : "legacy fallback";
        var values = category.entries().stream()
                .limit(3)
                .map(entry -> entry.label() + percentage(entry.percentOfTotal()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return label + "(" + source + "): " + values;
    }

    private static String percentage(BigDecimal value) {
        return value == null ? "" : " " + value.stripTrailingZeros().toPlainString() + "%";
    }

    private static ObjectValue replaceMixFields(ObjectValue financials, CompanyRevenueMixLegacyRead mix) {
        var fields = new LinkedHashMap<>(financials.fields());
        fields.put(NOTE_FIELD, nullableText(mix.note()));
        fields.put(SEGMENT_FIELD, entries(mix.segment()));
        fields.put(GEOGRAPHY_FIELD, entries(mix.geography()));
        return new ObjectValue(fields);
    }

    private static ArrayValue entries(List<CompanyRevenueMixLegacyRead.Entry> entries) {
        return new ArrayValue(entries.stream().map(entry -> {
            var fields = new LinkedHashMap<String, StructuredValue>();
            fields.put("label", new TextValue(entry.label()));
            fields.put("value", nullableNumber(entry.value()));
            fields.put("unit", nullableText(entry.unit()));
            fields.put("percentOfTotal", nullableNumber(entry.percentOfTotal()));
            return (StructuredValue) new ObjectValue(fields);
        }).toList());
    }

    private static StructuredValue nullableText(String value) {
        return value == null ? NullValue.INSTANCE : new TextValue(value);
    }

    private static StructuredValue nullableNumber(BigDecimal value) {
        return value == null ? NullValue.INSTANCE : new NumberValue(value);
    }

    private static Research withFinancials(Research source, ObjectValue financials) {
        return new Research(
                source.profile(), source.quote(), financials, source.score(), source.buyScore(),
                source.filings(), source.irMaterials(), source.highlights(), source.peerGroup(),
                source.bottleneck(), source.narrative(), source.capitalFlow(), source.cashFlowQuality(),
                source.multipleInsight(), source.guidanceInsight(), source.timeframeView(),
                source.correctionAssessment(), source.thesisMonitor(), source.reversalConfirmation(),
                source.sectorContext(), source.verdicts(), source.bottomSignal(), source.positionSizing(),
                source.executionBridge(), source.peers()
        );
    }

    private record ResolvedCategory(
            Source source,
            List<CompanyRevenueMixLegacyRead.Entry> entries,
            String periodEnd
    ) {
        private ResolvedCategory {
            source = Objects.requireNonNull(source, "source");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }
}
