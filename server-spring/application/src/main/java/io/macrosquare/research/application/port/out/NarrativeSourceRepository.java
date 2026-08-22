package io.macrosquare.research.application.port.out;

import io.macrosquare.research.domain.narrative.NarrativeSourceObservation;
import io.macrosquare.research.domain.narrative.NarrativeSourceReading;

import java.time.LocalDate;
import java.util.List;

public interface NarrativeSourceRepository {

    int save(List<NarrativeSourceReading> readings);

    List<NarrativeSourceObservation> loadSince(LocalDate since);
}
