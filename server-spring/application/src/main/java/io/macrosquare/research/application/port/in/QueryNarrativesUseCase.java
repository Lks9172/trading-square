package io.macrosquare.research.application.port.in;

import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.application.model.NarrativeThemeView;

import java.util.List;

public interface QueryNarrativesUseCase {
    List<NarrativeThemeDefinition> listDefinitions();

    List<NarrativeThemeView> getOverview();

    NarrativeThemeView getTheme(String themeId);
}
