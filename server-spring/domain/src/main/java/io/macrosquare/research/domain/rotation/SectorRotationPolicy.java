package io.macrosquare.research.domain.rotation;

import io.macrosquare.research.domain.narrative.NarrativeTheme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.macrosquare.research.domain.rotation.RotationMath.*;


public final class SectorRotationPolicy {

    public static final String METHODOLOGY_VERSION = "CURRENT_SECTOR_ROTATION_COMPOSITE_V3";

    private static final int LEADER_MOMENTUM_MIN = 65;
    private static final int HIGH_CROWDING_MIN = 70;

    private static final Set<String> AI_NARRATIVE_SECTORS = Set.of(
            "SECTOR_XLK", "SECTOR_SOXX", "SECTOR_SMH", "SECTOR_GRID"
    );
    private static final Set<String> DEFENSIVE_HOLD_SECTORS = Set.of(
            "SECTOR_XLV", "SECTOR_XLU", "SECTOR_XLP", "SECTOR_XLRE"
    );

    private final SectorRotationRegimePolicy regimePolicy;

    public SectorRotationPolicy() {
        this(new SectorRotationRegimePolicy());
    }

    SectorRotationPolicy(SectorRotationRegimePolicy regimePolicy) {
        this.regimePolicy = java.util.Objects.requireNonNull(regimePolicy);
    }

    public SectorRotationView evaluate(SectorRotationInput input) {
        var assessment = inferRegime(input.market());
        var items = new ArrayList<SectorRotationItem>();
        for (var sector : input.sectors()) {
            items.add(evaluateSector(
                    sector,
                    input.market(),
                    assessment,
                    input.narrativeHeatScores()
            ));
        }
        items.sort(Comparator.comparingInt(SectorRotationItem::rotationScore).reversed());

        var favoredNext = items.stream()
                .filter(item -> item.state() == SectorRotationState.IMPROVING
                        && item.expectedLeadershipWindow() == SectorRotationHorizon.ONE_TO_THREE_MONTHS)
                .limit(3)
                .map(SectorRotationItem::label)
                .toList();
        var fadingNext = items.stream()
                .filter(item -> item.state() == SectorRotationState.WEAKENING)
                .sorted(Comparator.comparingInt(SectorRotationItem::relativeStrengthScore).reversed())
                .limit(3)
                .map(SectorRotationItem::label)
                .toList();

        var regimeLabel = switch (assessment.regime()) {
            case MID_GROWTH -> "중기 성장";
            case EARLY_CYCLICAL -> "초기 경기민감";
            case LATE_INFLATION -> "후기 인플레";
            case DEFENSIVE -> "방어";
            case RE_ACCELERATION -> "재가속";
        };
        var currentLeaders = currentLeaderBuckets(items);
        var currentLeaderLabels = currentLeaders.stream().map(SectorRotationOutlookBucket::label).toList();
        var currentText = currentLeaderLabels.isEmpty()
                ? "현재 가격 상대강도로 확정된 주도 섹터는 제한적입니다."
                : "현재 가격 상대강도 주도는 " + String.join(", ", currentLeaderLabels) + "입니다.";
        var nextText = favoredNext.isEmpty()
                ? "거시 전환을 확인한 차기 후보도 아직 제한적입니다."
                : "다음 관찰 후보는 " + String.join(", ", favoredNext) + "입니다.";
        var summary = currentText + " 거시 순환은 " + regimeLabel + " 단계(분리도 "
                + assessment.confidence() + ")이며, " + nextText;

        // Product buckets are time-horizon contracts, not another score cut.
        // The previous 68-point split could put a 3~6 month outlook in the
        // "next (1~3m)" list and contradicted the message shown on the same card.
        var nextCandidates = buckets(items, item -> item.state() == SectorRotationState.IMPROVING
                && item.expectedLeadershipWindow() == SectorRotationHorizon.ONE_TO_THREE_MONTHS);
        var secondaryCandidates = buckets(items, item -> item.state() == SectorRotationState.IMPROVING
                && item.expectedLeadershipWindow() == SectorRotationHorizon.THREE_TO_SIX_MONTHS);
        var fadingCandidates = bucketsByRelativeStrength(
                items, item -> item.state() == SectorRotationState.WEAKENING);

        return new SectorRotationView(
                assessment.regime(),
                assessment.confidence(),
                assessment.regimeScores(),
                summary,
                favoredNext,
                fadingNext,
                currentLeaders,
                nextCandidates,
                secondaryCandidates,
                fadingCandidates,
                items
        );
    }

    public RotationRegimeAssessment inferRegime(RotationMarketEvidence market) {
        return regimePolicy.inferRegime(market);
    }

    public Map<SectorRotationRegime, Integer> computeRegimeScores(RotationMarketEvidence market) {
        return regimePolicy.computeRegimeScores(market);
    }

    public int computeMacroFitScore(
            String sectorKey,
            SectorRotationRegime regime,
            RotationMarketEvidence market
    ) {
        return regimePolicy.computeMacroFitScore(sectorKey, regime, market);
    }

    private SectorRotationItem evaluateSector(
            SectorRotationEvidence sector,
            RotationMarketEvidence market,
            RotationRegimeAssessment assessment,
            Map<String, Integer> narrativeHeatScores
    ) {
        var momentum = sector.institutionalMomentumScore() == null
                ? normalizeMomentum(sector.mediumTermRelativeStrength())
                : sector.institutionalMomentumScore();
        var macroFitScore = computeBlendedMacroFitScore(sector.key(), assessment, market);
        var fundamentalScore = rounded(clamp(
                defaultScore(sector.qualityScore()) * 0.55
                        + defaultScore(sector.appealScore()) * 0.25
                        + defaultScore(sector.valuationScore()) * 0.20,
                0,
                100
        ));
        var flowScore = currentFundFlowScore(sector);
        var currentRevisionScore = currentRevisionScore(sector);
        var crowdingReliefScore = rounded(clamp(100 - defaultScore(sector.crowdingScore()), 0, 100));

        var aiHeat = narrativeHeatScores.get("ai-power");
        if (AI_NARRATIVE_SECTORS.contains(sector.key()) && aiHeat != null) {
            fundamentalScore = rounded(clamp(
                    fundamentalScore + clamp((aiHeat - 52) * 0.06, -3, 4),
                    0,
                    100
            ));
        }
        var energyHeat = narrativeHeatScores.get(NarrativeTheme.ENERGY_SUPPLY.id());
        if ("SECTOR_XLE".equals(sector.key()) && energyHeat != null) {
            fundamentalScore = rounded(clamp(
                    fundamentalScore + clamp((energyHeat - 50) * 0.05, -2, 3),
                    0,
                    100
            ));
        }
        var defenseHeat = narrativeHeatScores.get("defense-rearm");
        if ("SECTOR_ITA".equals(sector.key()) && defenseHeat != null) {
            fundamentalScore = rounded(clamp(
                    fundamentalScore + clamp((defenseHeat - 50) * 0.04, -2, 2),
                    0,
                    100
            ));
        }

        var rotationScore = rounded(clamp(
                macroFitScore * 0.3
                        + momentum * 0.28
                        + fundamentalScore * 0.22
                        + defaultScore(currentRevisionScore) * 0.12
                        + crowdingReliefScore * 0.04
                        + defaultScore(flowScore) * 0.04
                        - (Boolean.TRUE.equals(market.overheated())
                        && defaultScore(sector.crowdingScore()) >= HIGH_CROWDING_MIN ? 8 : 0),
                0,
                100
        ));

        var crowding = defaultScore(sector.crowdingScore());
        var mediumTermRelativeStrength = sector.mediumTermRelativeStrength();
        var shortTermRelativeStrength = sector.shortTermRelativeStrength();
        var hasCurrentRelativeStrength = mediumTermRelativeStrength != null
                && shortTermRelativeStrength != null;
        var establishedPriceLeader = hasCurrentRelativeStrength
                && mediumTermRelativeStrength >= 0
                && momentum >= LEADER_MOMENTUM_MIN
                && Boolean.TRUE.equals(sector.absoluteTrendPositive());
        var state = SectorRotationState.LAGGING;
        if (establishedPriceLeader
                && (shortTermRelativeStrength <= -3 || crowding >= HIGH_CROWDING_MIN)) {
            state = SectorRotationState.WEAKENING;
        } else if (establishedPriceLeader) {
            state = SectorRotationState.LEADING;
        } else if (hasCurrentRelativeStrength && rotationScore >= 66 && macroFitScore >= 60
                && (mediumTermRelativeStrength >= -1.5 || shortTermRelativeStrength >= 0)) {
            state = SectorRotationState.IMPROVING;
        } else if (hasCurrentRelativeStrength && rotationScore >= 60 && macroFitScore >= 72
                && shortTermRelativeStrength >= -2 && crowding < 65) {
            state = SectorRotationState.IMPROVING;
        }

        var label = SectorRotationLabel.ROTATION_OUT;
        if (state == SectorRotationState.LEADING) {
            label = SectorRotationLabel.LEADER;
        } else if (state == SectorRotationState.IMPROVING) {
            label = SectorRotationLabel.ROTATION_IN;
        } else if (state == SectorRotationState.WEAKENING) {
            label = SectorRotationLabel.LATE_LEADER;
        } else if (assessment.regime() == SectorRotationRegime.DEFENSIVE
                && DEFENSIVE_HOLD_SECTORS.contains(sector.key())) {
            label = SectorRotationLabel.DEFENSIVE_HOLD;
        }

        var outlook = expectedLeadershipWindow(rotationScore, state, label, momentum, crowdingReliefScore);
        var item = new SectorRotationItem(
                sector.key(),
                sector.label(),
                sector.classification(),
                rotationScore,
                macroFitScore,
                momentum,
                fundamentalScore,
                sector.valuationScore(),
                sector.earningsRevisionScore(),
                flowScore,
                crowdingReliefScore,
                state,
                label,
                outlook.horizon(),
                outlook.message(),
                List.of()
        );
        return item.withReasons(buildReasons(sector, item, assessment.regime()));
    }

    private int computeBlendedMacroFitScore(
            String sectorKey,
            RotationRegimeAssessment assessment,
            RotationMarketEvidence market
    ) {
        var maximum = assessment.regimeScores().values().stream().mapToInt(Integer::intValue)
                .max().orElse(0);
        double weighted = 0;
        double totalWeight = 0;
        for (var entry : assessment.regimeScores().entrySet()) {
            // Regime scores are overlapping likelihood signals, not mutually
            // exclusive probabilities. A bounded softmax prevents a one-point
            // winner from hard-switching every sector's macro score while still
            // allowing a clearly dominant regime to matter.
            var weight = Math.exp((entry.getValue() - maximum) / 12d);
            weighted += computeMacroFitScore(sectorKey, entry.getKey(), market) * weight;
            totalWeight += weight;
        }
        return totalWeight == 0
                ? computeMacroFitScore(sectorKey, assessment.regime(), market)
                : rounded(clamp(weighted / totalWeight, 0, 100));
    }

    private static List<SectorRotationOutlookBucket> buckets(
            List<SectorRotationItem> items,
            java.util.function.Predicate<SectorRotationItem> predicate
    ) {
        return items.stream()
                .filter(predicate)
                .limit(3)
                .map(SectorRotationPolicy::toBucket)
                .toList();
    }

    private static List<SectorRotationOutlookBucket> currentLeaderBuckets(
            List<SectorRotationItem> items
    ) {
        return items.stream()
                .filter(item -> item.state() == SectorRotationState.LEADING
                        || item.state() == SectorRotationState.WEAKENING)
                .sorted(Comparator.comparingInt(SectorRotationItem::relativeStrengthScore).reversed())
                .limit(3)
                .map(SectorRotationPolicy::toBucket)
                .toList();
    }

    private static List<SectorRotationOutlookBucket> bucketsByRelativeStrength(
            List<SectorRotationItem> items,
            java.util.function.Predicate<SectorRotationItem> predicate
    ) {
        return items.stream()
                .filter(predicate)
                .sorted(Comparator.comparingInt(SectorRotationItem::relativeStrengthScore).reversed())
                .limit(3)
                .map(SectorRotationPolicy::toBucket)
                .toList();
    }

    private static SectorRotationOutlookBucket toBucket(SectorRotationItem item) {
        return new SectorRotationOutlookBucket(
                item.label(),
                item.key(),
                item.rotationScore(),
                item.state(),
                item.rotationLabel(),
                item.expectedLeadershipWindow(),
                item.expectedLeadershipMessage(),
                item.reasons().isEmpty() ? "" : item.reasons().getFirst()
        );
    }

    private static List<String> buildReasons(
            SectorRotationEvidence sector,
            SectorRotationItem item,
            SectorRotationRegime regime
    ) {
        var reasons = new ArrayList<String>();
        switch (item.state()) {
            case IMPROVING -> reasons.add("거시 정합과 중기 상대강도가 함께 개선되는 순환 후보입니다.");
            case LEADING -> reasons.add("현재 거시·상대강도 순위가 상위권인 섹터입니다.");
            case WEAKENING -> reasons.add("강했던 섹터지만 과열 또는 후행 피로가 누적되는 구간입니다.");
            case LAGGING -> reasons.add("현 국면 대비 우선순위가 아직 낮습니다.");
        }
        if (sector.mediumTermRelativeStrength() == null) {
            reasons.add("중기 상대강도 근거가 없어 현재 주도·전환 후보로 승격하지 않습니다.");
        }
        if (sector.shortTermRelativeStrength() == null) {
            reasons.add("단기 상대강도 근거가 없어 전환 속도를 판정하지 않습니다.");
        }
        if (item.macroFitScore() >= 78) {
            reasons.add("현재 거시 국면(" + regime + ")과 정합도가 높습니다.");
        }
        if (item.earningsRevisionScore() != null) {
            reasons.add(item.earningsRevisionScore() >= 55
                    ? "구성종목의 최근 EPS 추정 상향 폭이 하향보다 넓습니다."
                    : item.earningsRevisionScore() <= 45
                    ? "구성종목의 최근 EPS 추정 하향 폭이 상향보다 넓습니다."
                    : "구성종목 EPS 추정 방향은 대체로 중립입니다.");
        }
        if (sector.priceBreadthScore() != null) {
            reasons.add(sector.priceBreadthScore() >= 60
                    ? "구성종목 가격 breadth가 넓어 상대강도 확산이 확인됩니다."
                    : sector.priceBreadthScore() <= 40
                    ? "구성종목 가격 breadth가 좁아 소수 종목 주도 가능성을 경계합니다."
                    : "구성종목 가격 breadth는 중립 구간입니다.");
        }
        if (defaultScore(sector.buyScore()) >= 70) {
            reasons.add("B 점수 " + defaultScore(sector.buyScore()) + "로 섹터 체력은 양호합니다.");
        }
        if (defaultScore(sector.crowdingScore()) >= HIGH_CROWDING_MIN) {
            reasons.add("과열 " + defaultScore(sector.crowdingScore()) + "로 추격보다 눌림 확인이 우선입니다.");
        } else if (defaultScore(sector.crowdingScore()) <= 45) {
            reasons.add("과열 " + defaultScore(sector.crowdingScore()) + "로 혼잡도 부담은 낮은 편입니다.");
        }
        var mediumTerm = sector.mediumTermRelativeStrength();
        if (mediumTerm != null && mediumTerm > 0) {
            reasons.add("중기 상대강도 " + fixedOne(mediumTerm) + "%가 플러스입니다.");
        } else if (mediumTerm != null && mediumTerm < 0) {
            reasons.add("중기 상대강도 " + fixedOne(mediumTerm) + "%로 아직 약합니다.");
        }
        var shortTerm = sector.shortTermRelativeStrength();
        if (shortTerm != null && shortTerm >= 4) {
            reasons.add("단기 1개월 탄력 " + fixedOne(shortTerm) + "%로 추세 확인이 붙고 있습니다.");
        } else if (shortTerm != null && shortTerm <= -4) {
            reasons.add("단기 1개월 탄력 " + fixedOne(shortTerm) + "%로 아직 재가속 확인이 부족합니다.");
        }
        if (Boolean.FALSE.equals(sector.absoluteTrendPositive())) {
            reasons.add("절대 추세가 아직 200일 기준선을 회복하지 못해 상대 순위만으로 주도로 확정하지 않습니다.");
        } else if (sector.absoluteTrendPositive() == null) {
            reasons.add("절대 추세 근거가 없어 상대 순위만으로 주도로 확정하지 않습니다.");
        }
        return reasons.stream().limit(3).toList();
    }

    private static Integer currentRevisionScore(SectorRotationEvidence sector) {
        if (sector.earningsRevisionObservedOn() == null
                || sector.earningsRevisionCoveragePct() == null
                || sector.earningsRevisionCoveragePct()
                < SectorEarningsRevisionBreadthPolicy.MIN_COVERAGE_PCT) {
            return null;
        }
        return sector.earningsRevisionScore();
    }

    private static Integer currentFundFlowScore(SectorRotationEvidence sector) {
        return sector.fundFlowObservedOn() == null ? null : sector.fundFlowScore();
    }

    private static LeadershipOutlook expectedLeadershipWindow(
            int rotationScore,
            SectorRotationState state,
            SectorRotationLabel label,
            int relativeStrengthScore,
            int crowdingReliefScore
    ) {
        if (state == SectorRotationState.LEADING) {
            if (relativeStrengthScore >= 80) {
                return new LeadershipOutlook(
                        SectorRotationHorizon.NOW,
                        "현재 순환 상위 구간 — 지금~3개월 내 실제 리더십 확인·유지 여부를 보는 단계"
                );
            }
            return new LeadershipOutlook(
                    SectorRotationHorizon.ONE_TO_THREE_MONTHS,
                    "주도 초입 — 1~3개월 내 리더십 고착 여부를 확인"
            );
        }
        if (state == SectorRotationState.IMPROVING) {
            if (rotationScore >= 70 && relativeStrengthScore >= 55) {
                return new LeadershipOutlook(
                        SectorRotationHorizon.ONE_TO_THREE_MONTHS,
                        "1~3개월 관찰 우선순위가 높은 전환 후보 — 주도 편입 확정은 아님"
                );
            }
            if (rotationScore >= 63) {
                return new LeadershipOutlook(
                        SectorRotationHorizon.THREE_TO_SIX_MONTHS,
                        "3~6개월 관찰 대상인 2차 확산 후보 — 시점 예측은 불확실"
                );
            }
            return new LeadershipOutlook(
                    SectorRotationHorizon.SIX_MONTHS_PLUS,
                    "주도 전환까지는 아직 시간이 더 필요한 후보"
            );
        }
        if (label == SectorRotationLabel.LATE_LEADER || state == SectorRotationState.WEAKENING) {
            return new LeadershipOutlook(
                    SectorRotationHorizon.UNCLEAR,
                    "과열/후행 피로 구간 — 다음 주도보다는 약화 위험을 먼저 봐야 함"
            );
        }
        if (rotationScore >= 58 && crowdingReliefScore >= 70) {
            return new LeadershipOutlook(
                    SectorRotationHorizon.SIX_MONTHS_PLUS,
                    "당장 주도는 아니지만 다음 사이클 대기 후보"
            );
        }
        return new LeadershipOutlook(SectorRotationHorizon.UNCLEAR, "현재는 주도 전환 가시성이 낮음");
    }

    private static int normalizeMomentum(Double score) {
        if (score == null || Double.isNaN(score)) return 50;
        return rounded(clamp(50 + score * 3, 5, 95));
    }

    private static int defaultScore(Integer value) {
        return value == null ? 50 : value;
    }

    private static String fixedOne(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record LeadershipOutlook(SectorRotationHorizon horizon, String message) {
    }
}
