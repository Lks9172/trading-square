package io.macrosquare.research.application.service;

import io.macrosquare.research.domain.narrative.NarrativeSourceDefinition;
import io.macrosquare.research.domain.narrative.NarrativeSourceQuality;

import java.time.Duration;
import java.util.List;

public final class NarrativeSourceCatalog {

    private final List<NarrativeSourceDefinition> definitions = List.of(
            new NarrativeSourceDefinition(
                    "GOOGLE_NEWS_7D", "Google News 7D", NarrativeSourceQuality.PUBLIC_FEED,
                    Duration.ofHours(18), Duration.ofDays(7)),
            new NarrativeSourceDefinition(
                    "WIKIMEDIA_7D", "Wikipedia 관심도", NarrativeSourceQuality.PUBLIC_API,
                    Duration.ofHours(48), Duration.ofDays(10)),
            new NarrativeSourceDefinition(
                    "YOUTUBE_30D", "YouTube 30D", NarrativeSourceQuality.VERIFIED_API,
                    Duration.ofHours(48), Duration.ofDays(10))
    );

    public List<NarrativeSourceDefinition> definitions() {
        return definitions;
    }
}
