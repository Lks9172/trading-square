/**
 * 지표별 자동 해설 레이어 — 노션 대시보드 "now-panel" 정합 (16차 Phase 1 B1).
 *
 * derived 의 숫자 값 자체는 그대로 유지하되, 사용자에게 "지금 이 값에서 무엇을
 * 해야 하는가" 를 한 문장으로 요약. UI/텔레그램 알림에서 가장 빈번히 소비될
 * 영역이므로, 재사용 가능한 순수 함수 형태로 추출.
 *
 * 사용: `interpretDerived(key, value, context?) → string | null`
 *   - null 반환 = 해설 대상 아님 (silent)
 *   - 반환 문자열은 한글 자연어 (emoji 포함) 한 문장
 */

export interface InterpretContext {
  regime?: string;
  nasdaqDisparity?: number | null;
  kospiDisparity?: number | null;
}

/** 핵심 30+ 지표의 임계 → 해설 매핑. */
export function interpretDerived(
  key: string,
  value: number | null | undefined,
  ctx?: InterpretContext,
): string | null {
  if (value === null || value === undefined || Number.isNaN(value)) return null;

  // ═══════════════════════════════════════════════════════════════════════
  // 1) 변동성/심리 (VIX, F&G, SKEW/VVIX/OVX, TAIL_RISK)
  // ═══════════════════════════════════════════════════════════════════════
  if (key === 'VIXCLS') {
    if (value > 40) return '🔴 VIX 40+ 위기 구간 — 포지션 축소 + 분할매수 탄약 준비';
    if (value > 30) return '🟠 VIX 30+ 경계 — 방어 자산 비중 확대 고려';
    if (value > 20) return '🟡 VIX 20+ 주의 — 신규 매수 보수적';
    if (value < 15) return '🟢 VIX 15 미만 매우 안정 — 과열 신호 교차 확인';
    return '🟢 VIX 안정';
  }
  if (key === 'FEAR_GREED' || key === 'FNG_TIER') {
    const fng = key === 'FNG_TIER' ? null : value;
    const tier = key === 'FNG_TIER' ? value : null;
    if (fng !== null) {
      if (fng >= 75) return '🔴 극탐욕 구간 — 추격 매수 금지, 익절 고려';
      if (fng >= 56) return '🟠 탐욕 구간 — 신규 매수 주의';
      if (fng < 25) return '🟢 극공포 구간 — 영상1 §전략C 매수 기회 신호';
      if (fng < 45) return '🟢 공포 구간 — 분할매수 관찰';
      return '🟡 중립';
    }
    if (tier !== null) {
      if (tier >= 2) return '🔴 F&G 극탐욕 tier — 매도 주의';
      if (tier === 1) return '🟠 F&G 탐욕 tier — 신규 매수 보수적';
      if (tier <= -2) return '🟢 F&G 극공포 tier — 매수 기회';
      if (tier === -1) return '🟢 F&G 공포 tier — 분할매수 구간';
      return '🟡 F&G 중립';
    }
  }
  if (key === 'TAIL_RISK_LEVEL') {
    if (value >= 2) return '🔴 꼬리위험 고조 (SKEW/VVIX/OVX 동시) — 블랙스완 경계';
    if (value === 1) return '🟡 꼬리위험 경계 — 옵션 헷지 검토';
    return '🟢 꼬리위험 정상';
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 2) 금리/신용 (DGS10_TIER, DGS30_TIER, SPREAD, HY)
  // ═══════════════════════════════════════════════════════════════════════
  if (key === 'DGS10_TIER') {
    if (value <= -2) return '🔴 10년 금리 5%+ 경계 — 위험자산 부담 가중';
    if (value === -1) return '🟠 10년 금리 4-5% 부담 — 장기채 매력 증가';
    if (value === 1) return '🟢 10년 금리 3% 미만 저금리 — 위험자산 우호';
    return '🟡 10년 금리 중립';
  }
  if (key === 'DGS30_TIER') {
    if (value === -2) return '🔴 30년 금리 5.5%+ 심각 — 채권 자경단 발동 가능';
    if (value === -1) return '🟠 30년 금리 5-5.5% 경계';
    return '🟡 30년 금리 정상';
  }
  if (key === 'SPREAD_30Y10Y_TIER') {
    if (value === -1) return '🔴 30Y-10Y 급경사화 — 장기 금리 우려, 채권 자경단 신호';
    if (value === -2) return '🔴 30Y-10Y 역전 심화 — 장기 수요 쇼크';
    if (value === 1) return '🟡 30Y-10Y 평탄화 — 경기 둔화 조짐';
    return '🟢 수익률곡선 정상';
  }
  if (key === 'BAMLH0A0HYM2') {
    if (value >= 8) return '🔴 HY OAS 8%+ 위기 — 신용 스트레스 극대';
    if (value >= 5) return '🟠 HY OAS 5-7% 주의 — 신용 스트레스 경고';
    if (value >= 3.5) return '🟡 HY OAS 보통';
    return '🟢 HY OAS 안정';
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 3) 유동성 (RRP, TGA, WRESBAL, STABLECOIN, LIQUIDITY_DIRECTION)
  // ═══════════════════════════════════════════════════════════════════════
  if (key === 'LIQUIDITY_DIRECTION') {
    if (value >= 3) return '🟢 유동성 강한 확장 — 위험자산 우호';
    if (value >= 1) return '🟢 유동성 확장';
    if (value <= -3) return '🔴 유동성 강한 긴축 — 방어 자산 우선';
    if (value <= -1) return '🟠 유동성 긴축';
    return '🟡 유동성 중립';
  }
  if (key === 'RRP_ABSOLUTE_LEVEL') {
    if (value === 1) return '🟢 RRP 100B+ — 유동성 잔존 (완화)';
    if (value === -1) return '🟠 RRP 50B 미만 — 시장 투입 거의 완료';
    return '🟡 RRP 소진 진행 중';
  }
  if (key === 'WRESBAL_ABSOLUTE_LEVEL') {
    return value === 1 ? '🟢 지급준비금 3조+ 안전' : '🟠 지급준비금 3조 미만 — 주의';
  }
  if (key === 'STABLECOIN_TBILL_DEMAND') {
    if (value >= 2) return '🟢 스테이블 300B+ — 강한 T-Bill 수요, 금리 하방 압력';
    if (value === 1) return '🟢 스테이블 200B+ — 채권 수요 기여';
    if (value <= -1) return '🟠 스테이블 축소 — 국채 수요 약화';
    return '🟡 스테이블 중립';
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 4) 경제 건강 (GOLDILOCKS, CPI_YOY, PCE_YOY, UNRATE_TIER, FEDERAL_DEFICIT)
  // ═══════════════════════════════════════════════════════════════════════
  if (key === 'GOLDILOCKS_ZONE') {
    if (value >= 2) return '🟡 Hot Goldilocks — 살짝 과열, 인플레 재점화 감시';
    if (value === 1) return '🟢 Normal Goldilocks — 이상적 구간, 위험자산 우호';
    if (value === 0) return '🟡 Mixed — 혼재 신호';
    if (value <= -1) return '🔴 Stagflation / Recession 진입 — 방어 절대 우선';
    return '🟡 중립';
  }
  if (key === 'CPI_YOY' || key === 'PCE_YOY') {
    if (value >= 5) return `🔴 ${key.replace('_YOY', '')} ${value.toFixed(2)}% — 인플레 재점화 경계`;
    if (value >= 3) return `🟠 ${key.replace('_YOY', '')} ${value.toFixed(2)}% — Fed 타겟 초과`;
    if (value >= 1.5) return `🟢 ${key.replace('_YOY', '')} ${value.toFixed(2)}% — Fed 타겟 근접`;
    return `🟠 ${key.replace('_YOY', '')} ${value.toFixed(2)}% — 디플레 우려`;
  }
  if (key === 'UNRATE_TIER') {
    if (value >= 2) return '🟢 실업률 4% 미만 — 완전 고용';
    if (value === 1) return '🟢 실업률 4-5% — 양호';
    if (value === -1) return '🟠 실업률 5-6% — 주의';
    if (value === -2) return '🔴 실업률 6%+ — 침체 우려';
    return '🟡 고용 중립';
  }
  if (key === 'FEDERAL_DEBT_GDP_TIER') {
    if (value === -2) return '🔴 부채/GDP 140%+ — IMF 2031 예측 수준, 채권 자경단 위험';
    if (value === -1) return '🟠 부채/GDP 120-140% 경계';
    return '🟡 부채/GDP 주의';
  }
  if (key === 'FEDERAL_DEFICIT_GDP_TIER') {
    if (value === -2) return '🔴 재정 적자 7%+ GDP — 채권 자경단 임박';
    if (value === -1) return '🟠 재정 적자 5-7% — 경계 (2026 수준)';
    return '🟡 재정 적자 주의';
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 5) 나스닥/코스피 기술 (DISPARITY, RSI, DRAWDOWN_ATH, CROSS, W_BOTTOM)
  // ═══════════════════════════════════════════════════════════════════════
  if (key === 'NASDAQ_DISPARITY' || key === 'KOSPI_DISPARITY') {
    if (value <= -25) return `🟢 ${key.split('_')[0]} 이격도 -25% — video1 §전략C 평균 회귀 극대`;
    if (value <= -15) return `🟢 ${key.split('_')[0]} 이격도 -15% — 분할매수 시작`;
    if (value >= 25) return `🔴 ${key.split('_')[0]} 이격도 +25%+ 과열 — 익절 고려`;
    if (value >= 15) return `🟠 ${key.split('_')[0]} 이격도 +15%+ — 추격 매수 주의`;
    return null;
  }
  if (key === 'NASDAQ_RSI_14' || key === 'GOLD_RSI_14' || key === 'KOSPI_RSI_14') {
    const asset = key.split('_')[0];
    if (value >= 70) return `🔴 ${asset} RSI ${value.toFixed(1)} 과매수 — 추격 매수 주의`;
    if (value <= 30) return `🟢 ${asset} RSI ${value.toFixed(1)} 과매도 — 평균 회귀 매수 신호`;
    if (value >= 60) return `🟡 ${asset} RSI ${value.toFixed(1)} 강세`;
    if (value <= 40) return `🟡 ${asset} RSI ${value.toFixed(1)} 약세`;
    return null;
  }
  if (key === 'NASDAQ_DRAWDOWN_ATH' || key === 'KOSPI_DRAWDOWN_ATH') {
    const asset = key.split('_')[0];
    if (value <= -30) return `🔴 ${asset} ATH 대비 -30%+ — 구조적 위험 가능성`;
    if (value <= -20) return `🟢 ${asset} ATH 대비 -20 ~ -30% — video1 "펀더멘털 살아있는 기회" 구간`;
    if (value <= -10) return `🟡 ${asset} ATH 대비 -10 ~ -20% 조정`;
    if (value >= -5) return `🟠 ${asset} ATH 근접 — 추격 주의`;
    return null;
  }
  if (key === 'NASDAQ_CROSS') {
    if (value === 1) return '🟠 골든크로스 발생 — video3 §역발상 "이미 20-30% 오른 늦은 신호"';
    if (value === -1) return '🟢 데드크로스 발생 — video3 §역발상 "공포 극점, 분할매수 시작 구간"';
    return null;
  }
  if (key === 'NASDAQ_W_BOTTOM' || key === 'KOSPI_W_BOTTOM') {
    const asset = key.split('_')[0];
    return value === 1 ? `🟢 ${asset} W 반등 확인 — video3 분할매수 3차 타이밍` : null;
  }
  if (key === 'DMA_CONVERGENCE_LEVEL') {
    if (value === 2) return '🟡 DMA 극수렴 — video3 "폭발 직전" 관망';
    if (value === 1) return '🟡 DMA 수렴 — 에너지 응축';
    if (value === -2) return '🟢 DMA 극확산 — 강추세 진행중';
    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 6) 환율/원자재 (KRW_FX_LEVEL, DXY_TIER, WTI, GOLD_SEASONAL, CGR)
  // ═══════════════════════════════════════════════════════════════════════
  if (key === 'KRW_FX_LEVEL') {
    if (value >= 1) return '🟢 USDKRW ≤ 1480 그린 게이트 — 외국인 복귀 우호';
    if (value <= -1) return '🔴 USDKRW ≥ 1500 레드 게이트 — 외국인 매도 압력';
    return '🟡 환율 중립';
  }
  if (key === 'DXY_TIER') {
    if (value === 2) return '🟢 DXY 95 미만 약세 — 금/EM 우호 (video2 §달러)';
    if (value === -1) return '🟠 DXY 105+ 강세 — 금/EM 부담';
    return '🟡 DXY 중립';
  }
  if (key === 'GOLD_SEASONAL') {
    if (value === 1) return '🟢 금 강시즌 — video2 §4부 계절성 우호 (상위 4개월)';
    if (value === -1) return '🟠 금 약시즌 — 하위 4개월';
    return null;
  }
  if (key === 'CB_GOLD_STRUCTURAL_DEMAND') {
    return value === 1 ? '🟢 금 구조적 매수 환경 — video2 §중앙은행 3년 1000톤+' : null;
  }
  if (key === 'COPPER_GOLD_RATIO_UPTURN') {
    return value === 1 ? '🟢 CGR 상승 전환 — video2 "금구리비 하락 = 경기회복 전조"' : null;
  }
  if (key === 'WTI_CPI_LAG_RISK') {
    if (value <= -2) return '🔴 WTI 과거 급등 +20% — 2-3개월 후 CPI 재상승 위험 (video5)';
    if (value === -1) return '🟠 WTI 과거 +10% — CPI 경계';
    if (value === 1) return '🟢 WTI 과거 하락 — CPI 완화 기대';
    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 7) 수급/기관 (FOREIGN_EXTREME, HISTORIC_EXTREME, 13F FLOW)
  // ═══════════════════════════════════════════════════════════════════════
  if (key === 'KOSPI_FOREIGN_HISTORIC_EXTREME') {
    if (value === -1) return '🟢 외국인 20D -20조+ 역사적 공황 규모 — stt_kospi "2025년 2-3월 수준" 반등 후보';
    if (value === 1) return '🔴 외국인 20D +20조+ 역사적 과열';
    return null;
  }
  if (key === 'INSTITUTIONAL_NASDAQ_FLOW' || key === 'INSTITUTIONAL_SECTOR_TECH_FLOW') {
    const label = key === 'INSTITUTIONAL_NASDAQ_FLOW' ? '메가캡' : '테크 섹터';
    if (value <= -2) return `🔴 기관 ${label} 강한 매도 — video4 §기관 "돈은 거짓말 안 함"`;
    if (value === -1) return `🟠 기관 ${label} 감축 진행`;
    if (value >= 2) return `🟢 기관 ${label} 강한 매수`;
    if (value === 1) return `🟢 기관 ${label} 증가`;
    return null;
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 8) 이벤트/신규 (ELECTION_DDAY, ECONOMY_STOCK_DIV, ASSET_CORR)
  // ═══════════════════════════════════════════════════════════════════════
  if (key === 'ELECTION_DDAY_LEVEL') {
    if (value === 2) return '🟢 선거 30-180일 전 — 부양 최고조, 주가 우호 사이클';
    if (value === 1) return '🟢 선거 180일+ 전 — 정책 공격적';
    if (value === -1) return '🟠 선거 직후 3개월 — post-election 불확실성';
    if (value === 0) return '🟡 선거 30일 이내 — 변동성 증가';
    return null;
  }
  if (key === 'ECONOMY_STOCK_DIVERGENCE') {
    if (value === -1) return '🔴 실물 약 + 주가 강 — 유동성 왜곡 경고 (video4)';
    if (value === 1) return '🟢 실물 회복 + 주가 저점 — 매수 기회';
    return null;
  }
  if (key === 'DIVERSIFICATION_LEVEL') {
    if (value === 1) return '🟢 SP-GOLD 음의 상관 — 분산 효과 양호 (video2)';
    if (value === -1) return '🟠 SP-GOLD 양의 상관 — 분산 효과 악화, 포트폴리오 재검토';
    return null;
  }
  if (key === 'CHINA_EQUITY_MOMENTUM') {
    if (value >= 10) return '🟢 중국 FXI +10% — 구리 수요 우호 (video2)';
    if (value <= -10) return '🔴 중국 FXI -10% — 구리 수요 우려';
    return null;
  }
  // 17차 신규
  if (key === 'NASDAQ_MULTIFRAME_ALIGNMENT') {
    if (value >= 3) return '🟢 월/주/일 3프레임 완전 정합 상승 — video3 §차트 순서 최적 구조';
    if (value >= 1) return '🟢 멀티프레임 상승 기조 — 분할매수 적기';
    if (value <= -3) return '🔴 3프레임 동시 약세 — 구조적 위험';
    if (value <= -1) return '🟠 멀티프레임 약세 기조 — 방어 검토';
    return '🟡 멀티프레임 중립';
  }
  if (key === 'NASDAQ_YEARLY_BULL_PINBAR') {
    return value === 1 ? '🟢 연봉 장대양봉 핀바 (video3 §8:23 정합)' : null;
  }
  if (key === 'NASDAQ_YEARLY_BEAR_CANDLE') {
    return value === 1 ? '🔴 연봉 장대 음봉 — 2008/2022 수준 위험' : null;
  }
  if (key === 'NASDAQ_YEARLY_AREA_INDEX') {
    if (value < 15) return '🟠 NASDAQ 연봉 아래꼬리 <15% — 누적 매수 미소화 (video3)';
    return '🟢 NASDAQ 연봉 아래꼬리 ≥15% — 정상';
  }
  if (key === 'M2_LEAD_SHIFT_CORRELATION') {
    if (value > 0.5) return '🟢 M2→S&P 강한 선행 상관 — 유동성 기여 유효';
    if (value > 0.2) return '🟢 M2→S&P 양의 선행 상관';
    if (value < -0.2) return '🟠 M2→S&P 음의 상관 — 정합성 약화';
    return null;
  }

  if (key === 'BTC_MOMENTUM') {
    if (value >= 20) return '🟠 BTC +20% — 위험선호 극대 (video4 proxy, allocation 제외)';
    if (value <= -20) return '🔴 BTC -20% — 위험회피 극대';
    return null;
  }

  return null;
}

/**
 * 복수 derived 객체에 해설 추가.
 * snapshot.derived[key].interpretation 필드로 저장됨.
 */
export function attachInterpretations(
  derived: Record<string, { value: number | null | undefined; [k: string]: unknown }>,
  ctx?: InterpretContext,
): void {
  for (const [key, entry] of Object.entries(derived)) {
    if (!entry) continue;
    const text = interpretDerived(key, entry.value as number | null | undefined, ctx);
    if (text) {
      (entry as Record<string, unknown>).interpretation = text;
    }
  }
}
