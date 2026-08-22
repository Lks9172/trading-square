package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.domain.rotation.SectorRotationRegime;
import io.macrosquare.research.application.port.in.NarrativeParityResult;
import io.macrosquare.research.application.port.in.ResearchParityReport;
import io.macrosquare.research.application.port.in.RotationParityResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ResearchParityResponse(
        String sourceTimestamp,
        boolean allMatched,
        int matchedNarratives,
        int totalNarratives,
        RotationParityResponse rotation,
        List<NarrativeParityResponse> narratives
) {
    static ResearchParityResponse from(ResearchParityReport report) {
        return new ResearchParityResponse(
                report.sourceTimestamp(),
                report.allMatched(),
                report.matchedNarratives(),
                report.totalNarratives(),
                RotationParityResponse.from(report.rotation()),
                report.narratives().stream().map(NarrativeParityResponse::from).toList()
        );
    }

    public record RotationParityResponse(
            boolean matched,
            String expectedRegime,
            String actualRegime,
            int expectedConfidence,
            int actualConfidence,
            Map<String, Integer> expectedScores,
            Map<String, Integer> actualScores,
            List<String> differences
    ) {
        static RotationParityResponse from(RotationParityResult result) {
            return new RotationParityResponse(
                    result.matched(),
                    result.expectedRegime().name(),
                    result.actualRegime().name(),
                    result.expectedConfidence(),
                    result.actualConfidence(),
                    scoreMap(result.expectedScores()),
                    scoreMap(result.actualScores()),
                    result.differences()
            );
        }

        private static Map<String, Integer> scoreMap(Map<SectorRotationRegime, Integer> source) {
            var scores = new LinkedHashMap<String, Integer>();
            source.forEach((key, value) -> scores.put(key.name(), value));
            return scores;
        }
    }

    public record NarrativeParityResponse(
            String themeId,
            boolean matched,
            String expectedStage,
            Integer expectedHeatScore,
            String actualStage,
            Integer actualHeatScore,
            List<String> differences
    ) {
        static NarrativeParityResponse from(NarrativeParityResult result) {
            return new NarrativeParityResponse(
                    result.theme().id(),
                    result.matched(),
                    result.expectedStage() == null ? null : result.expectedStage().name(),
                    result.expectedHeatScore(),
                    result.actualStage() == null ? null : result.actualStage().name(),
                    result.actualHeatScore(),
                    result.differences()
            );
        }
    }
}
