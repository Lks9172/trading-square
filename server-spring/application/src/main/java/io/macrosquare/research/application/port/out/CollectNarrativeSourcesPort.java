package io.macrosquare.research.application.port.out;

import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.domain.narrative.NarrativeSourceReading;

import java.util.List;

public interface CollectNarrativeSourcesPort {

    List<NarrativeSourceReading> collect(List<NarrativeThemeDefinition> themes);
}
