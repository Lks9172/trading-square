package io.macrosquare.research.application.service;

import io.macrosquare.research.application.model.CurrentSectorRotationAssessment;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationCandidate;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSector;
import io.macrosquare.research.application.model.ResearchCatalogModels.RotationSummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.Sector;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorDetail;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorScore;
import io.macrosquare.research.application.model.ResearchCatalogModels.SectorSummary;
import io.macrosquare.research.application.model.ResearchCatalogModels.Theme;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeCatalog;
import io.macrosquare.research.application.model.ResearchCatalogModels.ThemeDetail;
import io.macrosquare.research.domain.rotation.SectorRotationItem;
import io.macrosquare.research.domain.rotation.SectorRotationOutlookBucket;
import io.macrosquare.research.domain.rotation.SectorLeadershipConfirmationEvidence;
import io.macrosquare.research.domain.rotation.SectorLeadershipConfirmationPolicy;

import java.util.List;

/** Read-model overlay; no JSON or HTTP type crosses into the application layer. */
final class CurrentResearchCatalogOverlay {

    private static final SectorLeadershipConfirmationPolicy CONFIRMATION_POLICY =
            new SectorLeadershipConfirmationPolicy();
    private CurrentResearchCatalogOverlay() {
    }

    static SectorCatalog sectors(
            SectorCatalog source,
            CurrentSectorRotationAssessment assessment
    ) {
        var sectors = source.sectors().stream().map(sector -> {
            var profile = assessment.profiles().get(sector.sectorKey());
            if (profile == null) {
                return new Sector(
                        sector.id(), sector.label(), sector.description(), sector.sectorKey(),
                        sector.tickers(), unavailableSummary(sector.sectorSummary()), null,
                        sector.densitySummary(), sector.relatedThemes());
            }
            return new Sector(
                    sector.id(), sector.label(), sector.description(), sector.sectorKey(),
                    sector.tickers(), summary(sector.sectorSummary(), profile), rotation(profile),
                    sector.densitySummary(), sector.relatedThemes());
        }).toList();
        return new SectorCatalog(sectors, summary(assessment));
    }

    static ThemeCatalog themes(
            ThemeCatalog source,
            CurrentSectorRotationAssessment assessment
    ) {
        var themes = source.themes().stream().map(theme -> {
            var profile = theme.sectorKeys().stream()
                    .map(assessment.profiles()::get)
                    .filter(java.util.Objects::nonNull)
                    .max(java.util.Comparator.comparingInt(value -> value.rotation().rotationScore()))
                    .orElse(null);
            return new Theme(
                    theme.id(), theme.theme(), theme.description(), theme.tickers(), theme.sectorKeys(),
                    profile == null
                            ? unavailableSummary(theme.sectorSummary())
                            : summary(theme.sectorSummary(), profile));
        }).toList();
        return new ThemeCatalog(themes);
    }

    static ThemeDetail theme(
            ThemeDetail source,
            CurrentSectorRotationAssessment assessment
    ) {
        var scores = source.sectorScores().stream()
                .map(score -> score(score, assessment.profiles().get(score.key())))
                .toList();
        var top = scores.stream().max(java.util.Comparator.comparingInt(
                value -> value.rotationScore() == null ? -1 : value.rotationScore())).orElse(null);
        return new ThemeDetail(
                source.theme(), source.items(), scores, summary(source.sectorSummary(), top),
                source.sortKey(), source.companySortKey());
    }

    static SectorDetail sector(
            SectorDetail source,
            CurrentSectorRotationAssessment assessment
    ) {
        var scores = source.sectorScores().stream()
                .map(score -> score(score, assessment.profiles().get(score.key())))
                .toList();
        var profile = assessment.profiles().get(source.sector().sectorKey());
        return new SectorDetail(
                source.sector(), source.sortKey(), source.relatedThemes(), scores,
                profile == null ? unavailableSummary(source.sectorSummary()) : summary(source.sectorSummary(), profile),
                profile == null ? null : rotation(profile),
                summary(assessment), source.densitySummary(), source.items());
    }

    private static SectorSummary summary(
            SectorSummary source,
            CurrentSectorRotationAssessment.CurrentSectorProfile profile
    ) {
        if (source == null) return null;
        return new SectorSummary(
                source.averageBuyScore(), source.averageBottomScore(),
                source.averageBottomFailureRiskScore(), source.averageVolumeConfirmationScore(),
                source.averageAppealScore(), source.averageCrowdingScore(), source.averageQualityScore(),
                profile.rotation().rotationScore(), score(source.topSector(), profile));
    }

    private static SectorSummary summary(SectorSummary source, SectorScore top) {
        if (source == null) return null;
        var scores = top == null ? source.averageRotationScore() : top.rotationScore();
        return new SectorSummary(
                source.averageBuyScore(), source.averageBottomScore(),
                source.averageBottomFailureRiskScore(), source.averageVolumeConfirmationScore(),
                source.averageAppealScore(), source.averageCrowdingScore(), source.averageQualityScore(),
                scores, top == null ? source.topSector() : top);
    }

    private static SectorScore score(
            SectorScore source,
            CurrentSectorRotationAssessment.CurrentSectorProfile profile
    ) {
        if (source == null) return null;
        if (profile == null) return unavailableScore(source);
        var item = profile.rotation();
        return new SectorScore(
                source.key(), source.label(), source.classification(),
                profile.shortTermRelativeStrength(), source.qualityScore(), source.policySupport(),
                source.structuralDemand(), source.supplyTightness(), source.marketConcentration(),
                source.appealScore(), source.crowdingScore(), source.buyScore(), source.buyLabel(),
                source.stance(), item.rotationScore(), item.state().name(),
                item.rotationLabel().displayName(), item.reasons(), source.bottomState(),
                source.bottomScore(), source.bottomFailureRiskScore(), source.actionLabel(),
                source.failureSummary(), source.averageVolumeConfirmationScore(),
                source.averageVolumeConfirmationScorePresent(), source.buyScoreDelta7d(),
                source.buyScoreDelta30d(), source.buyScoreTrend(), source.trendPresent());
    }

    private static SectorSummary unavailableSummary(SectorSummary source) {
        if (source == null) return null;
        return new SectorSummary(
                source.averageBuyScore(), source.averageBottomScore(),
                source.averageBottomFailureRiskScore(), source.averageVolumeConfirmationScore(),
                source.averageAppealScore(), source.averageCrowdingScore(), source.averageQualityScore(),
                null, unavailableScore(source.topSector()));
    }

    private static SectorScore unavailableScore(SectorScore source) {
        if (source == null) return null;
        return new SectorScore(
                source.key(), source.label(), source.classification(), null,
                source.qualityScore(), source.policySupport(), source.structuralDemand(),
                source.supplyTightness(), source.marketConcentration(), source.appealScore(),
                source.crowdingScore(), source.buyScore(), source.buyLabel(), source.stance(),
                null, "UNAVAILABLE", "현재 데이터 부족",
                List.of("현재 단기·중기 상대강도 입력이 모두 확인되지 않아 순환 순위에서 제외했습니다."),
                source.bottomState(), source.bottomScore(), source.bottomFailureRiskScore(),
                source.actionLabel(), source.failureSummary(), source.averageVolumeConfirmationScore(),
                source.averageVolumeConfirmationScorePresent(), source.buyScoreDelta7d(),
                source.buyScoreDelta30d(), source.buyScoreTrend(), source.trendPresent());
    }

    private static RotationSector rotation(
            CurrentSectorRotationAssessment.CurrentSectorProfile profile
    ) {
        var item = profile.rotation();
        var revision = profile.currentRevisionBreadth();
        var fundFlow = profile.currentFundFlow();
        var priceBreadth = profile.currentPriceBreadth();
        return new RotationSector(
                item.key(), item.label(), item.classification().name().toLowerCase(java.util.Locale.ROOT),
                item.rotationScore(), item.macroFitScore(), item.relativeStrengthScore(),
                item.fundamentalScore(), item.valuationScore(),
                item.earningsRevisionScore(),
                revision == null ? null : revision.latestObservedOn().toString(),
                revision == null ? null : revision.coveragePct(),
                revision == null ? null : revision.revisedUpPct(),
                revision == null ? null : revision.revisedDownPct(),
                item.flowScore(),
                fundFlow == null ? null : fundFlow.observedOn().toString(),
                fundFlow == null ? null : fundFlow.flow1dUsd(),
                fundFlow == null ? null : fundFlow.flow5dUsd(),
                fundFlow == null ? null : fundFlow.flow20dUsd(),
                fundFlow == null ? null : fundFlow.flow5dPct(),
                fundFlow == null ? null : fundFlow.flow20dPct(),
                priceBreadth == null ? null : priceBreadth.score(),
                priceBreadth == null ? null : priceBreadth.latestObservedOn().toString(),
                priceBreadth == null ? null : priceBreadth.coveragePct(),
                priceBreadth == null ? null : priceBreadth.aboveMa20Pct(),
                priceBreadth == null ? null : priceBreadth.aboveMa50Pct(),
                priceBreadth == null ? null : priceBreadth.aboveMa200Pct(),
                item.crowdingReliefScore(), item.state().name(), item.rotationLabel().displayName(),
                item.expectedLeadershipWindow().code(), item.expectedLeadershipMessage(), item.reasons());
    }

    private static RotationSummary summary(CurrentSectorRotationAssessment assessment) {
        var view = assessment.rotation();
        var regimeScores = new java.util.LinkedHashMap<String, Integer>();
        view.regimeScores().forEach((key, value) -> regimeScores.put(key.name(), value));
        return new RotationSummary(
                view.regime().name(), view.confidence(), java.util.Map.copyOf(regimeScores),
                view.summary(), view.favoredNext(), view.fadingNext(),
                candidates(view.currentLeaders(), assessment), candidates(view.nextCandidates(), assessment),
                candidates(view.secondaryCandidates(), assessment), candidates(view.fadingCandidates(), assessment),
                assessment.calculatedAt(), true,
                "현재 주도=" + (assessment.totalReturnCoverage() == assessment.universeSize()
                        ? "최근 1개월 제외 6·12개월 배당 반영 ETF/SPY 상대 모멘텀·252일 변동성 조정"
                        : "총수익률 " + assessment.totalReturnCoverage() + "/" + assessment.universeSize()
                                + " · 미수집 구간 가격 상대강도 fallback")
                        + ", 다음 후보=연속 거시 적합도(파생 label·금융여건 중복 제거)+상대강도"
                        + " + 저빈도 품질·밸류 reference"
                        + " + 날짜·커버리지 확인된 구성종목 EPS revision breadth"
                        + " + State Street 공식 ETF 생성·환매 흐름"
                        + " + 추적 구성종목 가격 breadth"
                        + " · 주도 전환 확인 체크리스트");
    }

    private static List<RotationCandidate> candidates(
            List<SectorRotationOutlookBucket> source,
            CurrentSectorRotationAssessment assessment
    ) {
        return source.stream().map(value -> {
            var profile = assessment.profiles().get(value.sectorKey());
            if (profile == null) {
                return new RotationCandidate(
                        value.label(), value.sectorKey(), value.rotationScore(), value.state().name(),
                        value.rotationLabel().displayName(), value.expectedLeadershipWindow().code(),
                        value.expectedLeadershipMessage(), value.note());
            }
            var item = profile.rotation();
            var confirmation = CONFIRMATION_POLICY.evaluate(new SectorLeadershipConfirmationEvidence(
                    item.rotationScore(), item.macroFitScore(), item.relativeStrengthScore(),
                    profile.shortTermRelativeStrength(), profile.mediumTermRelativeStrength(),
                    profile.currentRevisionBreadth() == null
                            ? null : profile.currentRevisionBreadth().score(),
                    independentFlowScore(profile), item.crowdingReliefScore(),
                    item.state()));
            return new RotationCandidate(
                    value.label(), value.sectorKey(), value.rotationScore(), value.state().name(),
                    value.rotationLabel().displayName(), value.expectedLeadershipWindow().code(),
                    value.expectedLeadershipMessage(), value.note(),
                    confirmation.state().name(), confirmation.score(),
                    confirmation.evidenceCoveragePct(), confirmation.label(),
                    confirmation.reasons(), confirmation.invalidationSignals());
        }).toList();
    }

    private static Integer independentFlowScore(
            CurrentSectorRotationAssessment.CurrentSectorProfile profile
    ) {
        return profile.currentFundFlow() == null ? null : profile.currentFundFlow().score();
    }
}
