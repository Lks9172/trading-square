import {
  AssetSignal,
  DerivedIndicator,
  MarketDataPoint,
  RegimeState,
  TopDownAssetRationale,
  TopDownSectorView,
  TopDownView,
} from '../types/indicators';
import { SectorDefinition, listSectorDefinitions } from '../engines/sector-classification';
import { computeSectorQuality } from './sector-quality';

const ASSET_LABELS: Record<string, string> = {
  NASDAQ: '나스닥',
  KOSPI: '코스피',
  GOLD: '금',
  SILVER: '은',
  COPPER: '구리',
  LEVERAGE: '레버리지',
  CASH: '현금',
  EMERGING: '신흥국',
};

function rawValue(raw: Record<string, MarketDataPoint>, key: string): number | null {
  return raw[key]?.value ?? null;
}

function derivedValue(derived: Record<string, DerivedIndicator>, key: string): number | null {
  return derived[key]?.value ?? null;
}

function pushIf(arr: string[], condition: boolean, text: string) {
  if (condition) arr.push(text);
}

type ReasonCategory = 'macro' | 'sector' | 'flow' | 'timing' | 'asset';

const REASON_PRIORITY: Record<ReasonCategory, string[]> = {
  macro: ['유동성', '실질금리', '달러', '정책', '지정학'],
  sector: ['반도체', 'AI', '기술', '유틸리티', '에너지', '산업재', '소재'],
  flow: ['기관', '외국인', '13F', '스마트머니', '중앙은행', '구조'],
  timing: ['과열', '추격', 'W 바닥', '극저점', '이격도', '환율'],
  asset: ['과열', 'W 바닥', '기관', '외국인', '환율'],
};

function normalizeReason(item: string): string {
  return item.replace(/\s+/g, ' ').trim();
}

function rankReasons(items: Array<string | undefined>, category: ReasonCategory, limit = 3): string[] {
  const keywords = REASON_PRIORITY[category];
  const seen = new Set<string>();
  return items
    .filter((item): item is string => typeof item === 'string' && item.length > 0)
    .map((item) => normalizeReason(item))
    .filter((item) => {
      if (!item || seen.has(item)) return false;
      seen.add(item);
      return true;
    })
    .map((item, index) => ({
      item,
      index,
      score: keywords.reduce((sum, keyword, keywordIndex) => (item.includes(keyword) ? sum + (keywords.length - keywordIndex) * 10 : sum), 0)
        + (/과열|추격|W 바닥|극저점|기관|외국인|중앙은행/.test(item) ? 15 : 0),
    }))
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .slice(0, limit)
    .map((entry) => entry.item);
}

function compactReasons(items: string[], limit = 2): string[] {
  return items
    .filter((item) => !/현재 자산 신호|히스테리시스 보호/.test(item))
    .slice(0, limit);
}

function mergeReasons(category: ReasonCategory, ...groups: Array<string[] | undefined>): string[] {
  return rankReasons(groups.flat(), category);
}

function sectorStance(score: number | null, qualityScore: number): TopDownSectorView['stance'] {
  if (score === null) return 'neutral';
  if ((score >= 1.8 && qualityScore >= 60) || (score >= 1.0 && qualityScore >= 76)) return 'favored';
  if ((score <= -1.8 && qualityScore <= 50) || (score <= -1.0 && qualityScore <= 34) || score <= -4.8) return 'avoided';
  return 'neutral';
}

function macroView(raw: Record<string, MarketDataPoint>, derived: Record<string, DerivedIndicator>, regime: RegimeState): TopDownView['macroView'] {
  const liquidity = derivedValue(derived, 'LIQUIDITY_DIRECTION');
  const dxy = rawValue(raw, 'DXY');
  const yieldCurve = rawValue(raw, 'T10Y2Y');
  const wti = rawValue(raw, 'WTI');
  const realYield = derivedValue(derived, 'REAL_YIELD');
  const geoRisk = regime.components.geoRisk ?? 0;

  return [
    {
      key: 'liquidity',
      label: '유동성',
      stance: liquidity !== null ? (liquidity > 0 ? 'positive' : liquidity < 0 ? 'negative' : 'neutral') : 'neutral',
      detail: liquidity !== null
        ? liquidity > 0
          ? `유동성 방향이 개선 중 (${liquidity.toFixed(0)})`
          : liquidity < 0
            ? `유동성 방향이 둔화/축소 중 (${liquidity.toFixed(0)})`
            : '유동성 방향이 중립'
        : '유동성 데이터 보조 판단 필요',
    },
    {
      key: 'dollar',
      label: '달러',
      stance: dxy !== null ? (dxy < 102 ? 'positive' : dxy > 106 ? 'negative' : 'neutral') : 'neutral',
      detail: dxy !== null
        ? dxy < 102
          ? `달러 약세권 (${dxy.toFixed(1)}) — 원자재/신흥국 우호`
          : dxy > 106
            ? `달러 강세권 (${dxy.toFixed(1)}) — 위험자산 압박`
            : `달러 중립권 (${dxy.toFixed(1)})`
        : '달러 흐름 확인 필요',
    },
    {
      key: 'rates',
      label: '금리/실질금리',
      stance: realYield !== null ? (realYield < 1.5 ? 'positive' : realYield > 2.2 ? 'negative' : 'neutral') : 'neutral',
      detail: realYield !== null
        ? realYield < 1.5
          ? `실질금리 부담 낮음 (${realYield.toFixed(2)}%)`
          : realYield > 2.2
            ? `실질금리 부담 높음 (${realYield.toFixed(2)}%)`
            : `실질금리 중립 (${realYield.toFixed(2)}%)`
        : '실질금리 방향 확인 필요',
    },
    {
      key: 'cycle',
      label: '경기 사이클',
      stance: yieldCurve !== null ? (yieldCurve > 0.2 ? 'positive' : yieldCurve < -0.2 ? 'negative' : 'neutral') : 'neutral',
      detail: yieldCurve !== null
        ? yieldCurve > 0.2
          ? `장단기 금리차 정상/완만 확장 (${yieldCurve.toFixed(2)})`
          : yieldCurve < -0.2
            ? `역전 구간 지속 (${yieldCurve.toFixed(2)}) — 경기 둔화 경계`
            : `사이클 전환 관찰 구간 (${yieldCurve.toFixed(2)})`
        : '경기 사이클 중립',
    },
    {
      key: 'geopolitics',
      label: '지정학/에너지',
      stance: geoRisk <= -1 || (wti !== null && wti > 85) ? 'negative' : geoRisk >= 1 ? 'positive' : 'neutral',
      detail:
        geoRisk <= -1 || (wti !== null && wti > 85)
          ? `지정학/에너지 부담 높음${wti !== null ? ` (WTI ${wti.toFixed(1)})` : ''}`
          : geoRisk >= 1
            ? '지정학 부담 완화'
            : '지정학 변수 중립',
    },
  ];
}

function buildSectorReasons(
  item: SectorDefinition,
  score: number | null,
  macro: TopDownView['macroView'],
  qualityScore: ReturnType<typeof computeSectorQuality>,
): string[] {
  const reasons: string[] = [];
  const liquidityPositive = macro.find((m) => m.key === 'liquidity')?.stance === 'positive';
  const dollarPositive = macro.find((m) => m.key === 'dollar')?.stance === 'positive';
  const ratesNegative = macro.find((m) => m.key === 'rates')?.stance === 'negative';
  const geoNegative = macro.find((m) => m.key === 'geopolitics')?.stance === 'negative';

  if (score !== null) {
    pushIf(reasons, score > 0, `20일 모멘텀이 우상향 (${score.toFixed(1)}%)`);
    pushIf(reasons, score < 0, `20일 모멘텀이 부진 (${score.toFixed(1)}%)`);
  }
  pushIf(reasons, item.classification === 'structural' && liquidityPositive, '구조 수혜 + 유동성 개선 조합');
  pushIf(reasons, item.classification === 'cyclical' && dollarPositive, '달러 약세 시 경기민감 자산 우호');
  pushIf(reasons, item.classification === 'defensive' && ratesNegative, '금리/실질금리 부담 구간의 방어 대안');
  pushIf(reasons, item.key === 'SECTOR_XLE' && geoNegative, '지정학/유가 변수의 직접 수혜 가능');
  pushIf(reasons, item.key === 'SECTOR_XLK' && liquidityPositive, '기술/AI 프록시로 유동성 방향과 정합');
  pushIf(reasons, item.key === 'SECTOR_XLP' && item.classification === 'defensive', '필수소비재로 경기 둔화 방어 성격');
  pushIf(reasons, item.key === 'SECTOR_ITA' && geoNegative, '지정학/국방 예산 확대 수혜 프록시');
  pushIf(reasons, ['SECTOR_GRID', 'SECTOR_IGF'].includes(item.key) && liquidityPositive, '전력/인프라 CAPEX 사이클과 정합');
  pushIf(reasons, qualityScore.policySupport >= 65, `정책 수혜 점수 높음 (${qualityScore.policySupport})`);
  pushIf(reasons, qualityScore.structuralDemand >= 70, `구조 수요가 견조 (${qualityScore.structuralDemand})`);
  pushIf(reasons, qualityScore.supplyTightness >= 70, `공급 제약/병목 성격이 강함 (${qualityScore.supplyTightness})`);
  pushIf(reasons, qualityScore.marketConcentration >= 74 && reasons.length < 2, `과점/집중도 우위 (${qualityScore.marketConcentration})`);

  if (reasons.length === 0) reasons.push('거시 방향 대비 섹터 우위가 아직 뚜렷하지 않음');
  return compactReasons(rankReasons(reasons, 'sector'));
}

function buildAssetRationale(
  asset: string,
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  macro: TopDownView['macroView'],
  sectors: TopDownSectorView[],
  signal?: AssetSignal,
): TopDownAssetRationale {
  const macroReasons: string[] = [];
  const sectorReasons: string[] = [];
  const flowReasons: string[] = [];
  const timingNotes: string[] = [];

  const liquidityPositive = macro.find((m) => m.key === 'liquidity')?.stance === 'positive';
  const dollarPositive = macro.find((m) => m.key === 'dollar')?.stance === 'positive';
  const ratesPositive = macro.find((m) => m.key === 'rates')?.stance === 'positive';
  const geoNegative = macro.find((m) => m.key === 'geopolitics')?.stance === 'negative';

  if (asset === 'NASDAQ') {
    pushIf(macroReasons, liquidityPositive, '유동성 개선이 성장/기술 자산에 우호적');
    pushIf(macroReasons, ratesPositive, '실질금리 부담이 낮아 성장주 멀티플 방어에 유리');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_XLK' && s.stance === 'favored'), '기술 섹터 우위');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_XLC' && s.stance === 'favored'), '커뮤니케이션/메가캡 지원');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_GRID' && s.stance === 'favored'), '전력망 CAPEX 우위');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_IGF' && s.stance === 'favored'), '글로벌 인프라 우위');
    pushIf(flowReasons, (derivedValue(derived, 'INSTITUTIONAL_NASDAQ_FLOW') ?? 0) > 0, '기관 13F 나스닥 흐름이 플러스');
    pushIf(flowReasons, (derivedValue(derived, 'SMART_MONEY_SCORE') ?? 0) > 0, '스마트머니 흐름이 우호적');
    pushIf(timingNotes, (derivedValue(derived, 'OVERHEATED') ?? 0) === 1, '과열 경고 — 추격매수 주의');
    pushIf(timingNotes, (derivedValue(derived, 'NASDAQ_W_BOTTOM') ?? 0) === 1, 'W 바닥 확인 — 분할매수 3차 구간 후보');
  } else if (asset === 'KOSPI') {
    const foreignNet20 = derivedValue(derived, 'KOSPI_FOREIGN_NET_20D');
    const foreignExtreme = derivedValue(derived, 'KOSPI_FOREIGN_HISTORIC_EXTREME');
    pushIf(macroReasons, dollarPositive, '달러 약세는 원화/외국인 수급에 우호적');
    pushIf(macroReasons, !geoNegative, '지정학 부담이 완화되면 한국 위험자산 할인 축소 가능');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_XLK' && s.stance === 'favored'), '반도체/기술 우위가 한국 수출주에 유리');
    pushIf(flowReasons, foreignNet20 !== null && foreignNet20 > 0, '외국인 순매수 흐름이 개선 중');
    pushIf(flowReasons, foreignExtreme !== null && foreignExtreme >= 1, '외국인 수급이 역사적 저점에서 반전 구간');
    pushIf(timingNotes, (derivedValue(derived, 'FX_FOREIGN_COMBO_ALERT') ?? 0) >= 1, '환율+외인 경고 존재 — 진입 강도 조절 필요');
  } else if (asset === 'GOLD') {
    pushIf(macroReasons, ratesPositive, '실질금리 부담 완화는 금에 우호적');
    pushIf(macroReasons, dollarPositive, '달러 약세는 금/비달러 자산에 우호적');
    pushIf(macroReasons, geoNegative, '지정학 긴장은 안전자산 선호를 자극');
    pushIf(flowReasons, (derivedValue(derived, 'CB_GOLD_STRUCTURAL_DEMAND') ?? 0) > 0, '중앙은행 구조적 금 수요');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_XLP' && s.stance === 'favored'), '필수소비재 우위와 동행하는 방어 국면');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_ITA' && s.stance === 'favored'), '방산 프록시 강세와 맞물린 지정학 헤지');
    pushIf(timingNotes, (derivedValue(derived, 'GOLD_W_BOTTOM') ?? 0) === 1, '금 W 바닥 시그널 확인');
  } else if (asset === 'COPPER') {
    pushIf(macroReasons, dollarPositive, '달러 약세는 산업금속에 우호적');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_XLI' && s.stance === 'favored'), '산업재 우위와 동행 가능');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_XLB' && s.stance === 'favored'), '소재 섹터 민감도');
    pushIf(timingNotes, (derivedValue(derived, 'COPPER_GOLD_RATIO_UPTURN') ?? 0) === 1, '구리/금 비율 반등 — 경기 민감 자산 확인');
  } else if (asset === 'SILVER') {
    pushIf(macroReasons, ratesPositive, '실질금리 완화 시 귀금속 우호');
    pushIf(timingNotes, (derivedValue(derived, 'GOLD_SILVER_RATIO_EXTREME') ?? 0) === 1, '금은비 극단 구간 — 평균회귀 후보');
  } else if (asset === 'EMERGING') {
    const fxForeignAlert = derivedValue(derived, 'FX_FOREIGN_COMBO_ALERT');
    pushIf(macroReasons, dollarPositive, '달러 약세는 신흥국 자금 유입에 우호적');
    pushIf(flowReasons, fxForeignAlert !== null && fxForeignAlert < 2, 'FX/외인 경고가 하드 단계는 아님');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_GRID' && s.stance === 'favored'), '전력망 투자 사이클 우호');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_IGF' && s.stance === 'favored'), '인프라 프록시 우위');
  } else if (asset === 'LEVERAGE') {
    pushIf(macroReasons, liquidityPositive && ratesPositive, '레버리지는 유동성+금리 환경이 모두 좋아야 유리');
    pushIf(timingNotes, signal?.tier === 'HARD', '레버리지 3조건 모두 충족');
    pushIf(timingNotes, signal?.tier === 'MEDIUM', '레버리지 조건 일부만 충족 — 공격 비중 주의');
  } else if (asset === 'CASH') {
    pushIf(macroReasons, macro.some((m) => m.stance === 'negative'), '거시 부담 구간의 방어 수단');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_XLP' && s.stance === 'favored'), '필수소비재 우위로 방어 포지션 정합');
    pushIf(sectorReasons, sectors.some((s) => s.key === 'SECTOR_XLU' && s.stance === 'favored'), '유틸리티 우위로 현금/방어 선호 정합');
    pushIf(timingNotes, (derivedValue(derived, 'OVERHEATED') ?? 0) === 1, '과열 구간에서는 현금 가치가 상승');
  }

  if (signal) {
    pushIf(timingNotes, (signal.signal === 'REDUCE' || signal.signal === 'SELL') && timingNotes.length === 0, `현재 자산 신호는 ${signal.signal} — 비중 조절 우선`);
  }

  return {
    asset,
    label: ASSET_LABELS[asset] || asset,
    macroReasons: compactReasons(rankReasons(macroReasons, 'macro')),
    sectorReasons: compactReasons(rankReasons(sectorReasons, 'sector')),
    flowReasons: compactReasons(rankReasons(flowReasons, 'flow')),
    timingNotes: compactReasons(rankReasons(timingNotes, 'timing')),
  };
}

export function buildTopDownView(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  regime: RegimeState,
  signals: AssetSignal[],
): TopDownView {
  const macro = macroView(raw, derived, regime);
  const sectorViews: TopDownSectorView[] = listSectorDefinitions().map((item) => {
    const score = derivedValue(derived, item.key);
    const quality = computeSectorQuality(item, raw, derived, regime);
    const stance = sectorStance(score, quality.totalScore);

    return {
      ...item,
      score,
      quality,
      stance,
      reasons: buildSectorReasons(item, score, macro, quality),
    };
  });

  const favoredSectors = sectorViews
    .filter((s) => s.stance === 'favored')
    .sort((a, b) => ((b.quality?.totalScore ?? 0) * 10 + (b.score ?? -999)) - ((a.quality?.totalScore ?? 0) * 10 + (a.score ?? -999)))
    .slice(0, 4);
  const avoidedSectors = sectorViews
    .filter((s) => s.stance === 'avoided')
    .sort((a, b) => ((a.score ?? 999) - (b.score ?? 999)) || ((a.quality?.totalScore ?? 100) - (b.quality?.totalScore ?? 100)))
    .slice(0, 3);

  const assets = ['NASDAQ', 'KOSPI', 'GOLD', 'SILVER', 'COPPER', 'EMERGING', 'LEVERAGE', 'CASH'];
  const assetRationale = assets.map((asset) =>
    buildAssetRationale(asset, raw, derived, macro, sectorViews, signals.find((s) => s.asset === asset)),
  );

  const positiveMacroLabels = macro.filter((m) => m.stance === 'positive').map((m) => m.label);
  const favoredLabels = favoredSectors.map((s) => s.label).slice(0, 1);
  const summary = positiveMacroLabels.length > 0 || favoredLabels.length > 0
    ? `거시는 ${positiveMacroLabels.slice(0, 2).join(', ') || '중립'} 쪽, 섹터는 ${favoredLabels.join(', ') || '뚜렷한 우위 없음'} 중심 해석입니다.`
    : '현재 거시와 섹터는 뚜렷한 한 방향보다는 혼조/중립으로 해석됩니다.';

  return {
    summary,
    macroView: macro,
    favoredSectors,
    avoidedSectors,
    assetRationale,
  };
}

export function enrichSignalExplanations(signals: AssetSignal[], topdown: TopDownView): void {
  const rationaleByAsset = new Map<string, TopDownAssetRationale>(
    topdown.assetRationale.map((item) => [item.asset, item]),
  );

  for (const signal of signals) {
    const rationale = rationaleByAsset.get(signal.asset);
    if (!rationale) continue;
    const baseSignal = signal.explanation?.baseSignal ?? signal.signal;
    const finalSignal = signal.explanation?.finalSignal ?? signal.signal;
    const overrides = signal.explanation?.overrides ?? [];
    signal.explanation = {
      baseSignal,
      finalSignal,
      overrides,
      macroReasons: mergeReasons('macro', signal.explanation?.macroReasons, rationale.macroReasons),
      sectorReasons: mergeReasons('sector', signal.explanation?.sectorReasons, rationale.sectorReasons),
      assetReasons: compactReasons(mergeReasons('asset', signal.explanation?.assetReasons, signal.reasons.slice(0, 3))),
      flowReasons: mergeReasons('flow', signal.explanation?.flowReasons, rationale.flowReasons),
      timingNotes: mergeReasons('timing', signal.explanation?.timingNotes, rationale.timingNotes),
    };
  }
}
