package io.macrosquare.bootstrap.health;

import io.macrosquare.bootstrap.config.DartProperties;
import io.macrosquare.bootstrap.config.NarrativeSourceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OptionalIntegrationInfoContributorTest {

    @Test
    void reportsMissingOptionalCredentialsWithoutMakingThemLookActive() {
        var info = info(narrativeProperties(true, ""), dartProperties(false, ""));

        assertEquals(Map.of(
                "youtube", state(true, false, "missing-excluded", "YOUTUBE_API_KEY"),
                "openDart", state(false, false, "missing-disabled", "DART_API_KEY")
        ), info.get("optionalIntegrations"));
    }

    @Test
    void reportsConfiguredIntegrationsWithoutLeakingCredentialValues() {
        var info = info(
                narrativeProperties(true, "youtube-secret"),
                dartProperties(false, "dart-secret"));

        assertEquals(Map.of(
                "youtube", state(true, true, "active", "YOUTUBE_API_KEY"),
                "openDart", state(false, true, "configured-disabled", "DART_API_KEY")
        ), info.get("optionalIntegrations"));
        assertFalse(info.toString().contains("youtube-secret"));
        assertFalse(info.toString().contains("dart-secret"));
    }

    private static Info info(
            NarrativeSourceProperties narrativeSources,
            DartProperties dart
    ) {
        var builder = new Info.Builder();
        new OptionalIntegrationInfoContributor(narrativeSources, dart).contribute(builder);
        return builder.build();
    }

    private static NarrativeSourceProperties narrativeProperties(boolean enabled, String apiKey) {
        return new NarrativeSourceProperties(
                enabled,
                URI.create("https://news.google.com"),
                URI.create("https://wikimedia.org"),
                URI.create("https://www.googleapis.com"),
                apiKey,
                "MacroSquare tests",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ZERO,
                Duration.ofHours(6),
                Duration.ZERO,
                1_000_000
        );
    }

    private static DartProperties dartProperties(boolean enabled, String apiKey) {
        return new DartProperties(
                enabled,
                URI.create("https://opendart.fss.or.kr"),
                apiKey,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                Duration.ZERO,
                Duration.ofHours(6),
                Duration.ZERO,
                Duration.ofDays(7),
                1_000_000,
                2_000_000,
                120,
                List.of("005930")
        );
    }

    private static Map<String, Object> state(
            boolean collectorEnabled,
            boolean credentialConfigured,
            String status,
            String requiredEnvironmentVariable
    ) {
        return Map.of(
                "collectorEnabled", collectorEnabled,
                "credentialConfigured", credentialConfigured,
                "status", status,
                "requiredEnvironmentVariable", requiredEnvironmentVariable
        );
    }
}
