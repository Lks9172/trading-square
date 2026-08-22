package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.domain.narrative.NarrativeSourceQuality;
import io.macrosquare.research.domain.narrative.NarrativeSourceReading;
import io.macrosquare.research.domain.narrative.NarrativeSourceStatus;
import io.macrosquare.research.domain.narrative.NarrativeTheme;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryNarrativeSourceRepositoryTest {

    @Test
    void appendsOnlyContentChangesAsSameDayRevisions() {
        var repository = new InMemoryNarrativeSourceRepository();
        var first = reading("a", 20);

        assertEquals(1, repository.save(List.of(first)));
        assertEquals(0, repository.save(List.of(first)));
        assertEquals(1, repository.save(List.of(reading("b", 30))));

        var history = repository.loadSince(LocalDate.parse("2026-07-01"));
        assertEquals(2, history.size());
        assertEquals(2, history.getFirst().revision());
        assertEquals(30d, history.getFirst().reading().value());
    }

    private static NarrativeSourceReading reading(String hashCharacter, double value) {
        return new NarrativeSourceReading(
                NarrativeTheme.AI_POWER, "NEWS", "News", LocalDate.parse("2026-07-21"),
                Instant.parse("2026-07-21T12:00:00Z"), NarrativeSourceQuality.PUBLIC_FEED,
                NarrativeSourceStatus.AVAILABLE, value, 7, "detail", "https://example.com",
                hashCharacter.repeat(64), "raw/key");
    }
}
