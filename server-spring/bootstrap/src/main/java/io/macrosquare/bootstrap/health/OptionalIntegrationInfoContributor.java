package io.macrosquare.bootstrap.health;

import io.macrosquare.bootstrap.config.DartProperties;
import io.macrosquare.bootstrap.config.NarrativeSourceProperties;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.Map;
import java.util.Objects;

/** Exposes optional integration readiness without ever exposing credentials. */
public final class OptionalIntegrationInfoContributor implements InfoContributor {

    private final NarrativeSourceProperties narrativeSources;
    private final DartProperties dart;

    public OptionalIntegrationInfoContributor(
            NarrativeSourceProperties narrativeSources,
            DartProperties dart
    ) {
        this.narrativeSources = Objects.requireNonNull(narrativeSources);
        this.dart = Objects.requireNonNull(dart);
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("optionalIntegrations", Map.of(
                "youtube", youtubeState(),
                "openDart", dartState()
        ));
    }

    private Map<String, Object> youtubeState() {
        var configured = !narrativeSources.youtubeApiKey().isBlank();
        var status = !narrativeSources.enabled()
                ? "collector-disabled"
                : configured ? "active" : "missing-excluded";
        return state(narrativeSources.enabled(), configured, status, "YOUTUBE_API_KEY");
    }

    private Map<String, Object> dartState() {
        var configured = !dart.apiKey().isBlank();
        var status = dart.enabled()
                ? "active"
                : configured ? "configured-disabled" : "missing-disabled";
        return state(dart.enabled(), configured, status, "DART_API_KEY");
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
