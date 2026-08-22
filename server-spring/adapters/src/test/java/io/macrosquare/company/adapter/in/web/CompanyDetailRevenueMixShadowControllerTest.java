package io.macrosquare.company.adapter.in.web;

import io.macrosquare.company.application.model.CompanyRevenueMixLegacyRead;
import io.macrosquare.company.application.model.CompanyRevenueMixComposition;
import io.macrosquare.company.application.port.in.CompanyDetailRevenueMixShadowReport;
import io.macrosquare.company.application.port.in.CompanyRevenueMixParityReport;
import io.macrosquare.company.application.service.CompanyRevenueMixComposer;
import io.macrosquare.company.domain.model.CompanyRevenueMixAnalysis;
import io.macrosquare.company.domain.model.CompanyRevenueMixBreakdown;
import io.macrosquare.company.domain.model.CompanyRevenueMixDimension;
import io.macrosquare.company.domain.model.CompanyRevenueMixEntry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CompanyDetailRevenueMixShadowControllerTest {

    @Test
    void exposesShadowSourcesAndKeepsThePublicServingModeExplicit() throws Exception {
        var analysis = new CompanyRevenueMixAnalysis(breakdown(), null, 1, 2);
        var serving = research();
        CompanyRevenueMixComposition composition = new CompanyRevenueMixComposer()
                .compose(serving, analysis);
        var parity = new CompanyRevenueMixParityReport(
                "NVDA", "0001045810", "0001045810",
                10, 1, 1, 2,
                false, true, true, false, true, false,
                List.of("0001045810-26-000001"), List.of(), List.of("geography.coverage"),
                composition.baseline(), analysis
        );
        var report = new CompanyDetailRevenueMixShadowReport(
                "NVDA", true, true, true, false, parity, composition
        );
        var mvc = MockMvcBuilders.standaloneSetup(
                new CompanyDetailRevenueMixShadowController(ignored -> report)
        ).build();

        mvc.perform(get("/internal/v1/migration/company-detail-revenue-mix-shadow/nvda"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicEndpointMode").value("legacy-unchanged"))
                .andExpect(jsonPath("$.contractCompatible").value(true))
                .andExpect(jsonPath("$.servingSnapshotMatched").value(true))
                .andExpect(jsonPath("$.shadowServeReady").value(true))
                .andExpect(jsonPath("$.directMigrationReady").value(false))
                .andExpect(jsonPath("$.fallbackUsed").value(true))
                .andExpect(jsonPath("$.segmentSource").value("direct-sec-actual"))
                .andExpect(jsonPath("$.geographySource").value("legacy-fallback"))
                .andExpect(jsonPath("$.result.shadow.segment[0].label").value("Cloud"))
                .andExpect(jsonPath("$.result.shadow.segment[0].unit").value("USD"))
                .andExpect(jsonPath("$.result.shadow.geography[0].label").value("Legacy US"))
                .andExpect(jsonPath("$.result.shadow.note").value(org.hamcrest.Matchers.containsString(
                        "SEC Inline XBRL"
                )))
                .andExpect(jsonPath("$.shadowDetail").doesNotExist());
    }

    private static CompanyRevenueMixBreakdown breakdown() {
        return new CompanyRevenueMixBreakdown(
                CompanyRevenueMixBreakdown.Category.SEGMENT,
                CompanyRevenueMixDimension.REPORTABLE_SEGMENT,
                "Statement Business Segments",
                LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"),
                "USD", BigDecimal.valueOf(100), BigDecimal.valueOf(100), new BigDecimal("100.0"),
                "https://www.sec.gov/Archives/edgar/data/1/annual.htm",
                List.of(
                        new CompanyRevenueMixEntry("Cloud", BigDecimal.valueOf(60), new BigDecimal("60.0")),
                        new CompanyRevenueMixEntry("Consumer", BigDecimal.valueOf(40), new BigDecimal("40.0"))
                )
        );
    }

    private static io.macrosquare.company.application.model.CompanyReadModels.Research research() {
        var financials = new LinkedHashMap<String, io.macrosquare.company.application.model.CompanyReadModels.StructuredValue>();
        financials.put("revenueTtm", new io.macrosquare.company.application.model.CompanyReadModels.NumberValue(100L));
        financials.put("segmentGeoMixNote", new io.macrosquare.company.application.model.CompanyReadModels.TextValue("legacy note"));
        financials.put("segmentMix", array(mix("Legacy Platform", 60), mix("Legacy Service", 40)));
        financials.put("geoMix", array(mix("Legacy US", 70), mix("Legacy Other", 30)));
        var nil = io.macrosquare.company.application.model.CompanyReadModels.NullValue.INSTANCE;
        return new io.macrosquare.company.application.model.CompanyReadModels.Research(
                object(), object(), new io.macrosquare.company.application.model.CompanyReadModels.ObjectValue(financials),
                object(), object(), array(), array(), array(),
                nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, nil, array()
        );
    }

    private static io.macrosquare.company.application.model.CompanyReadModels.ObjectValue mix(String label, int percent) {
        var nil = io.macrosquare.company.application.model.CompanyReadModels.NullValue.INSTANCE;
        return object(
                "label", new io.macrosquare.company.application.model.CompanyReadModels.TextValue(label),
                "value", nil,
                "unit", nil,
                "percentOfTotal", new io.macrosquare.company.application.model.CompanyReadModels.NumberValue((long) percent)
        );
    }

    private static io.macrosquare.company.application.model.CompanyReadModels.ObjectValue object(Object... values) {
        var fields = new LinkedHashMap<String, io.macrosquare.company.application.model.CompanyReadModels.StructuredValue>();
        for (var index = 0; index < values.length; index += 2) {
            fields.put((String) values[index],
                    (io.macrosquare.company.application.model.CompanyReadModels.StructuredValue) values[index + 1]);
        }
        return new io.macrosquare.company.application.model.CompanyReadModels.ObjectValue(fields);
    }

    private static io.macrosquare.company.application.model.CompanyReadModels.ArrayValue array(
            io.macrosquare.company.application.model.CompanyReadModels.StructuredValue... values
    ) {
        return new io.macrosquare.company.application.model.CompanyReadModels.ArrayValue(List.of(values));
    }
}
