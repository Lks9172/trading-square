package io.macrosquare.research.domain.bottleneck;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class BottleneckScoringPolicy {

    private static final Set<String> SWITCHING_COST_TAGS = Set.of(
            "eda", "euv", "qualification", "liquid-cooling", "mission-critical"
    );
    private static final List<KeywordRule> KEYWORD_RULES = List.of(
            rule("supply-constraint", 1.4, 3, "공급 제약/타이트 서플라이 언급",
                    "supply constraint", "supply constrained", "capacity constraint", "tight supply", "scarce", "constrained supply"),
            rule("lead-time", 1.1, 3, "리드타임 장기화 언급",
                    "lead time", "long lead", "extended lead", "lead times"),
            rule("backlog", 1.0, 3, "수주잔고/백로그 언급",
                    "backlog", "book-to-bill", "order book", "bookings"),
            rule("pricing-power", 0.9, 3, "가격 전가력/가격 인상 언급",
                    "pricing power", "price increase", "favorable pricing", "price realization", "price discipline"),
            rule("sole-source", 1.5, 2, "대체 어려운 공급자 포지션 언급",
                    "sole source", "single source", "only supplier", "mission critical", "unique capability"),
            rule("capex-linkage", 1.0, 4, "대형 CAPEX/정책 수요 연동 언급",
                    "data center", "ai infrastructure", "grid modernization", "capacity expansion", "fab expansion", "rearm", "electrification"),
            rule("qualification-lockin", 1.1, 3, "고객 인증/설치기반 락인 언급",
                    "qualification", "qualified supplier", "design win", "installed base", "certification"),
            rule("yield-or-process", 0.8, 3, "수율/공정 제어 중요도 언급",
                    "yield", "process control", "metrology", "throughput")
    );
    private static final List<Pattern> EXCERPT_PATTERNS = List.of(
            Pattern.compile("[^.]{0,60}(capacity constraint|capacity constrained|supply constrained|lead time|backlog|pricing power|sole source|installed base|qualification)[^.]{0,90}", Pattern.CASE_INSENSITIVE),
            Pattern.compile("[^.]{0,60}(yield|process control|metrology|design win|mission critical)[^.]{0,90}", Pattern.CASE_INSENSITIVE)
    );

    public BottleneckCandidateScore evaluate(BottleneckCandidate candidate, BottleneckEvidence evidence) {
        if (!candidate.ticker().equals(evidence.ticker())) {
            throw new IllegalArgumentException("candidate and evidence ticker must match");
        }

        var text = computeTextSignal(evidence.corpus());
        var quality = computeQuality(evidence);
        var priors = candidate.priors();
        var componentScores = new BottleneckComponentScores(
                text.score(),
                quality.score(),
                defaultScore(priors.concentration()),
                defaultScore(priors.supplyTightness()),
                defaultScore(priors.capexLinkage()),
                switchingCost(candidate)
        );
        var total = (
                componentScores.textSignal() * 0.24
                        + componentScores.quality() * 0.22
                        + componentScores.concentration() * 0.16
                        + componentScores.supplyTightness() * 0.16
                        + componentScores.capexLinkage() * 0.10
                        + componentScores.switchingCost() * 0.12
        ) * 10;
        var score = (int) Math.round(clamp(total, 0, 100));

        var reasons = new ArrayList<String>();
        reasons.add(candidate.role());
        candidate.tags().stream().limit(2).map(tag -> "tag:" + tag).forEach(reasons::add);
        reasons.addAll(text.reasons());
        reasons.addAll(quality.reasons());

        var excerpts = extractEvidenceExcerpts(evidence.corpus());
        var textMatches = text.matches().stream()
                .limit(5)
                .map(match -> new BottleneckTextMatch(
                        match.label(), match.count(), match.score(), match.reason(), excerpts
                ))
                .toList();

        return new BottleneckCandidateScore(
                evidence.ticker(),
                evidence.company(),
                candidate.role(),
                candidate.theme(),
                score,
                conviction(score),
                componentScores,
                textMatches,
                reasons.stream().limit(7).toList(),
                new BottleneckMetrics(
                        evidence.revenueGrowthYoY(),
                        evidence.operatingMargin(),
                        evidence.evToSales(),
                        evidence.totalScore()
                )
        );
    }

    private static TextSignal computeTextSignal(String corpus) {
        var reasons = new ArrayList<String>();
        var matches = new ArrayList<BottleneckTextMatch>();
        var score = 0.0;
        for (var rule : KEYWORD_RULES) {
            var count = countMatches(corpus, rule.patterns());
            if (count == 0) continue;
            var applied = Math.min(count, rule.cap());
            var weighted = applied * rule.score();
            score += weighted;
            reasons.add(rule.reason() + " ×" + applied);
            matches.add(new BottleneckTextMatch(
                    rule.label(), applied, roundOneDecimal(weighted), rule.reason(), List.of()
            ));
        }
        matches.sort(Comparator.comparingDouble(BottleneckTextMatch::score).reversed()
                .thenComparing(Comparator.comparingInt(BottleneckTextMatch::count).reversed()));
        return new TextSignal(clamp(score, 0, 10), reasons, matches);
    }

    private static QualitySignal computeQuality(BottleneckEvidence evidence) {
        var reasons = new ArrayList<String>();
        var score = 0.0;
        if (evidence.totalScore() >= 75) {
            score += 4.5;
            reasons.add("기초 체력 점수 " + evidence.totalScore() + "/100");
        } else if (evidence.totalScore() >= 65) {
            score += 3.5;
            reasons.add("종합 점수 " + evidence.totalScore() + "/100");
        } else if (evidence.totalScore() >= 55) {
            score += 2.5;
            reasons.add("점수 중립 이상 " + evidence.totalScore() + "/100");
        }
        if (evidence.operatingMargin() != null) {
            if (evidence.operatingMargin() >= 25) {
                score += 2.5;
                reasons.add("영업이익률 " + fixedOne(evidence.operatingMargin()) + "%");
            } else if (evidence.operatingMargin() >= 15) {
                score += 1.5;
                reasons.add("영업이익률 방어 " + fixedOne(evidence.operatingMargin()) + "%");
            }
        }
        if (evidence.revenueGrowthYoY() != null) {
            if (evidence.revenueGrowthYoY() >= 20) {
                score += 2;
                reasons.add("매출 성장 " + fixedOne(evidence.revenueGrowthYoY()) + "%");
            } else if (evidence.revenueGrowthYoY() >= 8) {
                score += 1;
                reasons.add("매출 증가 " + fixedOne(evidence.revenueGrowthYoY()) + "%");
            }
        }
        return new QualitySignal(clamp(score, 0, 10), reasons);
    }

    private static int countMatches(String corpus, List<Pattern> patterns) {
        var total = 0;
        for (var pattern : patterns) {
            var matcher = pattern.matcher(corpus);
            while (matcher.find()) total++;
        }
        return total;
    }

    private static List<String> extractEvidenceExcerpts(String corpus) {
        var excerpts = new ArrayList<String>();
        for (var pattern : EXCERPT_PATTERNS) {
            var matcher = pattern.matcher(corpus);
            if (matcher.find()) {
                var match = matcher.group().trim();
                if (match.length() > 180) match = match.substring(0, 180);
                if (!match.isEmpty() && !excerpts.contains(match)) excerpts.add(match);
            }
            if (excerpts.size() >= 3) break;
        }
        return List.copyOf(excerpts);
    }

    private static double switchingCost(BottleneckCandidate candidate) {
        var base = defaultScore(candidate.priors().switchingCost());
        var hasBonus = candidate.tags().stream().anyMatch(SWITCHING_COST_TAGS::contains);
        return clamp(base + (hasBonus ? 1 : 0), 0, 10);
    }

    private static BottleneckConviction conviction(int score) {
        if (score >= 70) return BottleneckConviction.CORE;
        if (score >= 55) return BottleneckConviction.STRONG;
        return BottleneckConviction.WATCH;
    }

    private static double defaultScore(Double value) {
        return value == null ? 5 : value;
    }

    private static KeywordRule rule(
            String label,
            double score,
            int cap,
            String reason,
            String... expressions
    ) {
        var patterns = java.util.Arrays.stream(expressions)
                .map(expression -> Pattern.compile(expression, Pattern.CASE_INSENSITIVE))
                .toList();
        return new KeywordRule(label, score, cap, reason, patterns);
    }

    private static String fixedOne(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static double roundOneDecimal(double value) {
        return Math.round(value * 10) / 10.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private record KeywordRule(String label, double score, int cap, String reason, List<Pattern> patterns) {
    }

    private record TextSignal(double score, List<String> reasons, List<BottleneckTextMatch> matches) {
    }

    private record QualitySignal(double score, List<String> reasons) {
    }
}
