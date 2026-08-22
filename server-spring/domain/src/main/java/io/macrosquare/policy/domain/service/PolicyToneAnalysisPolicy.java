package io.macrosquare.policy.domain.service;

import io.macrosquare.policy.domain.model.PolicyDirection;
import io.macrosquare.policy.domain.model.PolicyDocument;
import io.macrosquare.policy.domain.model.PolicyDocumentAnalysis;
import io.macrosquare.policy.domain.model.PolicyDocumentType;
import io.macrosquare.policy.domain.model.PolicyEvidence;
import io.macrosquare.policy.domain.model.PolicyIntelligenceSnapshot;
import io.macrosquare.policy.domain.model.PolicyTone;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Transparent phrase lexicon policy; confidence measures evidence coverage, not market forecast odds. */
public final class PolicyToneAnalysisPolicy {

    private static final List<Phrase> PHRASES = phrases();

    public PolicyDocumentAnalysis analyze(PolicyDocument document) {
        var normalizedText = normalize(document.text());
        var normalized = normalizedText.value();
        var evidence = new ArrayList<PolicyEvidence>();
        var occupied = new ArrayList<Range>();
        for (var phrase : PHRASES) {
            var from = 0;
            while (from < normalized.length()) {
                var index = normalized.indexOf(phrase.text(), from);
                if (index < 0) break;
                var end = index + phrase.text().length();
                if (!overlaps(occupied, index, end) && !negated(normalized, index)) {
                    occupied.add(new Range(index, end));
                    evidence.add(new PolicyEvidence(
                            phrase.text(), phrase.direction(), phrase.weight(),
                            excerpt(document.text(), normalizedText.sourceIndex(index))));
                }
                from = end;
            }
        }
        var dovish = evidence.stream().filter(value -> value.direction() == PolicyDirection.DOVISH)
                .mapToInt(PolicyEvidence::weight).sum();
        var hawkish = evidence.stream().filter(value -> value.direction() == PolicyDirection.HAWKISH)
                .mapToInt(PolicyEvidence::weight).sum();
        var total = dovish + hawkish;
        var net = dovish - hawkish;
        var coverageScale = Math.min(1.0, total / 10.0);
        var score = total == 0 ? 0 : clampSigned((int) Math.round((net * 100.0 / total) * coverageScale));
        var mixed = dovish >= 3 && hawkish >= 3 && Math.abs(net) <= Math.max(2, total * 0.25);
        var tone = mixed ? PolicyTone.MIXED : score >= 15 ? PolicyTone.DOVISH : score <= -15
                ? PolicyTone.HAWKISH : PolicyTone.NEUTRAL;
        var relevance = switch (document.type()) {
            case FOMC_STATEMENT -> 25;
            case FOMC_MINUTES -> 22;
            case ECONOMIC_PROJECTIONS -> 18;
            case DISCOUNT_RATE_MINUTES -> 15;
            case TREASURY_RELEASE -> 12;
            case TARIFF_ACTION -> 20;
            case OTHER -> 8;
        };
        var clarity = total == 0 ? 0 : (int) Math.round(Math.abs(net) * 25.0 / total);
        var confidence = Math.min(100, 10 + relevance + Math.min(40, total * 4) + clarity);
        return new PolicyDocumentAnalysis(
                document, tone, score, confidence, dovish, hawkish,
                evidence.stream().sorted(Comparator.comparingInt(PolicyEvidence::weight).reversed())
                        .limit(8).toList(),
                summary(tone, score, confidence)
        );
    }

    public PolicyIntelligenceSnapshot aggregate(List<PolicyDocumentAnalysis> analyses, Instant now) {
        if (analyses == null || analyses.isEmpty()) {
            return new PolicyIntelligenceSnapshot(
                    now, PolicyTone.NEUTRAL, 0, 0, 0,
                    "공식 정책 원문 분석 데이터가 아직 수집되지 않았습니다.", List.of(),
                    io.macrosquare.policy.domain.model.PolicyCalibrationSummary.unavailable());
        }
        var ordered = balanced(analyses);
        double weightedScore = 0;
        double totalWeight = 0;
        double weightedConfidence = 0;
        var effectiveDocumentCount = 0;
        for (var analysis : ordered) {
            var weight = documentWeight(analysis.document().type())
                    * recencyWeight(analysis.document().publishedAt(), now)
                    * sourceEvidenceWeight(analysis);
            weightedScore += analysis.toneScore() * weight;
            weightedConfidence += analysis.confidence() * weight;
            totalWeight += weight;
            if (weight > 0) effectiveDocumentCount++;
        }
        var score = totalWeight == 0 ? 0 : clampSigned((int) Math.round(weightedScore / totalWeight));
        var confidence = totalWeight == 0 ? 0 : Math.min(100,
                (int) Math.round(weightedConfidence / totalWeight
                        * Math.min(1, effectiveDocumentCount / 3.0)));
        var hasDovish = ordered.stream().anyMatch(value -> value.tone() == PolicyTone.DOVISH);
        var hasHawkish = ordered.stream().anyMatch(value -> value.tone() == PolicyTone.HAWKISH);
        var tone = hasDovish && hasHawkish && Math.abs(score) < 20 ? PolicyTone.MIXED
                : score >= 15 ? PolicyTone.DOVISH : score <= -15 ? PolicyTone.HAWKISH : PolicyTone.NEUTRAL;
        return new PolicyIntelligenceSnapshot(
                ordered.getFirst().document().publishedAt(), tone, score, confidence, ordered.size(),
                aggregateSummary(tone, score, confidence), ordered,
                io.macrosquare.policy.domain.model.PolicyCalibrationSummary.unavailable());
    }

    private static boolean negated(String text, int index) {
        var start = Math.max(0, index - 24);
        var prefix = text.substring(start, index);
        return prefix.matches(".*\\b(not|no longer|unlikely to)\\s+$");
    }

    private static List<PolicyDocumentAnalysis> balanced(List<PolicyDocumentAnalysis> analyses) {
        var sorted = analyses.stream()
                .sorted(Comparator.comparing((PolicyDocumentAnalysis value) -> value.document().publishedAt()).reversed())
                .toList();
        var counts = new java.util.LinkedHashMap<String, Integer>();
        var result = new ArrayList<PolicyDocumentAnalysis>();
        for (var value : sorted) {
            var source = value.document().source();
            var limit = "Federal Reserve".equals(source) ? 8 : 3;
            if (counts.getOrDefault(source, 0) >= limit) continue;
            counts.merge(source, 1, Integer::sum);
            result.add(value);
            if (result.size() == 12) break;
        }
        return List.copyOf(result);
    }

    private static boolean overlaps(List<Range> ranges, int start, int end) {
        return ranges.stream().anyMatch(value -> start < value.end() && end > value.start());
    }

    private static String excerpt(String original, int normalizedIndex) {
        if (original.isBlank()) return "";
        var index = Math.min(original.length() - 1, Math.max(0, normalizedIndex));
        var start = Math.max(0, Math.max(original.lastIndexOf('.', index), original.lastIndexOf('\n', index)) + 1);
        var endPeriod = original.indexOf('.', index);
        var endLine = original.indexOf('\n', index);
        var end = endPeriod < 0 ? original.length() : endPeriod + 1;
        if (endLine >= 0) end = Math.min(end, endLine);
        var value = original.substring(start, Math.max(start, end)).trim().replaceAll("\\s+", " ");
        return value.length() <= 300 ? value : value.substring(0, 297) + "...";
    }

    private static NormalizedText normalize(String value) {
        if (value == null || value.isBlank()) return new NormalizedText("", new int[0]);
        var normalized = new StringBuilder(value.length());
        var sourceIndexes = new ArrayList<Integer>(value.length());
        var pendingSpace = false;
        var pendingSpaceIndex = 0;
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (Character.isWhitespace(character)) {
                if (normalized.length() > 0 && !pendingSpace) pendingSpaceIndex = index;
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                sourceIndexes.add(pendingSpaceIndex);
                pendingSpace = false;
            }
            var lowered = Character.toLowerCase(character);
            normalized.append(lowered == '–' || lowered == '—' ? '-' : lowered);
            sourceIndexes.add(index);
        }
        var indexes = new int[sourceIndexes.size()];
        for (var index = 0; index < sourceIndexes.size(); index++) indexes[index] = sourceIndexes.get(index);
        return new NormalizedText(normalized.toString(), indexes);
    }

    private static double documentWeight(PolicyDocumentType type) {
        return switch (type) {
            case FOMC_STATEMENT -> 1.0;
            case FOMC_MINUTES -> 0.85;
            case ECONOMIC_PROJECTIONS -> 0.70;
            case DISCOUNT_RATE_MINUTES -> 0.55;
            case TREASURY_RELEASE -> 0.40;
            case TARIFF_ACTION -> 0.65;
            case OTHER -> 0.35;
        };
    }

    private static double sourceEvidenceWeight(PolicyDocumentAnalysis analysis) {
        var type = analysis.document().type();
        if (type != PolicyDocumentType.TREASURY_RELEASE && type != PolicyDocumentType.TARIFF_ACTION) return 1;
        var evidenceWeight = analysis.dovishWeight() + analysis.hawkishWeight();
        return evidenceWeight == 0 ? 0 : Math.min(1, evidenceWeight / 5.0);
    }

    private static double recencyWeight(Instant published, Instant now) {
        var days = Math.max(0, Duration.between(published, now).toDays());
        if (days <= 30) return 1.0;
        if (days <= 90) return 0.8;
        if (days <= 180) return 0.55;
        return 0.35;
    }

    private static String summary(PolicyTone tone, int score, int confidence) {
        return "공식 원문 사전 분석은 " + label(tone) + " (" + signed(score)
                + "), 근거 충족도 " + confidence + "%입니다.";
    }

    private static String aggregateSummary(PolicyTone tone, int score, int confidence) {
        return "최근 미국 공식 통화·재정·통상정책 원문 종합은 " + label(tone) + " (" + signed(score)
                + ")입니다. 신뢰도 " + confidence + "%는 문구 근거량과 방향 일치도를 뜻하며 시장 예측 확률이 아닙니다.";
    }

    private static String label(PolicyTone tone) {
        return switch (tone) {
            case DOVISH -> "완화적";
            case HAWKISH -> "긴축적";
            case MIXED -> "혼합";
            case NEUTRAL -> "중립";
        };
    }

    private static String signed(int value) {
        return (value > 0 ? "+" : "") + value;
    }

    private static int clampSigned(int value) {
        return Math.max(-100, Math.min(100, value));
    }

    private static List<Phrase> phrases() {
        var result = new ArrayList<Phrase>();
        add(result, PolicyDirection.DOVISH, 6, "reduce the target range", "lower the target range");
        add(result, PolicyDirection.DOVISH, 5, "downside risks to employment", "rate cuts", "rate cut");
        add(result, PolicyDirection.DOVISH, 4, "labor market conditions have cooled", "inflation has eased", "disinflation");
        add(result, PolicyDirection.DOVISH, 3, "inflation has made progress", "risks are roughly in balance", "risks have moved into balance");
        add(result, PolicyDirection.DOVISH, 1, "support maximum employment", "accommodative policy");
        add(result, PolicyDirection.DOVISH, 5, "reduce tariffs", "lower tariffs", "tariff relief", "suspend tariffs");
        add(result, PolicyDirection.DOVISH, 3, "remove trade barriers", "market liquidity support");
        add(result, PolicyDirection.HAWKISH, 6, "raise the target range", "prepared to raise", "not appropriate to reduce");
        add(result, PolicyDirection.HAWKISH, 5, "inflation remains elevated", "upside risks to inflation", "higher for longer");
        add(result, PolicyDirection.HAWKISH, 4, "restrictive stance", "persistent inflation", "inflation remains too high");
        add(result, PolicyDirection.HAWKISH, 2, "labor market remains solid", "vigilant about inflation");
        add(result, PolicyDirection.HAWKISH, 1, "price stability", "economic activity is expanding at a solid pace");
        add(result, PolicyDirection.HAWKISH, 5, "impose tariffs", "additional tariffs", "increase tariffs");
        add(result, PolicyDirection.HAWKISH, 4, "section 301 action", "section 232 tariffs", "trade restrictions");
        return result.stream().sorted(Comparator.comparingInt((Phrase value) -> value.text().length()).reversed()).toList();
    }

    private static void add(List<Phrase> target, PolicyDirection direction, int weight, String... phrases) {
        for (var phrase : phrases) target.add(new Phrase(phrase, direction, weight));
    }

    private record Phrase(String text, PolicyDirection direction, int weight) {
    }

    private record Range(int start, int end) {
    }

    private record NormalizedText(String value, int[] sourceIndexes) {
        private int sourceIndex(int normalizedIndex) {
            if (sourceIndexes.length == 0) return 0;
            return sourceIndexes[Math.max(0, Math.min(sourceIndexes.length - 1, normalizedIndex))];
        }
    }
}
