export interface BottleneckKeywordRule {
  label: string;
  score: number;
  cap?: number;
  patterns: RegExp[];
  reason: string;
}

export const BOTTLENECK_KEYWORD_RULES: BottleneckKeywordRule[] = [
  {
    label: 'supply-constraint',
    score: 1.4,
    cap: 3,
    patterns: [/supply constraint/gi, /supply constrained/gi, /capacity constraint/gi, /tight supply/gi, /scarce/gi, /constrained supply/gi],
    reason: '공급 제약/타이트 서플라이 언급',
  },
  {
    label: 'lead-time',
    score: 1.1,
    cap: 3,
    patterns: [/lead time/gi, /long lead/gi, /extended lead/gi, /lead times/gi],
    reason: '리드타임 장기화 언급',
  },
  {
    label: 'backlog',
    score: 1.0,
    cap: 3,
    patterns: [/backlog/gi, /book-to-bill/gi, /order book/gi, /bookings/gi],
    reason: '수주잔고/백로그 언급',
  },
  {
    label: 'pricing-power',
    score: 0.9,
    cap: 3,
    patterns: [/pricing power/gi, /price increase/gi, /favorable pricing/gi, /price realization/gi, /price discipline/gi],
    reason: '가격 전가력/가격 인상 언급',
  },
  {
    label: 'sole-source',
    score: 1.5,
    cap: 2,
    patterns: [/sole source/gi, /single source/gi, /only supplier/gi, /mission critical/gi, /unique capability/gi],
    reason: '대체 어려운 공급자 포지션 언급',
  },
  {
    label: 'capex-linkage',
    score: 1.0,
    cap: 4,
    patterns: [/data center/gi, /ai infrastructure/gi, /grid modernization/gi, /capacity expansion/gi, /fab expansion/gi, /rearm/gi, /electrification/gi],
    reason: '대형 CAPEX/정책 수요 연동 언급',
  },
  {
    label: 'qualification-lockin',
    score: 1.1,
    cap: 3,
    patterns: [/qualification/gi, /qualified supplier/gi, /design win/gi, /installed base/gi, /certification/gi],
    reason: '고객 인증/설치기반 락인 언급',
  },
  {
    label: 'yield-or-process',
    score: 0.8,
    cap: 3,
    patterns: [/yield/gi, /process control/gi, /metrology/gi, /throughput/gi],
    reason: '수율/공정 제어 중요도 언급',
  },
];
