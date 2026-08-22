package io.macrosquare.research.domain.narrative;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NarrativeSourcePolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");
    private final NarrativeSourcePolicy policy = new NarrativeSourcePolicy();
    private final List<NarrativeSourceDefinition> definitions = List.of(
            new NarrativeSourceDefinition(
                    "NEWS", "News", NarrativeSourceQuality.PUBLIC_FEED,
                    Duration.ofHours(18), Duration.ofDays(7)),
            new NarrativeSourceDefinition(
                    "VIEWS", "Views", NarrativeSourceQuality.PUBLIC_API,
                    Duration.ofHours(48), Duration.ofDays(10)),
            new NarrativeSourceDefinition(
                    "VIDEO", "Video", NarrativeSourceQuality.VERIFIED_API,
                    Duration.ofHours(48), Duration.ofDays(10))
    );

    @Test
    void gradesCoverageAndExcludesMissingSourcesFromTheHeatInput() {
        var assessment = policy.assess(
                NarrativeTheme.AI_POWER,
                definitions,
                List.of(
                        observation("NEWS", NarrativeSourceQuality.PUBLIC_FEED,
                                NarrativeSourceStatus.AVAILABLE, 40d, 7d, NOW.minusSeconds(3_600), 1, "a"),
                        observation("VIEWS", NarrativeSourceQuality.PUBLIC_API,
                                NarrativeSourceStatus.AVAILABLE, 12d, 6d, NOW.minusSeconds(7_200), 1, "b"),
                        observation("VIDEO", NarrativeSourceQuality.VERIFIED_API,
                                NarrativeSourceStatus.MISSING, null, 5d, NOW, 1, "c")
                ),
                List.of(),
                NOW
        );

        assertEquals(NarrativeSourceCoverageStatus.HEALTHY, assessment.status());
        assertEquals(67, assessment.coveragePct());
        assertEquals(62, assessment.qualityScore());
        assertEquals(0, assessment.signals().stream()
                .filter(value -> value.key().equals("VIDEO")).findFirst().orElseThrow().weight());
    }

    @Test
    void usesARecentLastValidValueAsStaleAfterTheLatestFailure() {
        var assessment = policy.assess(
                NarrativeTheme.AI_POWER,
                definitions,
                List.of(
                        observation("NEWS", NarrativeSourceQuality.PUBLIC_FEED,
                                NarrativeSourceStatus.FAILED, null, 5d, NOW, 2, "b"),
                        observation("NEWS", NarrativeSourceQuality.PUBLIC_FEED,
                                NarrativeSourceStatus.AVAILABLE, 33d, 7d, NOW.minus(Duration.ofDays(2)), 1, "a")
                ),
                List.of(),
                NOW
        );

        var signal = assessment.signals().stream().filter(value -> value.key().equals("NEWS"))
                .findFirst().orElseThrow();
        var diagnostic = assessment.diagnostics().stream().filter(value -> value.sourceKey().equals("NEWS"))
                .findFirst().orElseThrow();
        assertEquals(NarrativeSourceStatus.STALE, signal.status());
        assertEquals(33d, signal.value());
        assertEquals(0.65 * 0.35, signal.weight(), 0.0001);
        assertEquals(1, diagnostic.missingStreak());
    }

    @Test
    void fallsBackToLegacyOnlyBeforeTheNativeSourceHistoryExists() {
        var legacy = List.of(new NarrativeExternalSignal("LEGACY", "Legacy", 10d, 7d, "legacy"));
        var assessment = policy.assess(
                NarrativeTheme.AI_POWER, definitions, List.of(), legacy, NOW);

        assertEquals(NarrativeSourceCoverageStatus.DEGRADED, assessment.status());
        assertTrue(assessment.legacyFallbackUsed());
        assertEquals(legacy, assessment.signals());
        assertFalse(assessment.diagnostics().isEmpty());
    }

    @Test
    void doesNotHideANativeOutageBehindLegacySignals() {
        var legacy = List.of(new NarrativeExternalSignal("LEGACY", "Legacy", 10d, 9d, "legacy"));
        var assessment = policy.assess(
                NarrativeTheme.AI_POWER,
                definitions,
                List.of(observation(
                        "NEWS", NarrativeSourceQuality.PUBLIC_FEED, NarrativeSourceStatus.FAILED,
                        null, 5d, NOW, 1, "f")),
                legacy,
                NOW
        );

        assertEquals(NarrativeSourceCoverageStatus.UNAVAILABLE, assessment.status());
        assertFalse(assessment.legacyFallbackUsed());
        assertEquals(0, assessment.coveragePct());
        assertTrue(assessment.signals().stream().allMatch(value -> value.weight() == 0));
    }

    @Test
    void expiresTheLastAvailableValuePastTheMaximumFallbackAge() {
        var assessment = policy.assess(
                NarrativeTheme.AI_POWER,
                definitions,
                List.of(observation(
                        "NEWS", NarrativeSourceQuality.PUBLIC_FEED, NarrativeSourceStatus.AVAILABLE,
                        80d, 9d, NOW.minus(Duration.ofDays(8)), 1, "e")),
                List.of(),
                NOW
        );

        var signal = assessment.signals().stream()
                .filter(value -> value.key().equals("NEWS"))
                .findFirst().orElseThrow();
        assertEquals(NarrativeSourceStatus.MISSING, signal.status());
        assertEquals(0, signal.weight());
        assertNull(signal.value());
    }

    @Test
    void exposesBoundedChronologicalAuditHistoryAndRevisionCounters() {
        var oldest = NOW.minus(Duration.ofDays(2));
        var prior = NOW.minus(Duration.ofDays(1));
        var assessment = policy.assess(
                NarrativeTheme.AI_POWER,
                definitions,
                List.of(
                        observation("NEWS", NarrativeSourceQuality.PUBLIC_FEED,
                                NarrativeSourceStatus.AVAILABLE, 20d, 6d, oldest, 1, "a"),
                        observation("NEWS", NarrativeSourceQuality.PUBLIC_FEED,
                                NarrativeSourceStatus.MISSING, null, 5d, prior, 1, "b"),
                        observation("NEWS", NarrativeSourceQuality.PUBLIC_FEED,
                                NarrativeSourceStatus.FAILED, null, 5d, NOW.minusSeconds(60), 1, "c"),
                        observation("NEWS", NarrativeSourceQuality.PUBLIC_FEED,
                                NarrativeSourceStatus.AVAILABLE, 35d, 8d, NOW, 2, "d")
                ),
                List.of(),
                NOW
        );

        assertEquals(4, assessment.observationCount());
        assertEquals(1, assessment.revisionEventCount());
        assertEquals(1, assessment.missingObservationCount());
        assertEquals(1, assessment.failedObservationCount());
        assertEquals(NOW, assessment.lastRefreshAt());
        assertEquals(4, assessment.history().size());
        assertEquals(oldest, assessment.history().getFirst().observedAt());
        assertEquals(NOW, assessment.history().getLast().observedAt());
        assertEquals(2, assessment.history().getLast().revision());
        assertNull(assessment.history().get(1).score());
    }

    @Test
    void boundsTheAuditProjectionWithoutLosingAggregateCounts() {
        var observations = IntStream.range(0, 200)
                .mapToObj(index -> observation(
                        "NEWS", NarrativeSourceQuality.PUBLIC_FEED, NarrativeSourceStatus.AVAILABLE,
                        (double) index, 6d, NOW.minusSeconds((199L - index) * 60), 1,
                        Integer.toHexString(index % 16)))
                .toList();

        var assessment = policy.assess(
                NarrativeTheme.AI_POWER, definitions, observations, List.of(), NOW);

        assertEquals(200, assessment.observationCount());
        assertEquals(180, assessment.history().size());
        assertEquals(NOW.minusSeconds(179L * 60), assessment.history().getFirst().observedAt());
        assertEquals(NOW, assessment.history().getLast().observedAt());
    }

    private static NarrativeSourceObservation observation(
            String sourceKey,
            NarrativeSourceQuality quality,
            NarrativeSourceStatus status,
            Double value,
            double score,
            Instant observedAt,
            int revision,
            String hashCharacter
    ) {
        return new NarrativeSourceObservation(new NarrativeSourceReading(
                NarrativeTheme.AI_POWER,
                sourceKey,
                sourceKey,
                LocalDate.ofInstant(observedAt, java.time.ZoneOffset.UTC),
                observedAt,
                quality,
                status,
                value,
                score,
                "detail",
                "https://example.com",
                hashCharacter.repeat(64),
                "raw/key"
        ), revision);
    }
}
