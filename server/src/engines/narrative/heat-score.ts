import { AssetSignal, SystemSnapshot } from '../../types/indicators';
import { NarrativeExternalSignal, NarrativeStage, NarrativeThemeDefinition, NarrativeThemeState } from '../../types/narrative';

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function raw(snapshot: SystemSnapshot, key: string): number | null {
  const v = snapshot.raw[key]?.value;
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function derived(snapshot: SystemSnapshot, key: string): number | null {
  const v = snapshot.derived[key]?.value;
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function signal(snapshot: SystemSnapshot, asset: string): AssetSignal | undefined {
  return snapshot.signals.find((item) => item.asset === asset);
}

function signalScore(sig?: AssetSignal): number {
  if (!sig) return 0;
  switch (sig.signal) {
    case 'STRONG_BUY': return 9;
    case 'BUY': return 7;
    case 'HOLD': return 5;
    case 'REDUCE': return 3;
    case 'SELL': return 1;
    default: return 0;
  }
}

function stageFromHeat(heatScore: number): NarrativeStage {
  if (heatScore >= 66) return 'OVERHEATED';
  if (heatScore >= 36) return 'MID';
  return 'EARLY';
}

function scoreNarrative(theme: NarrativeThemeDefinition, snapshot: SystemSnapshot, externalSignals: NarrativeExternalSignal[] = []) {
  const manual = snapshot.meta.profile.manualInputs;
  const proxyScores: NarrativeThemeState['proxyScores'] = [];
  const drivers: string[] = [];
  const risks: string[] = [];

  const add = (key: string, label: string, score: number, detail: string, opts?: { driver?: string; risk?: string }) => {
    const normalized = clamp(score, 0, 10);
    proxyScores.push({ key, label, score: normalized, detail });
    if (opts?.driver) drivers.push(opts.driver);
    if (opts?.risk) risks.push(opts.risk);
  };

  if (theme.id === 'ai-power') {
    const soxx = derived(snapshot, 'SECTOR_SOXX');
    const grid = derived(snapshot, 'SECTOR_GRID');
    const igf = derived(snapshot, 'SECTOR_IGF');
    const ndxSig = signal(snapshot, 'NASDAQ');
    const disparity = derived(snapshot, 'NASDAQ_DISPARITY');
    add('SECTOR_SOXX', 'SOXX 30D', soxx === null ? 4 : soxx >= 12 ? 9 : soxx >= 5 ? 7 : soxx >= 0 ? 5 : 2, `SOXX ${soxx?.toFixed(1) ?? 'n/a'}%`, soxx !== null && soxx >= 5 ? { driver: `반도체 모멘텀 ${soxx.toFixed(1)}%` } : undefined);
    add('SECTOR_GRID', 'GRID 30D', grid === null ? 4 : grid >= 8 ? 8 : grid >= 3 ? 6 : grid >= 0 ? 5 : 3, `GRID ${grid?.toFixed(1) ?? 'n/a'}%`, grid !== null && grid >= 3 ? { driver: `전력망 프록시 ${grid.toFixed(1)}%` } : undefined);
    add('SECTOR_IGF', 'IGF 30D', igf === null ? 4 : igf >= 6 ? 7 : igf >= 2 ? 6 : igf >= 0 ? 5 : 3, `IGF ${igf?.toFixed(1) ?? 'n/a'}%`);
    add('NASDAQ_SIGNAL', 'NASDAQ 신호', signalScore(ndxSig), `NASDAQ ${ndxSig?.signal ?? 'n/a'}`, ndxSig && ['BUY','STRONG_BUY'].includes(ndxSig.signal) ? { driver: `NASDAQ ${ndxSig.signal}` } : undefined);
    add('NASDAQ_DISPARITY', 'NASDAQ 이격도', disparity === null ? 4 : disparity >= 15 ? 9 : disparity >= 8 ? 7 : disparity >= 0 ? 5 : 3, `이격 ${disparity?.toFixed(1) ?? 'n/a'}%`, disparity !== null && disparity >= 15 ? { risk: `NASDAQ 이격도 ${disparity.toFixed(1)}%` } : undefined);
  } else if (theme.id === 'grid-capex') {
    const grid = derived(snapshot, 'SECTOR_GRID');
    const igf = derived(snapshot, 'SECTOR_IGF');
    const xlu = derived(snapshot, 'SECTOR_XLU');
    const copper = signal(snapshot, 'COPPER');
    const aiStrength = manual.aiNarrativeStrength ?? 0;
    add('SECTOR_GRID', 'GRID 30D', grid === null ? 4 : grid >= 8 ? 9 : grid >= 3 ? 7 : grid >= 0 ? 5 : 2, `GRID ${grid?.toFixed(1) ?? 'n/a'}%`, grid !== null && grid >= 3 ? { driver: `전력망 수익률 ${grid.toFixed(1)}%` } : undefined);
    add('SECTOR_IGF', 'IGF 30D', igf === null ? 4 : igf >= 6 ? 8 : igf >= 2 ? 6 : igf >= 0 ? 5 : 3, `IGF ${igf?.toFixed(1) ?? 'n/a'}%`);
    add('SECTOR_XLU', 'XLU 30D', xlu === null ? 4 : xlu >= 4 ? 7 : xlu >= 0 ? 5 : 3, `XLU ${xlu?.toFixed(1) ?? 'n/a'}%`);
    add('COPPER', '구리 신호', signalScore(copper), `COPPER ${copper?.signal ?? 'n/a'}`);
    add('AI_NARRATIVE_STRENGTH', '수동 AI 강도', aiStrength >= 2 ? 8 : aiStrength === 1 ? 6 : 4, `manual=${aiStrength}`, aiStrength >= 1 ? { driver: `수동 AI 내러티브 ${aiStrength}` } : undefined);
  } else if (theme.id === 'defense-rearm') {
    const ita = derived(snapshot, 'SECTOR_ITA');
    const geoRisk = manual.geoRisk ?? 0;
    const wti = raw(snapshot, 'WTI');
    const goldSig = signal(snapshot, 'GOLD');
    add('SECTOR_ITA', 'ITA 30D', ita === null ? 4 : ita >= 8 ? 9 : ita >= 3 ? 7 : ita >= 0 ? 5 : 2, `ITA ${ita?.toFixed(1) ?? 'n/a'}%`, ita !== null && ita >= 3 ? { driver: `방산 프록시 ${ita.toFixed(1)}%` } : undefined);
    add('GEO_RISK', '지정학 수동', geoRisk >= 4 ? 9 : geoRisk >= 3 ? 7 : geoRisk >= 2 ? 5 : 3, `geoRisk=${geoRisk}`, geoRisk >= 3 ? { driver: `지정학 위험 ${geoRisk}` } : undefined);
    add('WTI', 'WTI 레벨', wti === null ? 4 : wti >= 85 ? 7 : wti >= 70 ? 5 : 4, `WTI ${wti?.toFixed(1) ?? 'n/a'}`);
    add('GOLD_SIGNAL', '금 신호', signalScore(goldSig), `GOLD ${goldSig?.signal ?? 'n/a'}`, goldSig && ['BUY','STRONG_BUY'].includes(goldSig.signal) ? { driver: `금 신호 ${goldSig.signal}` } : undefined);
  } else if (theme.id === 'finance-liquidity') {
    const xlf = derived(snapshot, 'SECTOR_XLF');
    const vix = raw(snapshot, 'VIXCLS');
    const ndxSig = signal(snapshot, 'NASDAQ');
    add('SECTOR_XLF', 'XLF 30D', xlf === null ? 4 : xlf >= 8 ? 8 : xlf >= 3 ? 6 : xlf >= 0 ? 5 : 3, `XLF ${xlf?.toFixed(1) ?? 'n/a'}%`, xlf !== null && xlf >= 3 ? { driver: `금융 모멘텀 ${xlf.toFixed(1)}%` } : undefined);
    add('VIXCLS', 'VIX', vix === null ? 4 : vix >= 28 ? 3 : vix >= 20 ? 5 : 7, `VIX ${vix?.toFixed(1) ?? 'n/a'}`, vix !== null && vix >= 28 ? { risk: `변동성 ${vix.toFixed(1)}` } : undefined);
    add('NASDAQ_SIGNAL', 'NASDAQ 신호', signalScore(ndxSig), `NASDAQ ${ndxSig?.signal ?? 'n/a'}`);
  } else if (theme.id === 'energy-supply') {
    const xle = derived(snapshot, 'SECTOR_XLE');
    const wti = raw(snapshot, 'WTI');
    const copper = signal(snapshot, 'COPPER');
    add('SECTOR_XLE', 'XLE 30D', xle === null ? 4 : xle >= 10 ? 9 : xle >= 4 ? 7 : xle >= 0 ? 5 : 2, `XLE ${xle?.toFixed(1) ?? 'n/a'}%`, xle !== null && xle >= 4 ? { driver: `에너지 모멘텀 ${xle.toFixed(1)}%` } : undefined);
    add('WTI', 'WTI', wti === null ? 4 : wti >= 85 ? 8 : wti >= 72 ? 6 : wti >= 60 ? 5 : 3, `WTI ${wti?.toFixed(1) ?? 'n/a'}`, wti !== null && wti >= 85 ? { risk: `유가 ${wti.toFixed(1)}` } : undefined);
    add('COPPER', '구리 신호', signalScore(copper), `COPPER ${copper?.signal ?? 'n/a'}`);
  } else if (theme.id === 'digital-attention') {
    const xlc = derived(snapshot, 'SECTOR_XLC');
    const ndxSig = signal(snapshot, 'NASDAQ');
    const aiStrength = manual.aiNarrativeStrength ?? 0;
    add('SECTOR_XLC', 'XLC 30D', xlc === null ? 4 : xlc >= 8 ? 8 : xlc >= 3 ? 6 : xlc >= 0 ? 5 : 3, `XLC ${xlc?.toFixed(1) ?? 'n/a'}%`, xlc !== null && xlc >= 3 ? { driver: `커뮤니케이션 모멘텀 ${xlc.toFixed(1)}%` } : undefined);
    add('NASDAQ_SIGNAL', 'NASDAQ 신호', signalScore(ndxSig), `NASDAQ ${ndxSig?.signal ?? 'n/a'}`);
    add('AI_NARRATIVE_STRENGTH', 'AI/광고 확산', aiStrength >= 2 ? 7 : aiStrength === 1 ? 6 : 4, `manual=${aiStrength}`);
  } else if (theme.id === 'consumer-demand') {
    const xly = derived(snapshot, 'SECTOR_XLY');
    const xlp = derived(snapshot, 'SECTOR_XLP');
    const copper = signal(snapshot, 'COPPER');
    add('SECTOR_XLY', 'XLY 30D', xly === null ? 4 : xly >= 8 ? 8 : xly >= 3 ? 6 : xly >= 0 ? 5 : 3, `XLY ${xly?.toFixed(1) ?? 'n/a'}%`, xly !== null && xly >= 3 ? { driver: `소비 수요 ${xly.toFixed(1)}%` } : undefined);
    add('SECTOR_XLP', 'XLP 30D', xlp === null ? 4 : xlp >= 4 ? 6 : xlp >= 0 ? 5 : 3, `XLP ${xlp?.toFixed(1) ?? 'n/a'}%`);
    add('COPPER', '구리 신호', signalScore(copper), `COPPER ${copper?.signal ?? 'n/a'}`);
  } else if (theme.id === 'consumer-defensive') {
    const xly = derived(snapshot, 'SECTOR_XLY');
    const xlp = derived(snapshot, 'SECTOR_XLP');
    const xlv = derived(snapshot, 'SECTOR_XLV');
    add('SECTOR_XLY', 'XLY 30D', xly === null ? 4 : xly >= 8 ? 8 : xly >= 3 ? 6 : xly >= 0 ? 5 : 3, `XLY ${xly?.toFixed(1) ?? 'n/a'}%`);
    add('SECTOR_XLP', 'XLP 30D', xlp === null ? 4 : xlp >= 5 ? 7 : xlp >= 1 ? 6 : xlp >= 0 ? 5 : 3, `XLP ${xlp?.toFixed(1) ?? 'n/a'}`);
    add('SECTOR_XLV', 'XLV 30D', xlv === null ? 4 : xlv >= 5 ? 7 : xlv >= 1 ? 6 : xlv >= 0 ? 5 : 3, `XLV ${xlv?.toFixed(1) ?? 'n/a'}`);
  } else if (theme.id === 'materials-reflation') {
    const xlb = derived(snapshot, 'SECTOR_XLB');
    const wti = raw(snapshot, 'WTI');
    const copper = signal(snapshot, 'COPPER');
    add('SECTOR_XLB', 'XLB 30D', xlb === null ? 4 : xlb >= 8 ? 8 : xlb >= 3 ? 6 : xlb >= 0 ? 5 : 3, `XLB ${xlb?.toFixed(1) ?? 'n/a'}`, xlb !== null && xlb >= 3 ? { driver: `소재 모멘텀 ${xlb.toFixed(1)}%` } : undefined);
    add('WTI', 'WTI', wti === null ? 4 : wti >= 82 ? 7 : wti >= 70 ? 6 : 4, `WTI ${wti?.toFixed(1) ?? 'n/a'}`);
    add('COPPER', '구리 신호', signalScore(copper), `COPPER ${copper?.signal ?? 'n/a'}`, copper && ['BUY','STRONG_BUY'].includes(copper.signal) ? { driver: `구리 ${copper.signal}` } : undefined);
  } else if (theme.id === 'real-assets-rate') {
    const xlre = derived(snapshot, 'SECTOR_XLRE');
    const igf = derived(snapshot, 'SECTOR_IGF');
    const goldSig = signal(snapshot, 'GOLD');
    add('SECTOR_XLRE', 'XLRE 30D', xlre === null ? 4 : xlre >= 6 ? 7 : xlre >= 1 ? 6 : xlre >= 0 ? 5 : 3, `XLRE ${xlre?.toFixed(1) ?? 'n/a'}%`);
    add('SECTOR_IGF', 'IGF 30D', igf === null ? 4 : igf >= 6 ? 7 : igf >= 2 ? 6 : igf >= 0 ? 5 : 3, `IGF ${igf?.toFixed(1) ?? 'n/a'}`);
    add('GOLD_SIGNAL', '금 신호', signalScore(goldSig), `GOLD ${goldSig?.signal ?? 'n/a'}`);
  } else if (theme.id === 'safehaven-gold') {
    const goldSig = signal(snapshot, 'GOLD');
    const goldPriority = derived(snapshot, 'GOLD_PRIORITY_SCORE');
    const vix = raw(snapshot, 'VIXCLS');
    const disparity = derived(snapshot, 'GOLD_DISPARITY');
    const cb = derived(snapshot, 'CB_GOLD_STRUCTURAL_DEMAND');
    add('GOLD_SIGNAL', '금 신호', signalScore(goldSig), `GOLD ${goldSig?.signal ?? 'n/a'}`, goldSig && ['BUY','STRONG_BUY'].includes(goldSig.signal) ? { driver: `금 신호 ${goldSig.signal}` } : undefined);
    add('GOLD_PRIORITY_SCORE', '금 우선순위', goldPriority === null ? 4 : goldPriority >= 0.7 ? 9 : goldPriority >= 0.4 ? 7 : goldPriority >= 0.2 ? 5 : 3, `score ${goldPriority?.toFixed(2) ?? 'n/a'}`);
    add('VIXCLS', 'VIX', vix === null ? 4 : vix >= 30 ? 9 : vix >= 22 ? 7 : vix >= 15 ? 5 : 3, `VIX ${vix?.toFixed(1) ?? 'n/a'}`, vix !== null && vix >= 30 ? { driver: `VIX ${vix.toFixed(1)}` } : undefined);
    add('GOLD_DISPARITY', '금 이격도', disparity === null ? 4 : disparity >= 18 ? 9 : disparity >= 10 ? 7 : disparity >= 0 ? 5 : 3, `이격 ${disparity?.toFixed(1) ?? 'n/a'}%`, disparity !== null && disparity >= 18 ? { risk: `금 이격도 ${disparity.toFixed(1)}%` } : undefined);
    add('CB_GOLD_STRUCTURAL_DEMAND', '중앙은행 수요', cb === null ? 4 : cb >= 0.7 ? 8 : cb >= 0.4 ? 6 : cb >= 0.2 ? 5 : 3, `CB demand ${cb?.toFixed(2) ?? 'n/a'}`);
  }

  const externalDrivers = externalSignals.filter((item) => item.score >= 7).map((item) => `${item.label} ${item.value ?? 'n/a'}`);
  const externalRisks = externalSignals.filter((item) => item.score >= 8.5).map((item) => `${item.label} 과열 ${item.value ?? 'n/a'}`);
  const combined = [...proxyScores, ...externalSignals];
  const avg = combined.length ? combined.reduce((sum, item) => sum + item.score, 0) / combined.length : 0;
  const heatScore = Math.round(avg * 10);
  return {
    stage: stageFromHeat(heatScore),
    heatScore,
    drivers: [...drivers, ...externalDrivers].slice(0, 6),
    risks: [...risks, ...externalRisks].slice(0, 4),
    proxyScores,
    externalSignals,
  };
}

export function computeNarrativeThemeState(theme: NarrativeThemeDefinition, snapshot: SystemSnapshot, externalSignals: NarrativeExternalSignal[] = []): NarrativeThemeState {
  const scored = scoreNarrative(theme, snapshot, externalSignals);
  return {
    theme,
    generatedAt: snapshot.timestamp,
    stage: scored.stage,
    heatScore: scored.heatScore,
    drivers: scored.drivers,
    risks: scored.risks,
    proxyScores: scored.proxyScores,
    externalSignals: scored.externalSignals,
  };
}
