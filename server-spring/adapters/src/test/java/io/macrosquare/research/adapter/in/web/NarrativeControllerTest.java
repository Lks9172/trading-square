package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.model.NarrativeHistoryPoint;
import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.application.model.NarrativeThemeView;
import io.macrosquare.research.application.model.NarrativeTrend;
import io.macrosquare.research.application.port.in.NarrativeThemeNotFoundException;
import io.macrosquare.research.application.port.in.QueryNarrativesUseCase;
import io.macrosquare.research.application.service.NarrativeThemeCatalog;
import io.macrosquare.research.domain.narrative.NarrativeExternalSignal;
import io.macrosquare.research.domain.narrative.NarrativeProxyScore;
import io.macrosquare.research.domain.narrative.NarrativeSourceAssessment;
import io.macrosquare.research.domain.narrative.NarrativeSourceCoverageStatus;
import io.macrosquare.research.domain.narrative.NarrativeSourceHistoryPoint;
import io.macrosquare.research.domain.narrative.NarrativeSourceQuality;
import io.macrosquare.research.domain.narrative.NarrativeSourceStatus;
import io.macrosquare.research.domain.narrative.NarrativeStage;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import io.macrosquare.research.domain.narrative.NarrativeThemeState;
import io.macrosquare.system.adapter.in.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NarrativeControllerTest {

    @Test
    void preservesTheNodeCatalogOverviewAndDetailJsonContracts() throws Exception {
        var definition = new NarrativeThemeCatalog().definition(NarrativeTheme.AI_POWER);
        var view = view(definition);
        var controller = new NarrativeController(stub(definition, view));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/narrative/themes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes[0].id").value("ai-power"))
                .andExpect(jsonPath("$.themes[0].externalQueries.youtubeQuery")
                        .value("AI infrastructure semiconductor datacenter power"));

        var overviewBody = mvc.perform(get("/api/narrative/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themes[0].theme.title").value("AI / 반도체"))
                .andExpect(jsonPath("$.themes[0].stage").value("MID"))
                .andExpect(jsonPath("$.themes[0].sourceObservationCount").value(1))
                .andExpect(jsonPath("$.themes[0].sourceHistory").isEmpty())
                .andExpect(jsonPath("$.themes[0].heatDelta30d").doesNotExist())
                .andExpect(jsonPath("$.themes[0].heatHistory[0].date").value("2026-07-18"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(overviewBody.contains("\"value\":8680,"));
        assertFalse(overviewBody.contains("\"value\":8680.0"));
        assertTrue(overviewBody.contains("\"heatDelta30d\":null"));

        var detailBody = mvc.perform(get("/api/narrative/themes/ai-power"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme.id").value("ai-power"))
                .andExpect(jsonPath("$.proxyScores[0].score").value(2))
                .andExpect(jsonPath("$.sourceRevisionCount").value(1))
                .andExpect(jsonPath("$.sourceLastRefreshAt").value("2026-07-19T00:00:00Z"))
                .andExpect(jsonPath("$.sourceHistoryTruncated").value(false))
                .andExpect(jsonPath("$.sourceHistory[0].sourceKey").value("YOUTUBE_30D"))
                .andExpect(jsonPath("$.sourceHistory[0].revision").value(2))
                .andReturn().getResponse().getContentAsString();
        assertFalse(detailBody.contains("contentHash"));
        assertFalse(detailBody.contains("rawObjectKey"));
    }

    @Test
    void mapsUnknownThemeIdsToTheLegacy404Contract() throws Exception {
        var definition = new NarrativeThemeCatalog().definition(NarrativeTheme.AI_POWER);
        var controller = new NarrativeController(stub(definition, view(definition)));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        mvc.perform(get("/api/narrative/themes/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("narrative theme not found"));
    }

    private static QueryNarrativesUseCase stub(
            NarrativeThemeDefinition definition,
            NarrativeThemeView view
    ) {
        return new QueryNarrativesUseCase() {
            @Override
            public List<NarrativeThemeDefinition> listDefinitions() {
                return List.of(definition);
            }

            @Override
            public List<NarrativeThemeView> getOverview() {
                return List.of(view);
            }

            @Override
            public NarrativeThemeView getTheme(String themeId) {
                if (!"ai-power".equals(themeId)) throw new NarrativeThemeNotFoundException();
                return view;
            }
        };
    }

    private static NarrativeThemeView view(NarrativeThemeDefinition definition) {
        var state = new NarrativeThemeState(
                NarrativeTheme.AI_POWER,
                NarrativeStage.MID,
                46,
                List.of("NASDAQ BUY"),
                List.of("YouTube Search 과열 8680"),
                List.of(new NarrativeProxyScore("SECTOR_SOXX", "SOXX 30D", 2, "SOXX -11.7%")),
                List.of(new NarrativeExternalSignal(
                        "YOUTUBE_30D", "YouTube Search", 8680.0, 9, "검색 추정 8680건"
                ))
        );
        var observedAt = Instant.parse("2026-07-19T00:00:00Z");
        var sourceAssessment = new NarrativeSourceAssessment(
                NarrativeSourceCoverageStatus.DEGRADED,
                25,
                33,
                false,
                state.externalSignals(),
                List.of(),
                List.of(new NarrativeSourceHistoryPoint(
                        "YOUTUBE_30D", "YouTube Search", LocalDate.parse("2026-07-19"), observedAt,
                        2, NarrativeSourceQuality.VERIFIED_API, NarrativeSourceStatus.AVAILABLE,
                        8680d, 9d, "검색 추정 8680건", "https://youtube.com")),
                1,
                1,
                0,
                0,
                observedAt
        );
        return new NarrativeThemeView(
                definition,
                "2026-07-19T00:00:00.000Z",
                state,
                NarrativeTrend.STABLE,
                1,
                null,
                List.of(new NarrativeHistoryPoint("2026-07-18", 45)),
                sourceAssessment
        );
    }
}
