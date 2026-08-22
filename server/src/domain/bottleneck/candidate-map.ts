import { BottleneckThemeDefinition } from '../../types/bottleneck';

export const BOTTLENECK_THEME_DEFINITIONS: BottleneckThemeDefinition[] = [
  {
    id: 'cooling-thermal-chain',
    title: '냉각 / 열관리 병목',
    description: 'AI 서버 전력 밀도 상승에 따른 냉각, HVAC, 열관리 공급망 병목 후보',
    tickers: [
      { ticker: 'VRT', role: '열관리/데이터센터 냉각', theme: '냉각', tags: ['liquid-cooling','thermal'], priors: { concentration: 7, supplyTightness: 8, capexLinkage: 9, switchingCost: 7 } },
      { ticker: 'TT', role: '산업/상업 HVAC', theme: '냉각', tags: ['hvac','cooling'], priors: { concentration: 6, supplyTightness: 6, capexLinkage: 8, switchingCost: 6 } },
      { ticker: 'CARR', role: '냉각/공조', theme: '냉각', tags: ['cooling','chiller'], priors: { concentration: 6, supplyTightness: 6, capexLinkage: 8, switchingCost: 6 } },
      { ticker: 'JCI', role: '빌딩 냉각/제어', theme: '냉각', tags: ['cooling','controls'], priors: { concentration: 5, supplyTightness: 5, capexLinkage: 7, switchingCost: 6 } },
      { ticker: 'ETN', role: '전력 품질/열부하 인프라', theme: '냉각', tags: ['power-quality','thermal-load'], priors: { concentration: 7, supplyTightness: 7, capexLinkage: 8, switchingCost: 6 } },
      { ticker: 'PH', role: '유체/열관리', theme: '냉각', tags: ['fluid','thermal-management'], priors: { concentration: 7, supplyTightness: 6, capexLinkage: 7, switchingCost: 6 } },
      { ticker: 'NVT', role: '인클로저/열관리', theme: '냉각', tags: ['enclosure','thermal'], priors: { concentration: 6, supplyTightness: 7, capexLinkage: 8, switchingCost: 6 } },
      { ticker: 'ROK', role: '산업 자동화/열효율', theme: '냉각', tags: ['automation','efficiency'], priors: { concentration: 6, supplyTightness: 5, capexLinkage: 7, switchingCost: 6 } },
      { ticker: 'EME', role: '냉각/기계설비 시공', theme: '냉각', tags: ['mechanical','cooling-install'], priors: { concentration: 5, supplyTightness: 5, capexLinkage: 7, switchingCost: 5 } },
      { ticker: 'PWR', role: '데이터센터 인프라 EPC', theme: '냉각', tags: ['epc','data-center'], priors: { concentration: 6, supplyTightness: 6, capexLinkage: 8, switchingCost: 5 } },
    ],
  },
  {
    id: 'ai-power-grid',
    title: 'AI 전력 / 전력망 병목',
    description: '데이터센터 전력 증설, 변압기, 배전, 전력장비 CAPEX의 병목 후보',
    tickers: [
      { ticker: 'VRT', role: '데이터센터 전력/냉각', theme: 'AI 전력', tags: ['cooling','power-density','data-center'], priors: { concentration: 7, supplyTightness: 8, capexLinkage: 9, switchingCost: 7 } },
      { ticker: 'ETN', role: '전력 분배/전장', theme: 'AI 전력', tags: ['switchgear','electrification'], priors: { concentration: 7, supplyTightness: 7, capexLinkage: 9, switchingCost: 6 } },
      { ticker: 'HUBB', role: '배전/그리드 장비', theme: 'AI 전력', tags: ['transformer','distribution-grid'], priors: { concentration: 7, supplyTightness: 8, capexLinkage: 8, switchingCost: 7 } },
      { ticker: 'NVT', role: '전력 인프라/인클로저', theme: 'AI 전력', priors: { concentration: 6, supplyTightness: 7, capexLinkage: 8 } },
      { ticker: 'PWR', role: '전력 EPC / 데이터센터 인프라', theme: 'AI 전력', priors: { concentration: 6, supplyTightness: 6, capexLinkage: 8 } },
      { ticker: 'EME', role: '전력/설비 시공', theme: 'AI 전력', priors: { concentration: 5, supplyTightness: 6, capexLinkage: 8 } },
      { ticker: 'GEV', role: '전력장비/그리드 현대화', theme: 'AI 전력', priors: { concentration: 6, supplyTightness: 7, capexLinkage: 8 } },
      { ticker: 'NEE', role: '전력 증설/데이터센터 수요 수혜 유틸리티', theme: 'AI 전력', tags: ['utility-scale','power-demand'], priors: { concentration: 6, supplyTightness: 6, capexLinkage: 8, switchingCost: 6 } },
      { ticker: 'CEG', role: '원전/기저전력 공급 병목', theme: 'AI 전력', tags: ['nuclear','baseload'], priors: { concentration: 6, supplyTightness: 7, capexLinkage: 8, switchingCost: 6 } },
      { ticker: 'VST', role: '전력 수급/민간 발전 병목', theme: 'AI 전력', tags: ['merchant-power','generation'], priors: { concentration: 5, supplyTightness: 6, capexLinkage: 7, switchingCost: 5 } },
      { ticker: 'PH', role: '전력 품질/자동화', theme: 'AI 전력', priors: { concentration: 7, supplyTightness: 6, capexLinkage: 7 } },
      { ticker: 'ROK', role: '산업 자동화/전력 효율', theme: 'AI 전력', priors: { concentration: 6, supplyTightness: 5, capexLinkage: 7 } },
      { ticker: 'JCI', role: '빌딩 전력/냉각 인프라', theme: 'AI 전력', priors: { concentration: 5, supplyTightness: 5, capexLinkage: 7 } },
    ],
  },
  {
    id: 'semi-equipment-eda',
    title: '반도체 장비 / EDA 병목',
    description: '반도체 공급망에서 대체가 어려운 장비/설계 툴 병목 후보',
    tickers: [
      { ticker: 'ASML', role: '노광 장비', theme: '반도체 장비', tags: ['lithography','euv'], priors: { concentration: 10, supplyTightness: 9, capexLinkage: 9, switchingCost: 10 } },
      { ticker: 'AMAT', role: '증착/식각/패키징 장비', theme: '반도체 장비', priors: { concentration: 7, supplyTightness: 7, capexLinkage: 9 } },
      { ticker: 'LRCX', role: '식각 장비', theme: '반도체 장비', priors: { concentration: 7, supplyTightness: 7, capexLinkage: 9 } },
      { ticker: 'KLAC', role: '검사/계측 장비', theme: '반도체 장비', priors: { concentration: 8, supplyTightness: 7, capexLinkage: 8 } },
      { ticker: 'TER', role: '테스트 장비', theme: '반도체 장비', priors: { concentration: 6, supplyTightness: 6, capexLinkage: 7 } },
      { ticker: 'ONTO', role: '계측/검사', theme: '반도체 장비', priors: { concentration: 6, supplyTightness: 6, capexLinkage: 7 } },
      { ticker: 'FORM', role: '패키징/테스트', theme: '반도체 장비', priors: { concentration: 5, supplyTightness: 6, capexLinkage: 7 } },
      { ticker: 'CDNS', role: 'EDA 소프트웨어', theme: 'EDA', tags: ['eda','design-flow','ip'], priors: { concentration: 9, supplyTightness: 7, capexLinkage: 8, switchingCost: 9 } },
      { ticker: 'SNPS', role: 'EDA/IP', theme: 'EDA', tags: ['eda','verification','ip'], priors: { concentration: 9, supplyTightness: 7, capexLinkage: 8, switchingCost: 9 } },
      { ticker: 'ACLS', role: '이온주입 장비', theme: '반도체 장비', priors: { concentration: 5, supplyTightness: 6, capexLinkage: 6 } },
      { ticker: 'NVDA', role: 'AI 가속기 병목 수요의 핵심 종착지', theme: 'AI 반도체', tags: ['ai-accelerator','installed-base','design-win'], priors: { concentration: 8, supplyTightness: 7, capexLinkage: 9, switchingCost: 8 } },
      { ticker: 'AVGO', role: 'AI 네트워킹/커스텀칩 병목', theme: 'AI 반도체', tags: ['networking','custom-silicon','switching-cost'], priors: { concentration: 8, supplyTightness: 7, capexLinkage: 8, switchingCost: 8 } },
      { ticker: 'TSM', role: '첨단 공정 생산 병목', theme: '파운드리', tags: ['advanced-node','foundry','capacity'], priors: { concentration: 9, supplyTightness: 9, capexLinkage: 9, switchingCost: 9 } },
      { ticker: 'LIN', role: '반도체/산업용 가스 병목', theme: '산업가스', tags: ['industrial-gas','process-critical'], priors: { concentration: 8, supplyTightness: 7, capexLinkage: 7, switchingCost: 8 } },
      { ticker: 'APD', role: '고순도 산업가스 병목', theme: '산업가스', tags: ['industrial-gas','specialty-gas'], priors: { concentration: 7, supplyTightness: 7, capexLinkage: 7, switchingCost: 7 } },
    ],
  },
  {
    id: 'defense-critical-systems',
    title: '방산 핵심 시스템 병목',
    description: '방산 재무장 구간에서 핵심 플랫폼과 미션크리티컬 공급망 후보',
    tickers: [
      { ticker: 'LMT', role: '전투기/미사일 플랫폼', theme: '방산', tags: ['fighter','missile','platform'], priors: { concentration: 8, supplyTightness: 7, capexLinkage: 8, switchingCost: 8 } },
      { ticker: 'NOC', role: '우주/미사일/레이더', theme: '방산', priors: { concentration: 8, supplyTightness: 7, capexLinkage: 8 } },
      { ticker: 'RTX', role: '엔진/미사일/센서', theme: '방산', priors: { concentration: 7, supplyTightness: 7, capexLinkage: 8 } },
      { ticker: 'GD', role: '잠수함/전차', theme: '방산', priors: { concentration: 7, supplyTightness: 6, capexLinkage: 8 } },
      { ticker: 'LHX', role: '통신/ISR', theme: '방산', priors: { concentration: 7, supplyTightness: 6, capexLinkage: 7 } },
      { ticker: 'HII', role: '조선/잠수함', theme: '방산', priors: { concentration: 8, supplyTightness: 7, capexLinkage: 8 } },
      { ticker: 'KTOS', role: '무인기/전술시스템', theme: '방산', priors: { concentration: 5, supplyTightness: 6, capexLinkage: 7 } },
      { ticker: 'AVAV', role: '드론/무인기', theme: '방산', priors: { concentration: 5, supplyTightness: 5, capexLinkage: 7 } },
      { ticker: 'CW', role: '항공우주 부품', theme: '방산', priors: { concentration: 6, supplyTightness: 6, capexLinkage: 7 } },
      { ticker: 'TDG', role: '항공 부품/애프터마켓', theme: '방산', priors: { concentration: 7, supplyTightness: 6, capexLinkage: 6 } },
      { ticker: 'GE', role: '항공 엔진/방산 연계 시스템', theme: '방산', tags: ['aero-engine','critical-system'], priors: { concentration: 7, supplyTightness: 6, capexLinkage: 7, switchingCost: 7 } },
    ],
  },
  {
    id: 'digital-platform-moats',
    title: '디지털 플랫폼 / 네트워크 병목',
    description: '통신 인프라, 스트리밍 배급, 광고 플랫폼에서 네트워크 효과와 전환비용이 큰 후보',
    tickers: [
      { ticker: 'TMUS', role: '무선 네트워크 품질/가입자 락인', theme: '통신', tags: ['wireless','subscriber-lockin'], priors: { concentration: 6, supplyTightness: 4, capexLinkage: 6, switchingCost: 8 } },
      { ticker: 'CMCSA', role: '브로드밴드 라스트마일 인프라', theme: '통신', tags: ['broadband','last-mile'], priors: { concentration: 7, supplyTightness: 5, capexLinkage: 6, switchingCost: 8 } },
      { ticker: 'NFLX', role: '글로벌 스트리밍 배급 플랫폼', theme: '미디어', tags: ['streaming','distribution-platform'], priors: { concentration: 7, supplyTightness: 3, capexLinkage: 4, switchingCost: 7 } },
      { ticker: 'GOOGL', role: '광고/검색 플랫폼 유통 병목', theme: '디지털 광고', tags: ['search','advertising-platform'], priors: { concentration: 9, supplyTightness: 2, capexLinkage: 5, switchingCost: 8 } },
      { ticker: 'META', role: '소셜 광고/추천 알고리즘 네트워크 효과', theme: '디지털 광고', tags: ['social-graph','advertising-platform'], priors: { concentration: 8, supplyTightness: 2, capexLinkage: 5, switchingCost: 7 } },
      { ticker: 'AAPL', role: '디바이스·서비스 생태계 락인', theme: '기술', tags: ['ecosystem','installed-base'], priors: { concentration: 8, supplyTightness: 3, capexLinkage: 5, switchingCost: 9 } },
      { ticker: 'PANW', role: '보안 플랫폼 전환비용', theme: '기술', tags: ['security-platform','switching-cost'], priors: { concentration: 7, supplyTightness: 2, capexLinkage: 4, switchingCost: 8 } },
      { ticker: 'CRWD', role: '엔드포인트/클라우드 보안 플랫폼', theme: '기술', tags: ['security-platform','installed-base'], priors: { concentration: 6, supplyTightness: 2, capexLinkage: 4, switchingCost: 8 } },
      { ticker: 'ANET', role: '데이터센터 네트워크 스위칭 병목', theme: '기술', tags: ['networking','data-center'], priors: { concentration: 7, supplyTightness: 5, capexLinkage: 7, switchingCost: 7 } },
    ],
  },
  {
    id: 'consumer-healthcare-moats',
    title: '소비 / 헬스케어 플랫폼 병목',
    description: '필수소비 유통망과 의료기기 플랫폼에서 전환비용/유통망 우위가 큰 후보',
    tickers: [
      { ticker: 'COST', role: '멤버십 기반 대형 유통망', theme: '필수소비', tags: ['membership','distribution-scale'], priors: { concentration: 6, supplyTightness: 4, capexLinkage: 5, switchingCost: 7 } },
      { ticker: 'WMT', role: '생활필수품 유통망/물류 인프라', theme: '필수소비', tags: ['logistics','retail-scale'], priors: { concentration: 7, supplyTightness: 4, capexLinkage: 5, switchingCost: 7 } },
      { ticker: 'ISRG', role: '수술 로봇 설치기반/소모품 병목', theme: '헬스케어', tags: ['installed-base','procedure-lockin'], priors: { concentration: 8, supplyTightness: 5, capexLinkage: 6, switchingCost: 9 } },
      { ticker: 'SYK', role: '정형/수술장비 플랫폼', theme: '헬스케어', tags: ['medical-device','hospital-lockin'], priors: { concentration: 7, supplyTightness: 4, capexLinkage: 5, switchingCost: 8 } },
      { ticker: 'BSX', role: '시술기기/의료 플랫폼', theme: '헬스케어', tags: ['medical-device','procedure-platform'], priors: { concentration: 6, supplyTightness: 4, capexLinkage: 5, switchingCost: 7 } },
      { ticker: 'MDT', role: '만성질환 치료기기 기반', theme: '헬스케어', tags: ['therapy-platform','device-lockin'], priors: { concentration: 6, supplyTightness: 4, capexLinkage: 5, switchingCost: 7 } },
    ],
  },
  {
    id: 'consumer-travel-energy-realassets',
    title: '소비 / 에너지 / 리츠 병목',
    description: '여행 플랫폼, 에너지 서비스, 데이터센터/타워 리츠에서 공급·유통 우위가 큰 후보',
    tickers: [
      { ticker: 'BKNG', role: '여행 수요 집선 플랫폼', theme: '소비', tags: ['travel-platform','network-effect'], priors: { concentration: 7, supplyTightness: 2, capexLinkage: 4, switchingCost: 7 } },
      { ticker: 'CMG', role: '브랜드·매장 생산성 우위', theme: '소비', tags: ['brand','store-economics'], priors: { concentration: 5, supplyTightness: 2, capexLinkage: 4, switchingCost: 6 } },
      { ticker: 'HD', role: '주택 수리/자재 유통망 병목', theme: '소비', tags: ['distribution-scale','pro-customer'], priors: { concentration: 7, supplyTightness: 4, capexLinkage: 6, switchingCost: 7 } },
      { ticker: 'SLB', role: '오일서비스 기술/현장 집행 병목', theme: '에너지', tags: ['oil-service','field-tech'], priors: { concentration: 7, supplyTightness: 6, capexLinkage: 8, switchingCost: 7 } },
      { ticker: 'BKR', role: '에너지 장비/서비스 병목', theme: '에너지', tags: ['oil-service','equipment'], priors: { concentration: 6, supplyTightness: 5, capexLinkage: 7, switchingCost: 6 } },
      { ticker: 'EQIX', role: '코로케이션/인터커넥트 데이터센터 병목', theme: '리츠', tags: ['colocation','interconnection'], priors: { concentration: 8, supplyTightness: 6, capexLinkage: 8, switchingCost: 8 } },
      { ticker: 'DLR', role: '대형 데이터센터 임대 병목', theme: '리츠', tags: ['data-center','leasing'], priors: { concentration: 7, supplyTightness: 6, capexLinkage: 8, switchingCost: 7 } },
      { ticker: 'AMT', role: '타워 인프라/통신 입지 병목', theme: '리츠', tags: ['tower','wireless-site'], priors: { concentration: 8, supplyTightness: 5, capexLinkage: 7, switchingCost: 8 } },
      { ticker: 'PLD', role: '물류 부동산 네트워크 병목', theme: '리츠', tags: ['logistics-real-estate','network'], priors: { concentration: 7, supplyTightness: 5, capexLinkage: 7, switchingCost: 7 } },
      { ticker: 'WMB', role: '가스 파이프라인 네트워크 입지', theme: '에너지', tags: ['pipeline','network'], priors: { concentration: 7, supplyTightness: 6, capexLinkage: 7, switchingCost: 7 } },
      { ticker: 'TRGP', role: 'NGL 물류·처리 네트워크 병목', theme: '에너지', tags: ['ngl','midstream'], priors: { concentration: 6, supplyTightness: 6, capexLinkage: 7, switchingCost: 6 } },
      { ticker: 'SPG', role: 'A급 소매 부동산 입지', theme: '리츠', tags: ['real-estate','prime-location'], priors: { concentration: 6, supplyTightness: 4, capexLinkage: 5, switchingCost: 6 } },
      { ticker: 'WELL', role: '헬스케어 부동산 운영 네트워크', theme: '리츠', tags: ['healthcare-real-estate','network'], priors: { concentration: 6, supplyTightness: 4, capexLinkage: 5, switchingCost: 6 } },
      { ticker: 'CCI', role: '타워/파이버 입지 병목', theme: '리츠', tags: ['tower','fiber'], priors: { concentration: 7, supplyTightness: 5, capexLinkage: 6, switchingCost: 7 } },
      { ticker: 'PSA', role: '셀프스토리지 입지 네트워크', theme: '리츠', tags: ['self-storage','location-network'], priors: { concentration: 5, supplyTightness: 4, capexLinkage: 4, switchingCost: 6 } },
      { ticker: 'IRM', role: '문서보관/데이터센터 이중 락인', theme: '리츠', tags: ['records-management','data-center'], priors: { concentration: 6, supplyTightness: 4, capexLinkage: 5, switchingCost: 7 } },
      { ticker: 'CBRE', role: '상업용 부동산 운영 네트워크', theme: '리츠', tags: ['real-estate-services','network'], priors: { concentration: 5, supplyTightness: 3, capexLinkage: 4, switchingCost: 6 } },
    ],
  },
  {
    id: 'financial-infrastructure-moats',
    title: '금융 인프라 / 결제 레일 병목',
    description: '결제 네트워크, 거래소, 시장 데이터처럼 전환비용과 네트워크 효과가 큰 금융 인프라 후보',
    tickers: [
      { ticker: 'V', role: '글로벌 결제 네트워크', theme: '금융', tags: ['payments-network','cross-border'], priors: { concentration: 8, supplyTightness: 2, capexLinkage: 4, switchingCost: 9 } },
      { ticker: 'MA', role: '글로벌 결제 레일', theme: '금융', tags: ['payments-network','switching-cost'], priors: { concentration: 8, supplyTightness: 2, capexLinkage: 4, switchingCost: 9 } },
      { ticker: 'ICE', role: '거래소/시장 데이터 플랫폼', theme: '금융', tags: ['exchange','data-platform'], priors: { concentration: 8, supplyTightness: 2, capexLinkage: 4, switchingCost: 8 } },
      { ticker: 'CME', role: '파생상품 거래소 유동성 병목', theme: '금융', tags: ['exchange','liquidity-pool'], priors: { concentration: 8, supplyTightness: 2, capexLinkage: 4, switchingCost: 8 } },
      { ticker: 'PGR', role: '보험 데이터/가격 책정 엔진', theme: '금융', tags: ['insurance-data','pricing-engine'], priors: { concentration: 6, supplyTightness: 2, capexLinkage: 3, switchingCost: 7 } },
      { ticker: 'CB', role: '상업 보험 언더라이팅 플랫폼', theme: '금융', tags: ['insurance-platform','underwriting'], priors: { concentration: 6, supplyTightness: 2, capexLinkage: 3, switchingCost: 7 } },
    ],
  },
  {
    id: 'materials-utility-process-moats',
    title: '소재 / 유틸리티 공정 병목',
    description: '산업가스, 코팅, 수처리, 전력망처럼 공정에 깊게 박힌 병목 후보',
    tickers: [
      { ticker: 'SHW', role: '산업 코팅 유통/브랜드 병목', theme: '소재', tags: ['coatings','distribution'], priors: { concentration: 6, supplyTightness: 4, capexLinkage: 5, switchingCost: 6 } },
      { ticker: 'ECL', role: '수처리/위생 공정 병목', theme: '소재', tags: ['water-treatment','process-critical'], priors: { concentration: 7, supplyTightness: 5, capexLinkage: 6, switchingCost: 7 } },
      { ticker: 'NUE', role: '전기로·철강 공급 병목', theme: '소재', tags: ['steel','rebar'], priors: { concentration: 6, supplyTightness: 6, capexLinkage: 7, switchingCost: 5 } },
      { ticker: 'SO', role: '규제 전력망 기반 병목', theme: '유틸리티', tags: ['regulated-grid','baseload'], priors: { concentration: 6, supplyTightness: 5, capexLinkage: 7, switchingCost: 6 } },
      { ticker: 'DUK', role: '동남부 전력망 운영 병목', theme: '유틸리티', tags: ['regulated-grid','transmission'], priors: { concentration: 6, supplyTightness: 5, capexLinkage: 7, switchingCost: 6 } },
      { ticker: 'AEP', role: '송배전 자산 기반 병목', theme: '유틸리티', tags: ['transmission','distribution-grid'], priors: { concentration: 6, supplyTightness: 6, capexLinkage: 7, switchingCost: 6 } },
      { ticker: 'SRE', role: '미국·멕시코 에너지 인프라 병목', theme: '유틸리티', tags: ['utility','lng-infra'], priors: { concentration: 6, supplyTightness: 5, capexLinkage: 7, switchingCost: 6 } },
    ],
  },
];

export function getBottleneckThemes(): BottleneckThemeDefinition[] {
  return BOTTLENECK_THEME_DEFINITIONS;
}

export function getBottleneckThemeById(id: string): BottleneckThemeDefinition | null {
  return BOTTLENECK_THEME_DEFINITIONS.find((item) => item.id === id) ?? null;
}

export function findBottleneckCandidateByTicker(ticker: string): { themeId: string; candidate: BottleneckThemeDefinition['tickers'][number] } | null {
  const normalized = ticker.toUpperCase();
  for (const theme of BOTTLENECK_THEME_DEFINITIONS) {
    const candidate = theme.tickers.find((item) => item.ticker.toUpperCase() == normalized);
    if (candidate) return { themeId: theme.id, candidate };
  }
  return null;
}
