export interface CompanyPeerTarget {
  ticker: string;
  relation: string;
  peerGroup?: string;
}

export interface PeerAutoExpansionInput {
  ticker: string;
  name?: string | null;
  sic?: string | null;
}

export interface ResearchThemeGroup {
  id: string;
  theme: string;
  description: string;
  tickers: string[];
  sectorKeys: string[];
}

export interface ResearchSectorGroup {
  id: string;
  label: string;
  description: string;
  sectorKey: string;
  tickers: string[];
}

function slugifyTheme(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9가-힣]+/g, '-').replace(/^-+|-+$/g, '');
}

const PEER_GROUP_CANDIDATES: Record<string, string[]> = {
  AI_SEMIS: ['NVDA', 'AMD', 'AVGO', 'TSM', 'ASML', 'MRVL', 'INTC', 'QCOM', 'MU', 'ARM', 'ADI', 'MCHP', 'TXN'],
  MEGACAP_PLATFORM: ['MSFT', 'GOOGL', 'META', 'AMZN', 'ORCL', 'CRM', 'NOW', 'ADBE', 'NFLX', 'UBER', 'AAPL', 'PANW', 'CRWD', 'SNOW', 'ANET', 'INTU'],
  SEMI_EQUIPMENT: ['ASML', 'AMAT', 'LRCX', 'KLAC', 'TSM', 'TER', 'ONTO', 'ACLS', 'UCTT', 'FORM'],
  POWER_INFRA: ['VRT', 'ETN', 'HUBB', 'NVT', 'GEV', 'PWR', 'EME', 'JCI', 'TT', 'CARR', 'PH', 'ROK'],
  DEFENSE_AERO: ['LMT', 'NOC', 'RTX', 'GD', 'BA', 'LHX', 'HII', 'KTOS', 'AVAV', 'CW'],
  HEALTHCARE_BIO: ['LLY', 'NVO', 'ISRG', 'UNH', 'JNJ', 'MRK', 'ABBV', 'TMO', 'DHR', 'VRTX', 'REGN', 'AMGN', 'GILD', 'BMY', 'PFE', 'SYK', 'MDT', 'BSX', 'ABT', 'ZBH'],
  FINANCIALS: ['JPM', 'BAC', 'WFC', 'GS', 'MS', 'BLK', 'BX', 'KKR', 'SCHW', 'C', 'AXP', 'V', 'MA', 'ICE', 'CME', 'PYPL', 'AON', 'MMC', 'CB', 'PGR'],
  ENERGY_SUPPLY: ['XOM', 'CVX', 'COP', 'EOG', 'SLB', 'BKR', 'HAL', 'MPC', 'PSX', 'VLO', 'WMB', 'KMI', 'DVN', 'FANG', 'APA', 'EQT', 'CTRA', 'OKE', 'TRGP', 'OXY'],
  COMMUNICATION_MEDIA: ['GOOGL', 'META', 'NFLX', 'TMUS', 'VZ', 'T', 'CMCSA', 'DIS', 'SPOT', 'ROKU', 'PINS', 'SNAP', 'WBD', 'CHTR', 'DASH', 'UBER', 'LYV', 'FOXA', 'EA', 'TTWO'],
  CONSUMER_FRANCHISE: ['AMZN', 'TSLA', 'HD', 'MCD', 'NKE', 'SBUX', 'BKNG', 'LOW', 'TJX', 'ROST', 'AZO', 'ORLY', 'CMG', 'MAR', 'HLT', 'LULU', 'YUM', 'DRI', 'EBAY', 'DPZ'],
  CONSUMER_STAPLES: ['PG', 'KO', 'PEP', 'WMT', 'COST', 'PM', 'MO', 'CL', 'MDLZ', 'GIS', 'KMB', 'KR', 'KHC', 'MNST', 'EL', 'HSY', 'SYY', 'ADM', 'TSN', 'CHD'],
  MATERIALS_RESOURCES: ['LIN', 'APD', 'SHW', 'ECL', 'FCX', 'NEM', 'NUE', 'ALB', 'CF', 'MOS', 'MLM', 'VMC', 'DD', 'DOW', 'CTVA', 'IFF', 'STLD', 'BALL', 'PKG', 'LYB'],
  UTILITIES_DEFENSIVE: ['NEE', 'SO', 'DUK', 'AEP', 'SRE', 'XEL', 'EXC', 'CEG', 'VST', 'NRG', 'AWK', 'WEC', 'D', 'PEG', 'PCG', 'ED', 'EIX', 'ETR', 'CMS', 'AES'],
  REAL_ASSETS: ['PLD', 'AMT', 'EQIX', 'SPG', 'O', 'WELL', 'DLR', 'PSA', 'CCI', 'VICI', 'AVB', 'EQR', 'SBAC', 'EXR', 'ARE', 'IRM', 'CBRE', 'WY', 'INVH', 'KIM'],
  INDUSTRIALS_CYCLICAL: ['GE', 'CAT', 'DE', 'ETN', 'PH', 'ROK', 'HON', 'EMR', 'UNP', 'UPS', 'FDX', 'URI'],
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
  '3721': 'DEFENSE_AERO',
  '2834': 'HEALTHCARE_BIO',
  '3841': 'HEALTHCARE_BIO',
  '6021': 'FINANCIALS',
  '6022': 'FINANCIALS',
  '6029': 'FINANCIALS',
  '6211': 'FINANCIALS',
  '6282': 'FINANCIALS',
  '1311': 'ENERGY_SUPPLY',
  '1389': 'ENERGY_SUPPLY',
  '2911': 'ENERGY_SUPPLY',
  '3312': 'MATERIALS_RESOURCES',
  '3334': 'MATERIALS_RESOURCES',
  '2810': 'MATERIALS_RESOURCES',
  '4911': 'UTILITIES_DEFENSIVE',
  '4932': 'UTILITIES_DEFENSIVE',
  '6798': 'REAL_ASSETS',
  '6512': 'REAL_ASSETS',
  '3714': 'CONSUMER_FRANCHISE',
  '5311': 'CONSUMER_FRANCHISE',
  '5812': 'CONSUMER_FRANCHISE',
  '4841': 'COMMUNICATION_MEDIA',
  '4833': 'COMMUNICATION_MEDIA',
  '4899': 'COMMUNICATION_MEDIA',
  '2086': 'CONSUMER_STAPLES',
  '2099': 'CONSUMER_STAPLES',
  '5140': 'CONSUMER_STAPLES',
  '3531': 'INDUSTRIALS_CYCLICAL',
  '3533': 'INDUSTRIALS_CYCLICAL',
  '4512': 'INDUSTRIALS_CYCLICAL',
};

const NAME_GROUP_RULES: Array<{ pattern: RegExp; peerGroup: string }> = [
  { pattern: /(semiconductor|micro devices|broadcom|marvell|nvidia|taiwan semiconductor|asml|applied materials|lam research|kla|micron|arm|qualcomm)/i, peerGroup: 'AI_SEMIS' },
  { pattern: /(google|alphabet|meta|microsoft|amazon|cloud|platform|software|oracle|salesforce|service now|adobe|netflix|uber)/i, peerGroup: 'MEGACAP_PLATFORM' },
  { pattern: /(vertiv|eaton|hubbell|nvent|grid|electric|power|transformer|emcor|quanta|carrier|johnson controls|trane)/i, peerGroup: 'POWER_INFRA' },
  { pattern: /(equipment|etch|deposition|lithography|fab|semicap)/i, peerGroup: 'SEMI_EQUIPMENT' },
  { pattern: /(lockheed|northrop|raytheon|general dynamics|boeing|defense|aerospace|kratos|aero vironment)/i, peerGroup: 'DEFENSE_AERO' },
  { pattern: /(eli lilly|novo|isrg|unitedhealth|merck|abbvie|thermo fisher|danaher|vertex|health|pharma|biotech)/i, peerGroup: 'HEALTHCARE_BIO' },
  { pattern: /(jpmorgan|bank|wells fargo|goldman|morgan stanley|blackrock|apollo|visa|mastercard|amex|intercontinental exchange|cme|charles schwab)/i, peerGroup: 'FINANCIALS' },
  { pattern: /(exxon|chevron|conoco|eog|slb|baker hughes|halliburton|midstream|pipeline|refining|energy)/i, peerGroup: 'ENERGY_SUPPLY' },
  { pattern: /(google|alphabet|meta|netflix|telecom|wireless|verizon|at&t|comcast|disney|spotify|roku|pins|snap|charter|warner|media|communication)/i, peerGroup: 'COMMUNICATION_MEDIA' },
  { pattern: /(amazon|tesla|home depot|mcdonald|nike|starbucks|booking|tjx|ross|autozone|oreilly|lululemon|restaurant|travel|consumer discretionary|retail)/i, peerGroup: 'CONSUMER_FRANCHISE' },
  { pattern: /(procter|coca-cola|pepsico|walmart|costco|philip morris|altria|colgate|mondelez|general mills|kimberly|kroger|kraft|hershey|staples|beverage|household)/i, peerGroup: 'CONSUMER_STAPLES' },
  { pattern: /(linde|air products|sherwin|ecolab|freeport|newmont|nucor|albemarle|materials|steel|mining|chemical)/i, peerGroup: 'MATERIALS_RESOURCES' },
  { pattern: /(next era|southern|duke|utility|electricity|water|constellation energy|vistra|nrg|regulated)/i, peerGroup: 'UTILITIES_DEFENSIVE' },
  { pattern: /(prologis|american tower|equinix|simon property|realty income|welltower|digital realty|cell tower|reit|real estate)/i, peerGroup: 'REAL_ASSETS' },
  { pattern: /(caterpillar|deere|honeywell|emerson|railroad|logistics|industrial|machinery|equipment rental|united rentals)/i, peerGroup: 'INDUSTRIALS_CYCLICAL' },
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

function inferPeerGroupFromTickerMembership(ticker: string): string | null {
  const normalized = ticker.toUpperCase();
  for (const [peerGroup, candidates] of Object.entries(PEER_GROUP_CANDIDATES)) {
    if (candidates.includes(normalized)) return peerGroup;
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
    { ticker: 'NVDA', relation: 'AI 인프라', peerGroup: 'AI_SEMIS' },
    { ticker: 'AMD', relation: '반도체 peer', peerGroup: 'AI_SEMIS' },
    { ticker: 'MRVL', relation: '네트워킹/커스텀칩', peerGroup: 'AI_SEMIS' },
  ],
  MSFT: [
    { ticker: 'GOOGL', relation: '클라우드/AI 플랫폼', peerGroup: 'MEGACAP_PLATFORM' },
    { ticker: 'AMZN', relation: '클라우드 peer', peerGroup: 'MEGACAP_PLATFORM' },
    { ticker: 'META', relation: 'AI CAPEX 대형주', peerGroup: 'MEGACAP_PLATFORM' },
  ],
  GOOGL: [
    { ticker: 'MSFT', relation: '클라우드/AI 플랫폼', peerGroup: 'MEGACAP_PLATFORM' },
    { ticker: 'META', relation: '광고/AI 대형주', peerGroup: 'MEGACAP_PLATFORM' },
    { ticker: 'AMZN', relation: '클라우드 peer', peerGroup: 'MEGACAP_PLATFORM' },
  ],
  META: [
    { ticker: 'GOOGL', relation: '광고 플랫폼', peerGroup: 'MEGACAP_PLATFORM' },
    { ticker: 'MSFT', relation: 'AI CAPEX 대형주', peerGroup: 'MEGACAP_PLATFORM' },
    { ticker: 'AMZN', relation: '대형 기술주 peer', peerGroup: 'MEGACAP_PLATFORM' },
  ],
  ASML: [
    { ticker: 'AMAT', relation: '반도체 장비', peerGroup: 'SEMI_EQUIPMENT' },
    { ticker: 'LRCX', relation: '반도체 장비', peerGroup: 'SEMI_EQUIPMENT' },
    { ticker: 'TSM', relation: '반도체 생태계', peerGroup: 'SEMI_EQUIPMENT' },
  ],
  TSM: [
    { ticker: 'ASML', relation: '생산 생태계', peerGroup: 'AI_SEMIS' },
    { ticker: 'NVDA', relation: 'AI 수요 연동', peerGroup: 'AI_SEMIS' },
    { ticker: 'INTC', relation: '파운드리 경쟁', peerGroup: 'AI_SEMIS' },
  ],
  LMT: [
    { ticker: 'NOC', relation: '미 방산 peer', peerGroup: 'DEFENSE_AERO' },
    { ticker: 'RTX', relation: '항공우주/방산', peerGroup: 'DEFENSE_AERO' },
    { ticker: 'GD', relation: '방산 peer', peerGroup: 'DEFENSE_AERO' },
  ],
  LLY: [
    { ticker: 'NVO', relation: '비만/당뇨 경쟁', peerGroup: 'HEALTHCARE_BIO' },
    { ticker: 'MRK', relation: '대형 제약', peerGroup: 'HEALTHCARE_BIO' },
    { ticker: 'ABBV', relation: '대형 제약 peer', peerGroup: 'HEALTHCARE_BIO' },
  ],
};

export function getCompanyPeers(ticker: string): CompanyPeerTarget[] {
  return PEER_MAP[ticker.toUpperCase()] ?? [];
}

export function inferCompanyPeerGroup(input: PeerAutoExpansionInput): string | null {
  return inferPeerGroup(input)
    ?? inferPeerGroupFromTickerMembership(input.ticker)
    ?? getCompanyPeers(input.ticker).find((item) => item.peerGroup)?.peerGroup
    ?? null;
}

export function getAutoExpandedCompanyPeers(input: PeerAutoExpansionInput): CompanyPeerTarget[] {
  const baseTicker = input.ticker.toUpperCase();
  const staticPeers = getCompanyPeers(baseTicker);
  const inferredGroup = inferCompanyPeerGroup(input);
  const expanded = inferredGroup && PEER_GROUP_CANDIDATES[inferredGroup]
    ? PEER_GROUP_CANDIDATES[inferredGroup].map((ticker) => ({
        ticker,
        relation: `${inferredGroup} 산업군 비교`,
        peerGroup: inferredGroup,
      }))
    : [];
  return dedupePeers([...staticPeers, ...expanded], baseTicker).slice(0, 10);
}

export const RESEARCH_THEME_GROUPS: ResearchThemeGroup[] = [
  {
    id: 'ai-semiconductors',
    theme: 'AI / 반도체',
    description: 'AI CAPEX 및 반도체 공급망 핵심 상위 기업',
    tickers: ['NVDA', 'AMD', 'AVGO', 'TSM', 'ASML', 'MRVL', 'QCOM', 'MU', 'ARM', 'INTC', 'ADI', 'MCHP', 'AMAT', 'LRCX', 'KLAC', 'NXPI', 'ON', 'MPWR', 'TER', 'ONTO', 'ACLS', 'FORM', 'UCTT', 'ENTG', 'COHU', 'CAMT', 'NVMI', 'MKSI', 'ICHR', 'AEHR', 'VECO', 'LSCC', 'AMKR', 'SWKS', 'QRVO', 'WOLF', 'GFS', 'ALAB', 'CRDO', 'SOUN'],
    sectorKeys: ['SECTOR_XLK', 'SECTOR_SOXX', 'SECTOR_SMH'],
  },
  {
    id: 'megacap-platform-saas',
    theme: '메가캡 플랫폼 / SaaS',
    description: '클라우드·광고·AI 플랫폼과 대표 소프트웨어 대형주',
    tickers: ['MSFT', 'GOOGL', 'META', 'AMZN', 'ORCL', 'CRM', 'NOW', 'ADBE', 'NFLX', 'UBER', 'SHOP', 'INTU', 'PANW', 'CRWD', 'SNOW', 'MDB', 'DDOG', 'ZS', 'NET', 'TEAM', 'AAPL', 'SAP', 'IBM', 'WDAY', 'HUBS', 'OKTA', 'FTNT', 'PLTR', 'APP', 'DUOL', 'TTD', 'XYZ', 'PYPL', 'DOCU', 'ESTC', 'GTLB', 'TOST', 'TWLO', 'PATH', 'SMCI'],
    sectorKeys: ['SECTOR_XLK', 'SECTOR_XLC'],
  },
  {
    id: 'power-infra',
    theme: '전력 / 인프라',
    description: '전력망, 냉각, 전기설비, 데이터센터 인프라 대표 기업',
    tickers: ['VRT', 'ETN', 'HUBB', 'NVT', 'GEV', 'PWR', 'EME', 'JCI', 'TT', 'CARR', 'PH', 'ROK', 'GNRC', 'MYRG', 'FIX', 'MTZ', 'FELE', 'VST', 'CEG', 'NEE', 'NRG', 'PEG', 'AEP', 'D', 'DUK', 'SO', 'SRE', 'XEL', 'ETR', 'WEC', 'PCG', 'EXC', 'CMS', 'AES', 'PRIM', 'IESC', 'MIR', 'POWL', 'FLR', 'CBT'],
    sectorKeys: ['SECTOR_GRID', 'SECTOR_IGF', 'SECTOR_XLU', 'SECTOR_XLI'],
  },
  {
    id: 'semiconductor-equipment',
    theme: '반도체 장비',
    description: '웨이퍼 공정/검사/패키징 장비 중심 상위 기업',
    tickers: ['ASML', 'AMAT', 'LRCX', 'KLAC', 'TER', 'ONTO', 'ACLS', 'FORM', 'UCTT', 'TSM', 'ENTG', 'COHU', 'CAMT', 'NVMI', 'MKSI', 'ICHR', 'AEHR', 'VECO', 'LSCC', 'AMKR', 'RMBS', 'ASX', 'HIMX', 'SYNA', 'ALGM', 'DIOD', 'IPGP', 'OLED', 'KLIC', 'MTSI', 'AXTI', 'POWI', 'SMTC', 'SITM', 'CRUS', 'IMOS', 'STM', 'NDSN', 'CGNX', 'KEYS'],
    sectorKeys: ['SECTOR_SOXX', 'SECTOR_SMH', 'SECTOR_XLI'],
  },
  {
    id: 'defense-aerospace',
    theme: '방산 / 항공우주',
    description: '지정학 리스크와 재무장 수혜 대표 기업',
    tickers: ['LMT', 'NOC', 'RTX', 'GD', 'BA', 'LHX', 'HII', 'KTOS', 'AVAV', 'CW', 'TXT', 'TDG', 'HEI', 'HWM', 'LDOS', 'MRCY', 'PLTR', 'OSIS', 'AXON', 'BWXT', 'GE', 'HON', 'EMR', 'TDY', 'ESLT', 'CAE', 'RKLB', 'IRDM', 'VSAT', 'ACHR', 'JOBY', 'SPR', 'ATI', 'NDSN', 'CWST', 'HXL', 'MOG.A', 'CRS', 'SAIC', 'CACI'],
    sectorKeys: ['SECTOR_ITA', 'SECTOR_XLI'],
  },
  {
    id: 'healthcare-biotech',
    theme: '헬스케어 / 바이오',
    description: '대형 제약, 의료장비, 비만/고부가 치료 대표 기업',
    tickers: ['LLY', 'NVO', 'ISRG', 'UNH', 'JNJ', 'MRK', 'ABBV', 'TMO', 'DHR', 'VRTX', 'REGN', 'AMGN', 'GILD', 'BMY', 'PFE', 'SYK', 'MDT', 'BSX', 'ABT', 'ZBH', 'CI', 'ELV', 'CVS', 'HCA', 'IQV', 'DXCM', 'EW', 'STE', 'IDXX', 'RMD', 'BIIB', 'ALNY', 'MRNA', 'NBIX', 'VEEV', 'WST', 'PODD', 'GEHC', 'CAH', 'MCK'],
    sectorKeys: ['SECTOR_XLV'],
  },
];

export const RESEARCH_STANDARD_SECTORS: ResearchSectorGroup[] = [
  { id: 'technology', label: '기술', description: '소프트웨어, IT 서비스, 대형 기술 플랫폼 전반', sectorKey: 'SECTOR_XLK', tickers: ['MSFT','AAPL','NVDA','AVGO','ORCL','CRM','NOW','ADBE','INTU','PANW','CRWD','SNOW','AMD','QCOM','TXN','MU','AMAT','LRCX','KLAC','ANET','IBM','PLTR','SMCI','WDAY','FTNT','ZS','NET','MDB','DDOG','TEAM','GTLB','DOCU','HUBS','OKTA','SAP','APP','DUOL','TOST','PATH','TWLO'] },
  { id: 'communication-services', label: '커뮤니케이션', description: '광고, 플랫폼, 미디어/인터넷 커뮤니케이션', sectorKey: 'SECTOR_XLC', tickers: ['GOOGL','META','NFLX','TMUS','VZ','T','CMCSA','DIS','SPOT','ROKU','PINS','SNAP','TTWO','EA','WBD','CHTR','DASH','UBER','LYV','FOXA','FOX','BIDU','IAC','MTCH','PSKY','TKO','SIRI','NYT','OMC','NWSA','NWS','FWONA','FWONK','TU','CCOI','NXST','SBGI','LBRDK','LBRDA','RBLX'] },
  { id: 'financials', label: '금융', description: '은행, 보험, 자산운용, 결제 등 금융주', sectorKey: 'SECTOR_XLF', tickers: ['JPM','BAC','WFC','GS','MS','BLK','BX','KKR','SCHW','C','AXP','V','MA','PYPL','ICE','CME','PGR','AON','MMC','CB','USB','PNC','TFC','COF','BNY','STT','AIG','MET','PRU','ALL','TRV','AJG','SPGI','MCO','MSCI','BRO','NDAQ','DFS','RJF','FITB'] },
  { id: 'energy', label: '에너지', description: '정유, E&P, 오일서비스 등 에너지 섹터', sectorKey: 'SECTOR_XLE', tickers: ['XOM','CVX','COP','EOG','SLB','MPC','PSX','VLO','OXY','KMI','WMB','BKR','HAL','DVN','FANG','APA','EQT','CTRA','OKE','TRGP','EPD','CNQ','CQP','PR','AR','RRC','EXE','LNG','ET','PAA','WES','CHRD','SM','MTDR','VTLE','OVV','CNX','MPLX','SUN','TALO'] },
  { id: 'industrials', label: '산업재', description: '방산, 운송, 자본재, 설비투자 사이클', sectorKey: 'SECTOR_XLI', tickers: ['GE','CAT','DE','RTX','LMT','NOC','GD','ETN','PH','ROK','PWR','EMR','HON','TT','BA','UNP','UPS','FDX','URI','JCI','LHX','HII','TDG','HEI','HWM','AXON','BWXT','GEV','VRT','HUBB','EME','FIX','MTZ','MYRG','CARR','IR','XYL','DOV','ITW','CMI'] },
  { id: 'consumer-discretionary', label: '임의소비재', description: '경기민감 소비와 플랫폼 소비주', sectorKey: 'SECTOR_XLY', tickers: ['AMZN','TSLA','HD','MCD','NKE','SBUX','BKNG','LOW','TJX','ROST','AZO','ORLY','CMG','MAR','HLT','LULU','YUM','DRI','EBAY','DPZ','RCL','CCL','NCLH','ABNB','LEN','DHI','PHM','MGM','WYNN','LVS','ULTA','DECK','ETSY','BBY','GM','F','NVR','CHDN','POOL','WSM'] },
  { id: 'materials', label: '소재', description: '화학, 금속, 원자재 및 소재 업종', sectorKey: 'SECTOR_XLB', tickers: ['LIN','APD','SHW','ECL','NEM','FCX','NUE','DD','DOW','CTVA','MLM','VMC','IFF','ALB','STLD','BALL','PKG','CF','MOS','LYB','RPM','EMN','FMC','NTR','TECK','SCCO','AA','RS','CLF','EXP','CMC','AXTA','OLN','IP','SW','SON','CCK','GEF','AMCR','AVY'] },
  { id: 'healthcare', label: '헬스케어', description: '대형 제약, 의료장비, 바이오헬스', sectorKey: 'SECTOR_XLV', tickers: ['LLY','NVO','UNH','JNJ','MRK','ABBV','ISRG','TMO','DHR','VRTX','REGN','AMGN','GILD','BMY','PFE','SYK','MDT','BSX','ABT','ZBH','CI','ELV','CVS','HCA','IQV','DXCM','EW','STE','IDXX','RMD','BIIB','ALNY','MRNA','NBIX','VEEV','WST','PODD','GEHC','CAH','MCK'] },
  { id: 'utilities', label: '유틸리티', description: '전력, 가스, 방어형 배당 섹터', sectorKey: 'SECTOR_XLU', tickers: ['NEE','SO','DUK','AEP','D','SRE','XEL','PEG','PCG','ED','WEC','EIX','AWK','ETR','EXC','CMS','AES','CEG','VST','NRG','ES','EVRG','LNT','PNW','ATO','NI','CNP','AEE','FE','OGE','PPL','DTE','UGI','ORA','BKH','IDA','NWE','CPK','POR','OTTR'] },
  { id: 'consumer-staples', label: '필수소비재', description: '생활필수품, 유통, 안정적 소비 섹터', sectorKey: 'SECTOR_XLP', tickers: ['PG','KO','PEP','WMT','COST','PM','MO','CL','MDLZ','GIS','KMB','KR','KHC','MNST','EL','HSY','SYY','ADM','TSN','CHD','DG','DLTR','UL','TGT','MKC','PPC','CPB','HRL','CLX','CAG','BG','BF.B','TAP','STZ','KDP','SJM','LW','CELH','BJ','USFD'] },
  { id: 'real-estate', label: '부동산/리츠', description: 'REITs 및 부동산 관련 자산', sectorKey: 'SECTOR_XLRE', tickers: ['PLD','AMT','EQIX','SPG','O','WELL','DLR','PSA','CCI','VICI','AVB','EQR','SBAC','EXR','ARE','IRM','CBRE','WY','INVH','KIM','MAA','ESS','UDR','CPT','BXP','VTR','DOC','REG','FRT','OHI','HST','RHP','LAMR','CSGP','JLL','STWD','NLY','AGNC','KRC','VNO'] },
];

export function getResearchThemeById(id: string): ResearchThemeGroup | null {
  const normalized = slugifyTheme(id);
  return RESEARCH_THEME_GROUPS.find((item) => item.id === normalized || slugifyTheme(item.theme) === normalized) ?? null;
}

export function getResearchThemes(): ResearchThemeGroup[] {
  return RESEARCH_THEME_GROUPS;
}
export function getResearchThemeForSectorKey(sectorKey: string): ResearchThemeGroup | null {
  return RESEARCH_THEME_GROUPS.find((theme) => theme.sectorKeys.includes(sectorKey)) ?? null;
}

export function getResearchStandardSectors(): ResearchSectorGroup[] {
  return RESEARCH_STANDARD_SECTORS;
}

export function getResearchSectorById(id: string): ResearchSectorGroup | null {
  const normalized = slugifyTheme(id);
  return RESEARCH_STANDARD_SECTORS.find((item) => item.id === normalized || slugifyTheme(item.label) === normalized) ?? null;
}

export function inferResearchSectorForTicker(ticker: string): ResearchSectorGroup | null {
  const normalized = ticker.toUpperCase();
  return RESEARCH_STANDARD_SECTORS.find((item) => item.tickers.includes(normalized)) ?? null;
}

export function getResearchThemesForSectorKey(sectorKey: string): ResearchThemeGroup[] {
  return RESEARCH_THEME_GROUPS.filter((theme) => theme.sectorKeys.includes(sectorKey));
}

export function getResearchCompanyUniverse(): string[] {
  return [
    ...new Set([
      ...RESEARCH_THEME_GROUPS.flatMap((theme) => theme.tickers),
      ...RESEARCH_STANDARD_SECTORS.flatMap((sector) => sector.tickers),
    ].map((ticker) => ticker.toUpperCase())),
  ];
}
