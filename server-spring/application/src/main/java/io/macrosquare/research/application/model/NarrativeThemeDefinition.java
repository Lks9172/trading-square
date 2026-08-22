package io.macrosquare.research.application.model;

import io.macrosquare.research.domain.narrative.NarrativeTheme;

import java.util.List;

public record NarrativeThemeDefinition(
        NarrativeTheme theme,
        String title,
        String description,
        List<String> proxies,
        NarrativeExternalQueries externalQueries
) {
    public NarrativeThemeDefinition {
        if (theme == null) throw new IllegalArgumentException("theme is required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description is required");
        }
        proxies = List.copyOf(proxies == null ? List.of() : proxies);
        if (proxies.isEmpty()) throw new IllegalArgumentException("proxies are required");
        if (externalQueries == null) throw new IllegalArgumentException("externalQueries are required");
    }
}
