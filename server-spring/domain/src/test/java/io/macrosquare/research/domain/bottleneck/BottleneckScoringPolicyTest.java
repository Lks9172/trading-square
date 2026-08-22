package io.macrosquare.research.domain.bottleneck;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottleneckScoringPolicyTest {

    private final BottleneckScoringPolicy policy = new BottleneckScoringPolicy();

    @Test
    void matchesTheExistingTypeScriptGoldenMaster() {
        var summary = "Supply constrained backlog improved with design win and installed base expansion. "
                + "Long lead times and pricing power remain strong.";
        var corpus = String.join("\n", List.of(
                "매출 성장 24.0%",
                "영업이익률 22.0%",
                summary,
                "",
                "deck",
                summary,
                "overall",
                "quality",
                "성장"
        ));
        var candidate = new BottleneckCandidate(
                "TEST",
                "EDA 소프트웨어",
                "EDA",
                List.of("eda", "qualification"),
                new BottleneckPriors(9.0, 7.0, 8.0, 9.0)
        );
        var evidence = new BottleneckEvidence(
                "TEST",
                "Test Co",
                corpus,
                78,
                24.0,
                22.0,
                5.0
        );

        var result = policy.evaluate(candidate, evidence);

        assertEquals(87, result.score());
        assertEquals(BottleneckConviction.CORE, result.conviction());
        assertEquals(new BottleneckComponentScores(10, 8, 9, 7, 8, 10), result.componentScores());
        assertEquals(List.of(
                "EDA 소프트웨어",
                "tag:eda",
                "tag:qualification",
                "공급 제약/타이트 서플라이 언급 ×2",
                "리드타임 장기화 언급 ×3",
                "수주잔고/백로그 언급 ×2",
                "가격 전가력/가격 인상 언급 ×2"
        ), result.reasons());
        assertEquals(List.of("lead-time", "qualification-lockin", "supply-constraint", "backlog", "pricing-power"),
                result.textMatches().stream().map(BottleneckTextMatch::label).toList());
        assertEquals(3.3, result.textMatches().getFirst().score());
        assertTrue(result.textMatches().getFirst().excerpts().getFirst().contains("Supply constrained backlog"));
    }

    @Test
    void defaultsMissingPriorsWithoutLeakingResearchTransportTypesIntoDomain() {
        var result = policy.evaluate(
                new BottleneckCandidate("ABC", "일반 공급자", "테스트", List.of(), null),
                new BottleneckEvidence("ABC", "ABC Co", "", 40, null, null, null)
        );

        assertEquals(27, result.score());
        assertEquals(BottleneckConviction.WATCH, result.conviction());
        assertEquals(new BottleneckComponentScores(0, 0, 5, 5, 5, 5), result.componentScores());
    }
}
