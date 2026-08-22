package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.domain.rotation.SectorRotationRegime;
import io.macrosquare.research.application.port.in.NarrativeParityResult;
import io.macrosquare.research.application.port.in.ResearchParityReport;
import io.macrosquare.research.application.port.in.RotationParityResult;
import io.macrosquare.research.application.port.out.ResearchSnapshotUnavailableException;
import io.macrosquare.research.domain.narrative.NarrativeStage;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResearchParityControllerTest {

    @Test
    void exposesAnInternalReadOnlyParityReportThroughTransportDtos() throws Exception {
        var scores = new LinkedHashMap<SectorRotationRegime, Integer>();
        scores.put(SectorRotationRegime.EARLY_CYCLICAL, 78);
        scores.put(SectorRotationRegime.MID_GROWTH, 81);
        scores.put(SectorRotationRegime.LATE_INFLATION, 32);
        scores.put(SectorRotationRegime.DEFENSIVE, 11);
        scores.put(SectorRotationRegime.RE_ACCELERATION, 100);
        var report = new ResearchParityReport(
                "2026-07-19T00:00:00Z",
                true,
                1,
                1,
                new RotationParityResult(
                        true,
                        SectorRotationRegime.RE_ACCELERATION,
                        SectorRotationRegime.RE_ACCELERATION,
                        69,
                        69,
                        scores,
                        scores,
                        List.of()
                ),
                List.of(new NarrativeParityResult(
                        NarrativeTheme.AI_POWER,
                        true,
                        NarrativeStage.MID,
                        46,
                        NarrativeStage.MID,
                        46,
                        List.of()
                ))
        );
        var mvc = MockMvcBuilders.standaloneSetup(new ResearchParityController(() -> report)).build();

        mvc.perform(get("/internal/v1/migration/research-parity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allMatched").value(true))
                .andExpect(jsonPath("$.rotation.expectedRegime").value("RE_ACCELERATION"))
                .andExpect(jsonPath("$.rotation.expectedScores.RE_ACCELERATION").value(100))
                .andExpect(jsonPath("$.narratives[0].themeId").value("ai-power"))
                .andExpect(jsonPath("$.narratives[0].actualHeatScore").value(46));
    }

    @Test
    void returnsASafeBadGatewayWhenTheLegacySnapshotCannotBeLoaded() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ResearchParityController(() -> {
                    throw new ResearchSnapshotUnavailableException("internal upstream detail");
                }))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/internal/v1/migration/research-parity"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Legacy research snapshot is temporarily unavailable"));
    }
}
