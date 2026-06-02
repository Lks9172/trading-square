export interface CompanyPeerTarget {
  ticker: string;
  relation: string;
  peerGroup?: string;
}

interface PeerAutoExpansionInput {
  ticker: string;
  name?: string | null;
  sic?: string | null;
}


const PEER_GROUP_CANDIDATES: Record<string, string[]> = {
  AI_SEMIS: ['NVDA', 'AMD', 'AVGO', 'TSM', 'ASML', 'MRVL', 'INTC'],
  MEGACAP_PLATFORM: ['MSFT', 'GOOGL', 'META', 'AMZN'],
  SEMI_EQUIPMENT: ['ASML', 'AMAT', 'LRCX', 'KLAC', 'TSM'],
  POWER_INFRA: ['VRT', 'ETN', 'HUBB', 'NVT', 'GEV'],
};

const SIC_GROUP_MAP: Record<string, string> = {
  '3571': 'AI_SEMIS',
  '3572': 'AI_SEMIS',
  '3576': 'AI_SEMIS',
  '3674': 'AI_SEMIS',
  '3679': 'SEMI_EQUIPMENT',
  '3663': 'POWER_INFRA',
  '3612': 'POWER_INFRA',
  '4931': 'POWER_INFRA',
  '7370': 'MEGACAP_PLATFORM',
  '7372': 'MEGACAP_PLATFORM',
};

const NAME_GROUP_RULES: Array<{ pattern: RegExp; peerGroup: string }> = [
  { pattern: /(semiconductor|micro devices|broadcom|marvell|nvidia|taiwan semiconductor|asml|applied materials|lam research)/i, peerGroup: 'AI_SEMIS' },
  { pattern: /(google|alphabet|meta|microsoft|amazon|cloud|platform|software)/i, peerGroup: 'MEGACAP_PLATFORM' },
  { pattern: /(vertiv|eaton|hubbell|nvent|grid|electric|power)/i, peerGroup: 'POWER_INFRA' },
  { pattern: /(equipment|etch|deposition|lithography)/i, peerGroup: 'SEMI_EQUIPMENT' },
];

function inferPeerGroup(input: PeerAutoExpansionInput): string | null {
  const sic = (input.sic ?? '').trim();
  if (sic && SIC_GROUP_MAP[sic]) return SIC_GROUP_MAP[sic];
  const name = input.name ?? '';
  for (const rule of NAME_GROUP_RULES) {
    if (rule.pattern.test(name)) return rule.peerGroup;
  }
  return null;
}

function dedupePeers(items: CompanyPeerTarget[], baseTicker: string): CompanyPeerTarget[] {
  const seen = new Set<string>();
  const normalizedBase = baseTicker.toUpperCase();
  const output: CompanyPeerTarget[] = [];
  for (const item of items) {
    const ticker = item.ticker.toUpperCase();
    if (!ticker || ticker === normalizedBase || seen.has(ticker)) continue;
    seen.add(ticker);
    output.push({ ...item, ticker });
  }
  return output;
}

const PEER_MAP: Record<string, CompanyPeerTarget[]> = {
  NVDA: [
    { ticker: 'AMD', relation: 'AI/가속기 경쟁', peerGroup: 'AI_SEMIS' },
    { ticker: 'AVGO', relation: 'AI 인프라/반도체', peerGroup: 'AI_SEMIS' },
    { ticker: 'TSM', relation: '생산 생태계', peerGroup: 'AI_SEMIS' },
  ],
  AMD: [
    { ticker: 'NVDA', relation: 'AI/가속기 경쟁', peerGroup: 'AI_SEMIS' },
    { ticker: 'AVGO', relation: '데이터센터 반도체', peerGroup: 'AI_SEMIS' },
    { ticker: 'INTC', relation: 'CPU 경쟁', peerGroup: 'AI_SEMIS' },
  ],
  AVGO: [
    { ticker: 'NVDA', relation: 'AI 인프라' },
    { ticker: 'AMD', relation: '반도체 peer' },
    { ticker: 'MRVL', relation: '네트워킹/커스텀칩' },
  ],
  MSFT: [
    { ticker: 'GOOGL', relation: '클라우드/AI 플랫폼' },
    { ticker: 'AMZN', relation: '클라우드 peer' },
    { ticker: 'META', relation: 'AI CAPEX 대형주' },
  ],
  GOOGL: [
    { ticker: 'MSFT', relation: '클라우드/AI 플랫폼' },
    { ticker: 'META', relation: '광고/AI 대형주' },
    { ticker: 'AMZN', relation: '클라우드 peer' },
  ],
  META: [
    { ticker: 'GOOGL', relation: '광고 플랫폼' },
    { ticker: 'MSFT', relation: 'AI CAPEX 대형주' },
    { ticker: 'AMZN', relation: '대형 기술주 peer' },
  ],
  ASML: [
    { ticker: 'AMAT', relation: '반도체 장비' },
    { ticker: 'LRCX', relation: '반도체 장비' },
    { ticker: 'TSM', relation: '반도체 생태계' },
  ],
  TSM: [
    { ticker: 'ASML', relation: '생산 생태계' },
    { ticker: 'NVDA', relation: 'AI 수요 연동' },
    { ticker: 'INTC', relation: '파운드리 경쟁' },
  ],
};

export function getCompanyPeers(ticker: string): CompanyPeerTarget[] {
  return PEER_MAP[ticker.toUpperCase()] ?? [];
}

export function getAutoExpandedCompanyPeers(input: PeerAutoExpansionInput): CompanyPeerTarget[] {
  const baseTicker = input.ticker.toUpperCase();
  const staticPeers = getCompanyPeers(baseTicker);
  const inferredGroup = inferPeerGroup(input)
    ?? staticPeers.find((item) => item.peerGroup)?.peerGroup
    ?? null;
  const expanded = inferredGroup && PEER_GROUP_CANDIDATES[inferredGroup]
    ? PEER_GROUP_CANDIDATES[inferredGroup].map((ticker) => ({
        ticker,
        relation: `${inferredGroup} 산업군 비교`,
        peerGroup: inferredGroup,
      }))
    : [];
  return dedupePeers([...staticPeers, ...expanded], baseTicker).slice(0, 6);
}

export const RESEARCH_THEME_GROUPS: Array<{ theme: string; description: string; tickers: string[] }> = [
  { theme: 'AI / 반도체', description: 'AI CAPEX 및 반도체 공급망 대표 기업', tickers: ['NVDA', 'AMD', 'AVGO', 'TSM', 'ASML'] },
  { theme: '메가캡 플랫폼', description: '클라우드/광고/AI 플랫폼 대형주', tickers: ['MSFT', 'GOOGL', 'META', 'AMZN'] },
  { theme: '인프라 / 전력', description: '향후 GRID_CAPEX 단계에서 연결 예정 대표군', tickers: ['VRT', 'ETN', 'HUBB', 'NVT'] },
];
