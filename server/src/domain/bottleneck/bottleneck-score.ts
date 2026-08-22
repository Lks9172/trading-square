import { CompanyResearchResponse } from '../../types/fundamentals';
import { BottleneckCandidateDefinition, BottleneckCandidateScore, BottleneckComponentScores, BottleneckTextMatch } from '../../types/bottleneck';
import { BOTTLENECK_KEYWORD_RULES } from './keyword-rules';
function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}
function collectCorpus(research: CompanyResearchResponse): string {
  return [
    ...research.highlights,
    ...research.filings.flatMap((item) => [item.summary ?? '', ...(item.guidanceSummary?.evidence ?? []), item.primaryDocDescription ?? '']),
    ...research.irMaterials.flatMap((item) => [item.title, item.summary ?? '']),
    ...research.score.reasons,
    ...research.score.quality.reasons,
    ...research.score.growth.reasons,
  ].join('\n');
}
function countMatches(corpus: string, patterns: RegExp[]): number {
  let total = 0;
  for (const pattern of patterns) {
    const matches = corpus.match(pattern);
    total += matches ? matches.length : 0;
  }
  return total;
}
function computeTextSignalScore(corpus: string): { score: number; reasons: string[]; matches: BottleneckTextMatch[] } {
  const reasons: string[] = [];
  const matches: BottleneckTextMatch[] = [];
  let score = 0;
  for (const rule of BOTTLENECK_KEYWORD_RULES) {
    const count = countMatches(corpus, rule.patterns);
    if (!count) continue;
    const applied = Math.min(count, rule.cap ?? count);
    const weighted = applied * rule.score;
    score += weighted;
    reasons.push(`${rule.reason} ×${applied}`);
    matches.push({ label: rule.label, count: applied, score: Number(weighted.toFixed(1)), reason: rule.reason });
  }
  matches.sort((a, b) => b.score - a.score || b.count - a.count);
  return { score: clamp(score, 0, 10), reasons, matches };
}
function computeQualityScore(research: CompanyResearchResponse): { score: number; reasons: string[] } {
  const reasons: string[] = [];
  let score = 0;
  const total = research.score.totalScore;
  if (total >= 75) {
    score += 4.5;
    reasons.push(`기초 체력 점수 ${total}/100`);
  } else if (total >= 65) {
    score += 3.5;
    reasons.push(`종합 점수 ${total}/100`);
  } else if (total >= 55) {
    score += 2.5;
    reasons.push(`점수 중립 이상 ${total}/100`);
  }
  const margin = research.financials.operatingMargin;
  if (margin !== null) {
    if (margin >= 25) {
      score += 2.5;
      reasons.push(`영업이익률 ${margin.toFixed(1)}%`);
    } else if (margin >= 15) {
      score += 1.5;
      reasons.push(`영업이익률 방어 ${margin.toFixed(1)}%`);
    }
  }
  const growth = research.financials.revenueGrowthYoY;
  if (growth !== null) {
    if (growth >= 20) {
      score += 2;
      reasons.push(`매출 성장 ${growth.toFixed(1)}%`);
    } else if (growth >= 8) {
      score += 1;
      reasons.push(`매출 증가 ${growth.toFixed(1)}%`);
    }
  }
  return { score: clamp(score, 0, 10), reasons };
}
function computeSwitchingCostScore(candidate: BottleneckCandidateDefinition): number {
  const base = candidate.priors?.switchingCost ?? 5;
  const tagBonus = (candidate.tags ?? []).some((tag) => ['eda','euv','qualification','liquid-cooling','mission-critical'].includes(tag)) ? 1 : 0;
  return clamp(base + tagBonus, 0, 10);
}
function extractEvidenceExcerpts(corpus: string, patterns: RegExp[]): string[] {
  const excerpts: string[] = [];
  for (const pattern of patterns) {
    const match = corpus.match(pattern)?.[0]?.trim();
    if (match && !excerpts.includes(match)) excerpts.push(match.slice(0, 180));
    if (excerpts.length >= 3) break;
  }
  return excerpts;
}

function convictionFromScore(score: number): 'WATCH' | 'STRONG' | 'CORE' {
  if (score >= 70) return 'CORE';
  if (score >= 55) return 'STRONG';
  return 'WATCH';
}
export function computeBottleneckCandidateScore(
  candidate: BottleneckCandidateDefinition,
  research: CompanyResearchResponse,
): BottleneckCandidateScore {
  const corpus = collectCorpus(research);
  const text = computeTextSignalScore(corpus);
  const quality = computeQualityScore(research);
  const componentScores: BottleneckComponentScores = {
    textSignal: text.score,
    quality: quality.score,
    concentration: candidate.priors?.concentration ?? 5,
    supplyTightness: candidate.priors?.supplyTightness ?? 5,
    capexLinkage: candidate.priors?.capexLinkage ?? 5,
    switchingCost: computeSwitchingCostScore(candidate),
  };
  const total = (
    componentScores.textSignal * 0.24 +
    componentScores.quality * 0.22 +
    componentScores.concentration * 0.16 +
    componentScores.supplyTightness * 0.16 +
    componentScores.capexLinkage * 0.10 +
    componentScores.switchingCost * 0.12
  ) * 10;
  const reasons = [
    candidate.role,
    ...(candidate.tags ?? []).slice(0, 2).map((tag) => `tag:${tag}`),
    ...text.reasons,
    ...quality.reasons,
  ].filter(Boolean).slice(0, 7);
  const score = Math.round(clamp(total, 0, 100));
  const excerptPatterns = [
    /[^.]{0,60}(capacity constraint|capacity constrained|supply constrained|lead time|backlog|pricing power|sole source|installed base|qualification)[^.]{0,90}/i,
    /[^.]{0,60}(yield|process control|metrology|design win|mission critical)[^.]{0,90}/i,
  ];
  return {
    ticker: research.profile.ticker,
    company: research.profile.name,
    role: candidate.role,
    theme: candidate.theme,
    score,
    conviction: convictionFromScore(score),
    componentScores,
    textMatches: text.matches.slice(0, 5).map((item) => ({ ...item, excerpts: extractEvidenceExcerpts(corpus, excerptPatterns) })),
    reasons,
    metrics: {
      revenueGrowthYoY: research.financials.revenueGrowthYoY,
      operatingMargin: research.financials.operatingMargin,
      evToSales: research.financials.evToSales,
      totalScore: research.score.totalScore,
    },
  };
}
