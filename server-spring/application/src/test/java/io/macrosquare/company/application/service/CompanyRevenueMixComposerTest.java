package io.macrosquare.company.application.service;

import io.macrosquare.company.application.model.CompanyReadModels.ArrayValue;
import io.macrosquare.company.application.model.CompanyReadModels.NullValue;
import io.macrosquare.company.application.model.CompanyReadModels.NumberValue;
import io.macrosquare.company.application.model.CompanyReadModels.ObjectValue;
import io.macrosquare.company.application.model.CompanyReadModels.Research;
import io.macrosquare.company.application.model.CompanyReadModels.StructuredValue;
import io.macrosquare.company.application.model.CompanyReadModels.TextValue;
import io.macrosquare.company.application.model.CompanyRevenueMixComposition.Source;
import io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis;
import io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown;
import io.macrosquare.company.domain.model.CompanyRevenueMixDimension;
import io.macrosquare.company.domain.model.CompanyRevenueMixEntry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanyRevenueMixComposerTest {

    private final CompanyRevenueMixComposer composer = new CompanyRevenueMixComposer();

    @Test
    void replacesOnlyExistingMixFieldsWithDirectActualValues() {
        var serving = research(true);
        var actual = new CompanyRevenueMixAnalysis(
                breakdown(CompanyRevenueMixBreakdown.Category.SEGMENT, "Cloud", 65, "Consumer", 35),
                breakdown(CompanyRevenueMixBreakdown.Category.GEOGRAPHY, "United States", 70, "Other", 30),
                2,
                4
        );

        var result = composer.compose(serving, actual);

        assertEquals(Source.DIRECT_SEC_ACTUAL, result.segmentSource());
        assertEquals(Source.DIRECT_SEC_ACTUAL, result.geographySource());
        assertFalse(result.fallbackUsed());
        assertEquals("Cloud", result.resolved().segment().getFirst().label());
        assertEquals(BigDecimal.valueOf(65), result.resolved().segment().getFirst().value());
        assertEquals("USD", result.resolved().segment().getFirst().unit());
        assertEquals(new BigDecimal("65.0"), result.resolved().segment().getFirst().percentOfTotal());
        assertTrue(result.resolved().note().contains("세그먼트(SEC Inline XBRL, 2025-12-31)"));
        assertTrue(result.resolved().note().contains("지역(SEC Inline XBRL, 2025-12-31)"));
        assertSame(serving.quote(), result.enrichedDetail().quote());
        assertEquals(
                serving.financials().fields().get("revenueTtm"),
                result.enrichedDetail().financials().fields().get("revenueTtm")
        );
        assertEquals(serving.financials().fields().keySet(), result.enrichedDetail().financials().fields().keySet());
    }

    @Test
    void keepsTheLegacyAxisWhenOnlyOneDirectAxisIsAvailable() {
        var result = composer.compose(
                research(true),
                new CompanyRevenueMixAnalysis(
                        breakdown(CompanyRevenueMixBreakdown.Category.SEGMENT, "Cloud", 80, "Consumer", 20),
                        null,
                        1,
                        2
                )
        );

        assertEquals(Source.DIRECT_SEC_ACTUAL, result.segmentSource());
        assertEquals(Source.BASELINE_FALLBACK, result.geographySource());
        assertTrue(result.fallbackUsed());
        assertEquals("Legacy US", result.resolved().geography().getFirst().label());
        assertTrue(result.resolved().note().contains("지역(legacy fallback)"));
    }

    @Test
    void preservesServingValuesExactlyWhenThereIsNoDirectActual() {
        var serving = research(true);

        var result = composer.compose(serving, new CompanyRevenueMixAnalysis(null, null, 0, 0));

        assertEquals(Source.BASELINE_FALLBACK, result.segmentSource());
        assertEquals(Source.BASELINE_FALLBACK, result.geographySource());
        assertFalse(result.actualUsed());
        assertEquals(result.baseline(), result.resolved());
        assertEquals(serving, result.enrichedDetail());
    }

    @Test
    void marksAnAbsentAxisUnavailableInsteadOfInventingRepresentativeData() {
        var result = composer.compose(
                research(false),
                new CompanyRevenueMixAnalysis(
                        breakdown(CompanyRevenueMixBreakdown.Category.SEGMENT, "Cloud", 80, "Consumer", 20),
                        null,
                        1,
                        2
                )
        );

        assertEquals(Source.UNAVAILABLE, result.geographySource());
        assertTrue(result.resolved().geography().isEmpty());
        assertFalse(result.resolved().note().contains("지역("));
    }

    @Test
    void removesZeroOrUnpercentedLegacyFactsInsteadOfPresentingThemAsRevenueMix() {
        var serving = research(true);
        var financials = new LinkedHashMap<>(serving.financials().fields());
        financials.put("segmentMix", array(object(
                "label", text("Sales Commissions and Fees"),
                "value", number(0),
                "unit", text("USD"),
                "percentOfTotal", NullValue.INSTANCE
        )));
        var invalid = withFinancials(serving, new ObjectValue(financials));

        var result = composer.compose(invalid, new CompanyRevenueMixAnalysis(null, null, 0, 0));

        assertEquals(Source.REJECTED_BASELINE, result.segmentSource());
        assertTrue(result.resolved().segment().isEmpty());
        assertEquals(Source.BASELINE_FALLBACK, result.geographySource());
        assertFalse(result.resolved().note().contains("Sales Commissions"));
    }

    @Test
    void rejectsAccountingConceptsThatOnlyCoincidentallySumToOneHundredPercent() {
        var serving = research(true);
        var financials = new LinkedHashMap<>(serving.financials().fields());
        financials.put("segmentMix", array(
                mixEntry("Revenue, Net (Deprecated 2018-01-31)", 47),
                mixEntry("Sales Revenue, Goods, Net (Deprecated 2018-01-31)", 37),
                mixEntry("Other Inventories, Spare Parts, Gross", 16)
        ));
        var invalid = withFinancials(serving, new ObjectValue(financials));

        var result = composer.compose(invalid, new CompanyRevenueMixAnalysis(null, null, 0, 0));

        assertEquals(Source.REJECTED_BASELINE, result.segmentSource());
        assertTrue(result.resolved().segment().isEmpty());
        assertEquals(Source.BASELINE_FALLBACK, result.geographySource());
    }

    static Research research(boolean includeLegacyMix) {
        var financials = new LinkedHashMap<String, StructuredValue>();
        financials.put("revenueTtm", number(1_000));
        financials.put("segmentGeoMixNote", includeLegacyMix ? text("legacy note") : NullValue.INSTANCE);
        financials.put("segmentMix", includeLegacyMix
                ? array(mixEntry("Legacy Platform", 60), mixEntry("Legacy Service", 40))
                : array());
        financials.put("geoMix", includeLegacyMix
                ? array(mixEntry("Legacy US", 70), mixEntry("Legacy Other", 30))
                : array());
        return new Research(
                object("ticker", text("NVDA")),
                object("symbol", text("NVDA")),
                new ObjectValue(financials),
                object(), object(), array(), array(), array(),
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, NullValue.INSTANCE, NullValue.INSTANCE,
                NullValue.INSTANCE, array()
        );
    }

    static CompanyRevenueMixBreakdown breakdown(
            CompanyRevenueMixBreakdown.Category category,
            String firstLabel,
            int firstPercent,
            String secondLabel,
            int secondPercent
    ) {
        return new CompanyRevenueMixBreakdown(
                category,
                category == CompanyRevenueMixBreakdown.Category.GEOGRAPHY
                        ? CompanyRevenueMixDimension.GEOGRAPHY
                        : CompanyRevenueMixDimension.REPORTABLE_SEGMENT,
                category == CompanyRevenueMixBreakdown.Category.GEOGRAPHY
                        ? "Statement Geographical"
                        : "Statement Business Segments",
                LocalDate.parse("2025-01-01"),
                LocalDate.parse("2025-12-31"),
                "USD",
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(100),
                new BigDecimal("100.0"),
                "https://www.sec.gov/Archives/edgar/data/1/annual.htm",
                List.of(
                        new CompanyRevenueMixEntry(
                                firstLabel, BigDecimal.valueOf(firstPercent), BigDecimal.valueOf(firstPercent).setScale(1)
                        ),
                        new CompanyRevenueMixEntry(
                                secondLabel, BigDecimal.valueOf(secondPercent), BigDecimal.valueOf(secondPercent).setScale(1)
                        )
                )
        );
    }

    static ObjectValue mixEntry(String label, int percent) {
        return object(
                "label", text(label),
                "value", NullValue.INSTANCE,
                "unit", NullValue.INSTANCE,
                "percentOfTotal", number(percent)
        );
    }

    static ObjectValue object(Object... entries) {
        var fields = new LinkedHashMap<String, StructuredValue>();
        for (var index = 0; index < entries.length; index += 2) {
            fields.put((String) entries[index], (StructuredValue) entries[index + 1]);
        }
        return new ObjectValue(fields);
    }

    static ArrayValue array(StructuredValue... values) {
        return new ArrayValue(List.of(values));
    }

    static TextValue text(String value) {
        return new TextValue(value);
    }

    static NumberValue number(long value) {
        return new NumberValue(value);
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
}
