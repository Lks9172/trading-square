package io.macrosquare.company.application.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral projections for the public company directory and summary reads.
 *
 * <p>These records deliberately mirror the information contract without importing
 * HTTP, Jackson, SEC collector, or retired transport types into the application layer.</p>
 */
public final class CompanyReadModels {

    private CompanyReadModels() {
    }

    public record SearchItem(String ticker, String cik, String title) {
        public SearchItem {
            ticker = requireText(ticker, "ticker");
            cik = requireText(cik, "cik");
            title = requireText(title, "title");
        }
    }

    public record SearchResult(List<SearchItem> items) {
        public SearchResult {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    public record Summary(
            String ticker,
            String name,
            Integer totalScore,
            Integer buyScore,
            String buyLabel,
            BigDecimal revenueGrowthYoY,
            BigDecimal operatingMargin,
            BigDecimal evToSales,
            Integer crowdingScore,
            Integer appealScore,
            String bottomState,
            Integer earningsBottomScore,
            Integer priceBottomScore,
            Integer volumeConfirmationScore,
            Integer failureRiskScore
    ) {
        public Summary {
            ticker = requireText(ticker, "ticker");
            name = requireText(name, "name");
        }
    }

    public record SummaryResult(List<Summary> items) {
        public SummaryResult {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /**
     * Explicit top-level company research projection.
     *
     * <p>The legacy response contains a large, evolving set of nested research
     * fields. The top-level contract is stable and validated here, while the
     * nested document values retain every field, null, list item, and insertion
     * order without importing Jackson or HTTP types into the application layer.</p>
     */
    public record Research(
            ObjectValue profile,
            ObjectValue quote,
            ObjectValue financials,
            ObjectValue score,
            ObjectValue buyScore,
            ArrayValue filings,
            ArrayValue irMaterials,
            ArrayValue highlights,
            StructuredValue peerGroup,
            StructuredValue bottleneck,
            StructuredValue narrative,
            StructuredValue capitalFlow,
            StructuredValue cashFlowQuality,
            StructuredValue multipleInsight,
            StructuredValue guidanceInsight,
            StructuredValue timeframeView,
            StructuredValue correctionAssessment,
            StructuredValue thesisMonitor,
            StructuredValue reversalConfirmation,
            StructuredValue sectorContext,
            StructuredValue verdicts,
            StructuredValue bottomSignal,
            StructuredValue positionSizing,
            StructuredValue executionBridge,
            ArrayValue peers
    ) {
        public Research {
            profile = Objects.requireNonNull(profile, "profile");
            quote = Objects.requireNonNull(quote, "quote");
            financials = Objects.requireNonNull(financials, "financials");
            score = Objects.requireNonNull(score, "score");
            buyScore = Objects.requireNonNull(buyScore, "buyScore");
            filings = Objects.requireNonNull(filings, "filings");
            irMaterials = Objects.requireNonNull(irMaterials, "irMaterials");
            highlights = Objects.requireNonNull(highlights, "highlights");
            peerGroup = Objects.requireNonNull(peerGroup, "peerGroup");
            bottleneck = Objects.requireNonNull(bottleneck, "bottleneck");
            narrative = Objects.requireNonNull(narrative, "narrative");
            capitalFlow = Objects.requireNonNull(capitalFlow, "capitalFlow");
            cashFlowQuality = Objects.requireNonNull(cashFlowQuality, "cashFlowQuality");
            multipleInsight = Objects.requireNonNull(multipleInsight, "multipleInsight");
            guidanceInsight = Objects.requireNonNull(guidanceInsight, "guidanceInsight");
            timeframeView = Objects.requireNonNull(timeframeView, "timeframeView");
            correctionAssessment = Objects.requireNonNull(correctionAssessment, "correctionAssessment");
            thesisMonitor = Objects.requireNonNull(thesisMonitor, "thesisMonitor");
            reversalConfirmation = Objects.requireNonNull(reversalConfirmation, "reversalConfirmation");
            sectorContext = Objects.requireNonNull(sectorContext, "sectorContext");
            verdicts = Objects.requireNonNull(verdicts, "verdicts");
            bottomSignal = Objects.requireNonNull(bottomSignal, "bottomSignal");
            positionSizing = Objects.requireNonNull(positionSizing, "positionSizing");
            executionBridge = Objects.requireNonNull(executionBridge, "executionBridge");
            peers = Objects.requireNonNull(peers, "peers");
        }
    }

    public sealed interface StructuredValue permits
            ObjectValue, ArrayValue, TextValue, NumberValue, BooleanValue, NullValue {
    }

    public record ObjectValue(Map<String, StructuredValue> fields) implements StructuredValue {
        public ObjectValue {
            Objects.requireNonNull(fields, "fields");
            var ordered = new LinkedHashMap<String, StructuredValue>(fields.size());
            fields.forEach((key, value) -> ordered.put(
                    requireText(key, "object field name"),
                    Objects.requireNonNull(value, "object field value")
            ));
            fields = Collections.unmodifiableMap(ordered);
        }
    }

    public record ArrayValue(List<StructuredValue> values) implements StructuredValue {
        public ArrayValue {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    public record TextValue(String value) implements StructuredValue {
        public TextValue {
            value = Objects.requireNonNull(value, "value");
        }
    }

    public record NumberValue(Number value) implements StructuredValue {
        public NumberValue {
            value = Objects.requireNonNull(value, "value");
            if (!(value instanceof Long) && !(value instanceof BigDecimal)) {
                throw new IllegalArgumentException("number value must be Long or BigDecimal");
            }
        }
    }

    public record BooleanValue(boolean value) implements StructuredValue {
    }

    public enum NullValue implements StructuredValue {
        INSTANCE
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
