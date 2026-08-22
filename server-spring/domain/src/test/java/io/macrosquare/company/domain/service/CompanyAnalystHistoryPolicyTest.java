package io.macrosquare.company.domain.service;

import io.macrosquare.company.domain.model.CompanyAnalystConsensus;
import io.macrosquare.company.domain.model.CompanyAnalystHistoryPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanyAnalystHistoryPolicyTest {

    private final CompanyAnalystHistoryPolicy policy = new CompanyAnalystHistoryPolicy();

    @Test
    void replacesTheUtcDayAndSortsLikeTheNodeHistoryWriter() {
        var result = policy.recordDaily(
                List.of(
                        point("2026-07-19", 1.0, 20.0),
                        point("2026-07-17", 0.9, 18.0)
                ),
                LocalDate.parse("2026-07-19"),
                new CompanyAnalystConsensus(1.1, 21.5),
                365
        );

        assertEquals(List.of(
                point("2026-07-17", 0.9, 18.0),
                point("2026-07-19", 1.1, 21.5)
        ), result);
    }

    @Test
    void retainsOnlyTheNewestConfiguredNumberOfPoints() {
        var result = policy.recordDaily(
                List.of(
                        point("2026-07-16", 0.7, 16.0),
                        point("2026-07-17", 0.8, 17.0),
                        point("2026-07-18", 0.9, 18.0)
                ),
                LocalDate.parse("2026-07-19"),
                new CompanyAnalystConsensus(1.0, 19.0),
                3
        );

        assertEquals(List.of(
                point("2026-07-17", 0.8, 17.0),
                point("2026-07-18", 0.9, 18.0),
                point("2026-07-19", 1.0, 19.0)
        ), result);
    }

    @Test
    void normalizesLegacyDuplicatesAndRejectsAnUnavailableCurrentObservation() {
        var duplicate = point("2026-07-17", 0.8, 17.0);
        assertThrows(IllegalArgumentException.class, () -> policy.recordDaily(
                List.of(
                        duplicate,
                        duplicate,
                        point("2026-07-19", 0.9, 18.0),
                        point("2026-07-19", 1.0, 19.0)
                ),
                LocalDate.parse("2026-07-19"),
                new CompanyAnalystConsensus(null, null),
                365
        ));

        var result = policy.recordDaily(
                List.of(duplicate, duplicate, point("2026-07-19", 0.9, 18.0)),
                LocalDate.parse("2026-07-19"),
                new CompanyAnalystConsensus(1.1, 21.0),
                365
        );

        assertEquals(List.of(
                duplicate,
                point("2026-07-19", 1.1, 21.0)
        ), result);
    }

    @Test
    void rejectsAnInvalidRetentionLimit() {
        assertThrows(IllegalArgumentException.class, () -> policy.recordDaily(
                List.of(),
                LocalDate.parse("2026-07-19"),
                new CompanyAnalystConsensus(1.0, 20.0),
                0
        ));
    }

    @Test
    void persistsProviderEpsRevisionEvidenceWithTheObservationDate() {
        var result = policy.recordDaily(
                List.of(),
                LocalDate.parse("2026-08-08"),
                new CompanyAnalystConsensus(1.0, 20.0, 1.5, 4.0, 8.0),
                365
        );

        assertEquals(4.0, result.getFirst().epsEstimateRevision30dPct());
        assertEquals(LocalDate.parse("2026-08-08"), result.getFirst().date());
    }

    private static CompanyAnalystHistoryPoint point(String date, Double score, Double upside) {
        return new CompanyAnalystHistoryPoint(LocalDate.parse(date), score, upside);
    }
}
