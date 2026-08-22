package io.macrosquare.research.adapter.out.persistence;

import io.macrosquare.research.application.port.out.NarrativeSourceRepository;
import io.macrosquare.research.domain.narrative.NarrativeSourceObservation;
import io.macrosquare.research.domain.narrative.NarrativeSourceReading;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class InMemoryNarrativeSourceRepository implements NarrativeSourceRepository {

    private final List<NarrativeSourceObservation> values = new ArrayList<>();

    @Override
    public synchronized int save(List<NarrativeSourceReading> readings) {
        var inserted = 0;
        for (var reading : readings) {
            var revisions = values.stream()
                    .filter(value -> sameSeriesDate(value.reading(), reading))
                    .sorted(Comparator.comparingInt(NarrativeSourceObservation::revision).reversed())
                    .toList();
            if (!revisions.isEmpty()
                    && revisions.getFirst().reading().contentHash().equals(reading.contentHash())) {
                continue;
            }
            var revision = revisions.isEmpty() ? 1 : revisions.getFirst().revision() + 1;
            values.add(new NarrativeSourceObservation(reading, revision));
            inserted++;
        }
        return inserted;
    }

    @Override
    public synchronized List<NarrativeSourceObservation> loadSince(LocalDate since) {
        return values.stream()
                .filter(value -> !value.reading().observationDate().isBefore(since))
                .sorted(Comparator.comparing(
                                (NarrativeSourceObservation value) -> value.reading().observationDate())
                        .reversed()
                        .thenComparing(Comparator.comparingInt(NarrativeSourceObservation::revision).reversed()))
                .toList();
    }

    private static boolean sameSeriesDate(NarrativeSourceReading left, NarrativeSourceReading right) {
        return left.theme() == right.theme()
                && left.sourceKey().equals(right.sourceKey())
                && left.observationDate().equals(right.observationDate());
    }
}
