package io.macrosquare.research.adapter.in.web;

import io.macrosquare.research.application.model.NarrativeExternalQueries;
import io.macrosquare.research.application.model.NarrativeHistoryPoint;
import io.macrosquare.research.application.model.NarrativeThemeDefinition;
import io.macrosquare.research.application.model.NarrativeThemeView;
import io.macrosquare.research.domain.narrative.NarrativeExternalSignal;
import io.macrosquare.research.domain.narrative.NarrativeProxyScore;
import io.macrosquare.research.domain.narrative.NarrativeSourceDiagnostic;
import io.macrosquare.research.domain.narrative.NarrativeSourceHistoryPoint;

import java.util.List;

public final class NarrativeApiResponse {

    private NarrativeApiResponse() {
    }

    public record Catalog(List<Definition> themes) {
        static Catalog from(List<NarrativeThemeDefinition> definitions) {
            return new Catalog(definitions.stream().map(Definition::from).toList());
        }
    }

    public record Overview(List<Theme> themes) {
        static Overview from(List<NarrativeThemeView> views) {
            return new Overview(views.stream().map(Theme::summary).toList());
        }
    }

    public record Theme(
            Definition theme,
            String generatedAt,
            String stage,
            int heatScore,
            List<String> drivers,
            List<String> risks,
            List<ProxyScore> proxyScores,
            List<ExternalSignal> externalSignals,
            String sourceStatus,
            int sourceQualityScore,
            int sourceCoveragePct,
            boolean legacyFallbackUsed,
            List<SourceDiagnostic> sourceDiagnostics,
            int sourceObservationCount,
            int sourceRevisionCount,
            int sourceMissingCount,
            int sourceFailureCount,
            String sourceLastRefreshAt,
            List<SourceHistoryPoint> sourceHistory,
            boolean sourceHistoryTruncated,
            String sourceMethodology,
            String trend,
            Integer heatDelta7d,
            Integer heatDelta30d,
            List<HistoryPoint> heatHistory
    ) {
        static Theme from(NarrativeThemeView view) {
            return from(view, true);
        }

        static Theme summary(NarrativeThemeView view) {
            return from(view, false);
        }

        private static Theme from(NarrativeThemeView view, boolean includeSourceHistory) {
            return new Theme(
                    Definition.from(view.definition()),
                    view.generatedAt(),
                    view.state().stage().name(),
                    view.state().heatScore(),
                    view.state().drivers(),
                    view.state().risks(),
                    view.state().proxyScores().stream().map(ProxyScore::from).toList(),
                    view.state().externalSignals().stream().map(ExternalSignal::from).toList(),
                    view.sourceAssessment().status().name(),
                    view.sourceAssessment().qualityScore(),
                    view.sourceAssessment().coveragePct(),
                    view.sourceAssessment().legacyFallbackUsed(),
                    view.sourceAssessment().diagnostics().stream().map(SourceDiagnostic::from).toList(),
                    view.sourceAssessment().observationCount(),
                    view.sourceAssessment().revisionEventCount(),
                    view.sourceAssessment().missingObservationCount(),
                    view.sourceAssessment().failedObservationCount(),
                    view.sourceAssessment().lastRefreshAt() == null
                            ? null
                            : view.sourceAssessment().lastRefreshAt().toString(),
                    includeSourceHistory
                            ? view.sourceAssessment().history().stream().map(SourceHistoryPoint::from).toList()
                            : List.of(),
                    view.sourceAssessment().history().size() < view.sourceAssessment().observationCount(),
                    "공식·검증 API·공개 API·공개 feed 순으로 신뢰 가중치를 적용합니다. "
                            + "결측 소스는 점수에서 제외하고 제한 기간 내 마지막 유효값만 STALE로 감쇠 사용하며, "
                            + "최근 45일 관측과 당일 revision을 별도로 보존합니다.",
                    view.trend().name(),
                    view.heatDelta7d(),
                    view.heatDelta30d(),
                    view.heatHistory().stream().map(HistoryPoint::from).toList()
            );
        }
    }

    public record SourceHistoryPoint(
            String sourceKey,
            String label,
            String observationDate,
            String observedAt,
            int revision,
            String quality,
            String status,
            Number value,
            Number score,
            String detail,
            String sourceUrl
    ) {
        static SourceHistoryPoint from(NarrativeSourceHistoryPoint value) {
            return new SourceHistoryPoint(
                    value.sourceKey(), value.label(), value.observationDate().toString(),
                    value.observedAt().toString(), value.revision(), value.quality().name(),
                    value.status().name(), jsonNumber(value.value()), jsonNumber(value.score()),
                    value.detail(), value.sourceUrl());
        }
    }

    public record Definition(
            String id,
            String title,
            String description,
            List<String> proxies,
            ExternalQueries externalQueries
    ) {
        static Definition from(NarrativeThemeDefinition definition) {
            return new Definition(
                    definition.theme().id(),
                    definition.title(),
                    definition.description(),
                    definition.proxies(),
                    ExternalQueries.from(definition.externalQueries())
            );
        }
    }

    public record ExternalQueries(String youtubeQuery, String newsQuery) {
        static ExternalQueries from(NarrativeExternalQueries queries) {
            return new ExternalQueries(queries.youtubeQuery(), queries.newsQuery());
        }
    }

    public record ProxyScore(String key, String label, Number score, String detail) {
        static ProxyScore from(NarrativeProxyScore score) {
            return new ProxyScore(score.key(), score.label(), jsonNumber(score.score()), score.detail());
        }
    }

    public record ExternalSignal(
            String key,
            String label,
            Number value,
            Number score,
            String detail,
            String quality,
            String status,
            String observedAt,
            Integer revision,
            Number weight,
            String sourceUrl
    ) {
        static ExternalSignal from(NarrativeExternalSignal signal) {
            return new ExternalSignal(
                    signal.key(),
                    signal.label(),
                    jsonNumber(signal.value()),
                    jsonNumber(signal.score()),
                    signal.detail(),
                    signal.quality().name(),
                    signal.status().name(),
                    signal.observedAt() == null ? null : signal.observedAt().toString(),
                    signal.revision(),
                    jsonNumber(signal.weight()),
                    signal.sourceUrl()
            );
        }
    }

    public record SourceDiagnostic(
            String sourceKey,
            String label,
            String quality,
            String status,
            String latestObservedAt,
            String lastAvailableAt,
            Long ageHours,
            Integer revision,
            int missingStreak,
            Number value,
            Number score,
            String detail,
            String sourceUrl,
            Number effectiveWeight
    ) {
        static SourceDiagnostic from(NarrativeSourceDiagnostic value) {
            return new SourceDiagnostic(
                    value.sourceKey(), value.label(), value.quality().name(), value.status().name(),
                    value.latestObservedAt() == null ? null : value.latestObservedAt().toString(),
                    value.lastAvailableAt() == null ? null : value.lastAvailableAt().toString(),
                    value.ageHours(), value.revision(), value.missingStreak(),
                    jsonNumber(value.value()), jsonNumber(value.score()), value.detail(), value.sourceUrl(),
                    jsonNumber(value.effectiveWeight())
            );
        }
    }

    public record HistoryPoint(String date, int heatScore) {
        static HistoryPoint from(NarrativeHistoryPoint point) {
            return new HistoryPoint(point.date(), point.heatScore());
        }
    }

    private static Number jsonNumber(Double value) {
        return value == null ? null : jsonNumber(value.doubleValue());
    }

    private static Number jsonNumber(double value) {
        if (value == Math.rint(value) && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) {
            return (long) value;
        }
        return value;
    }
}
