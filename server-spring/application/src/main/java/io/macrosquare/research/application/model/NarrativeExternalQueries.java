package io.macrosquare.research.application.model;

public record NarrativeExternalQueries(
        String youtubeQuery,
        String newsQuery
) {
    public NarrativeExternalQueries {
        if (youtubeQuery == null || youtubeQuery.isBlank()) {
            throw new IllegalArgumentException("youtubeQuery is required");
        }
        if (newsQuery == null || newsQuery.isBlank()) {
            throw new IllegalArgumentException("newsQuery is required");
        }
    }
}
