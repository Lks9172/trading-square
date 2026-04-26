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

  // 18차 Phase 1 + 2 신규
  if (key === 'LEVERAGE_TRIGGER_3OF3') {
    if (value === 1) return '🟢 착한 레버리지 3조건 동시 충족 — 최대 15%, 목표 +20~30% (video1 §3부)';
    return null;
  }
  if (key === 'LEVERAGE_TRIGGER_COUNT') {
    if (value === 2) return '🟡 레버리지 트리거 2/3 — 아직 대기 (3조건 모두 필요)';
    if (value === 1) return '⚪ 레버리지 트리거 1/3 — 전혀 부족';
    return null;
  }
  if (key === 'CONVICTION_SCORE_7AXIS') {
    if (value >= 5) return '🟢 7축 중 5+ 강세 정합 — video1+4 §확신 최고조';
    if (value >= 3) return '🟢 강 정합 — 공격적 진입 근거';
    if (value <= -5) return '🔴 7축 중 5+ 약세 — 확신 있게 방어';
    if (value <= -3) return '🟠 약세 정합 — 공격 자제';
    return null;
  }
  if (key === 'GOLD_LONGTERM_CUP_HANDLE') {
    if (value === 2) return '🟢 금 장기 컵앤핸들 완성 — video2 §4부 "역H&S 돌파"';
    if (value === 1) return '🔵 cup rim 근접/재탈환 — handle 형성 대기';
    if (value === -1) return '🟡 cup 미완성 — 장기 구조 약함';
    return null;
  }
  // 19차 신규
  // 20차 신규
  // 21차 신규
  if (key === 'PORTFOLIO_HOLDINGS_TOTAL_PCT') {
    if (value < 80) return '🟡 사용자 보유 합 80% 미만 — 미할당 자산 또는 입력 누락';
    if (value > 110) return '🔴 사용자 보유 합 110% 초과 — 입력 오류 가능';
    return null;
  }
  if (key === 'KOSPI_YEARLY_LOWER_WICK_PCT') {
    if (value < 5) return '🟠 KOSPI 연봉 아래꼬리 <5% — stt_kospi §"매수 미소화, 추가 조정 우려"';
    if (value >= 15) return '🟢 KOSPI 연봉 아래꼬리 ≥15% — 정상';
    return null;
  }
  if (key === 'DCA_STAGE_STAGNATION_COUNT') {
    if (value >= 2) return '🟠 분할매수 정체 ≥2개 자산 — video3 §"분할 적기" 가드';
    if (value === 1) return '🟡 분할매수 정체 1건';
    return null;
  }
  if (key === 'NAVER_INVEST_INFO_REPORT_DAYS_AGO') {
    if (value <= 1) return '🟢 네이버 투자정보 최신 (1일 이내)';
    if (value >= 7) return '🟡 네이버 투자정보 7일+ 공백';
    return null;
  }
  // 24차 신규
  if (key === 'KOSDAQ_DRAWDOWN_ATH') {
    if (value <= -30) return '🔴 KOSDAQ -30%↓ 심각 약세';
    if (value <= -20) return '🟠 KOSDAQ -20~-30% 약세';
    if (value <= -10) return '🟡 KOSDAQ -10~-20% 조정';
    return null;
  }
  if (key === 'EM_TRIO_BREADTH') {
    if (value === 2) return '🟢 EM 4국 동조 강세 — 신흥국 우호';
    if (value === 1) return '🔵 EM 우세';
    if (value === -2) return '🔴 EM 동조 약세';
    return null;
  }
  if (key === 'STLFSI_LEVEL') {
    if (value >= 2) return '🔴 금융 스트레스 극단';
    if (value >= 1) return '🟠 금융 스트레스 경계';
    if (value < 0) return '🟢 금융 매우 안정';
    return null;
  }
  if (key === 'MMF_RRP_RATIO') {
    if (value >= 8) return '🟢 MMF/RRP 8+ 시장 유동성 풍부';
    if (value < 2) return '🟠 MMF/RRP <2 Fed 흡수 강함';
    return null;
  }
  if (key === 'DCA_BUFFER_REMAINING_PCT') {
    if (value <= 0) return '🟠 분할매수 가용 0% — 추가 진입 불가';
    if (value >= 70) return '🟢 분할매수 buffer 70%+ 충분';
    return null;
  }
  if (key === 'KOSDAQ_DRAWDOWN_LEVEL') {
    if (value <= -2) return '🔴 KOSDAQ 심각';
    if (value === -1) return '🟠 KOSDAQ 약세';
    return null;
  }

  // 23차 신규
  if (key === 'COPPER_GOLD_RATIO_ZSCORE_90D') {
    if (value >= 1.5) return '🟢 CGR 90D z>+1.5 — 경기 회복 강신호 (video2 §3부)';
    if (value >= 0.5) return '🟢 CGR 90D z>+0.5 — 상승 우세';
    if (value <= -0.5) return '🟠 CGR 90D z<-0.5 — 하락 전환';
    return null;
  }
  if (key === 'M2_SP500_LEAD_10W_ALIGNMENT') {
    if (value === 1) return '🟢 M2 10주 리드 정합 — 유동성 신호 유효 (노션 §StreetStats)';
    if (value === -1) return '🟠 M2-S&P 10주 이탈';
    return null;
  }
  if (key === 'SMART_MONEY_3AXIS_ALIGNMENT') {
    if (value === 2) return '🟢 세력 3축 동조 매수 (13F+8-K+내부자) — 노션 §스마트머니 강신호';
    if (value === 1) return '🔵 세력 2축 활성';
    return null;
  }
  if (key === 'LIQUIDITY_TRIPLE_GATE') {
    if (value === 2) return '🟢 유동성 트리플 게이트 발동 (M2↑+VIX↓+DXY↓) — 위험자산 호조';
    if (value === 1) return '🔵 유동성 2축 활성';
    if (value === -1) return '🟠 유동성 약화';
    return null;
  }
  if (key === 'KOSPI_VOLUME_20T_FLAG') {
    if (value === 1) return '🟢 KOSPI 거래대금 20조+ — 지속성 우호 (video5 §73)';
    return null;
  }

  // 22차 신규
  if (key === 'OPERATOR_PHILOSOPHY_QUOTE_INDEX') {
    return null; // formula 에 short 인용이 이미 들어가있음
  }
  if (key === 'REGIME_FORWARD_RETURN_90D_AVG') {
    if (value >= 10) return '🟢 동일 레짐 진입 후 90D 평균 +10%+ — 강한 historical 우위';
    if (value >= 0) return '🟢 동일 레짐 진입 후 90D 평균 양수';
    if (value <= -5) return '🔴 동일 레짐 진입 후 90D 평균 -5%↓ — 방어 우선';
    return null;
  }
  if (key === 'NASDAQ_SIGNAL_HIT_RATE_30D') {
    if (value >= 70) return '🟢 BUY 신호 30D 적중률 70%+ — video1 §확률 우호';
    if (value >= 50) return '🟡 BUY 신호 30D 적중률 50%+';
    if (value <= 30) return '🔴 BUY 신호 적중률 < 30% — 임계 재검토 필요';
    return null;
  }
  if (key === 'SCENARIO_TRANSITION_FLIPS_30D') {
    if (value >= 5) return '🟠 시나리오 게이트 30일 5회+ 전환 — 분기 불안정';
    if (value >= 2) return '🟡 시나리오 게이트 전환 진행';
    return null;
  }

  if (key === 'KR_NEWS_TOTAL_COUNT_24H') {
    if (value >= 50) return '🔴 한국 매크로 뉴스 24h 50건+ — 노이즈 폭증';
    if (value >= 20) return '🟡 한국 매크로 뉴스 활발';
    return null;
  }
  if (key === 'KR_NEWS_FRESHNESS_MINUTES') {
    if (value <= 30) return '🟢 한국 매크로 뉴스 30분 이내';
    if (value >= 360) return '🟡 한국 매크로 뉴스 6시간+ 공백';
    return null;
  }
  if (key === 'GEOPOLITICAL_COUNTDOWN_DDAY') {
    if (value === 0) return '🔴 지정학 이벤트 오늘 — 변동성 주의';
    if (value <= 3) return '🟠 지정학 이벤트 D-3 이내 — 사전 대비';
    if (value <= 14) return '🟡 지정학 이벤트 D-14 이내 — 모니터링';
    return null;
  }

  if (key === 'TE_STREAM_MINUTES_AGO') {
    if (value <= 30) return '🟢 TE 헤드라인 30분 이내 — 시장 정보 즉시성';
    if (value >= 360) return '🟡 TE 헤드라인 6시간+ 공백';
    return null;
  }
  if (key === 'TE_STREAM_COUNT_24H') {
    if (value >= 40) return '🔴 TE 24h 헤드라인 40건+ — 매크로 노이즈 폭증';
    if (value >= 20) return '🟡 TE 24h 헤드라인 20-40건';
    return null;
  }
  if (key === 'KOSPI_QUARTERLY_UPPER_WICK_PCT') {
    if (value >= 80) return '🔴 분기봉 윗꼬리 80%+ — stt_kospi §1부 "위로 찔렀다 내려옴" 강한 매도 흔적';
    if (value >= 40) return '🟠 분기봉 윗꼬리 40%+ — 매도 우세 흔적';
    return null;
  }
  if (key === 'KOSPI_HALFYEAR_UPPER_WICK_PCT') {
    if (value >= 80) return '🔴 반기봉 윗꼬리 80%+ — 중기 매도 우세';
    if (value >= 40) return '🟡 반기봉 윗꼬리 40%+ — 중기 약세 압력';
    return null;
  }
  if (key === 'TRUMP_AGENDA_PRESSURE') {
    if (value >= 3) return '🔴 트럼프 어젠다 4축 압력 극대 — video4 §5:51 "관세 압력"';
    if (value >= 2) return '🟠 트럼프 어젠다 압력 진행';
    return null;
  }
  if (key === 'US_DEBT_TRAJECTORY_LEVEL') {
    if (value >= 3) return '🔴 부채 + 적자 극단 — 채권자경단 임박 (video4 §10:11)';
    if (value === 2) return '🟠 부채 + 적자 동시 경계 — IMF 2031 시나리오';
    if (value === 1) return '🟡 부채 누적 진행';
    return null;
  }
  if (key === 'LIQUIDITY_RISK_TRANSMISSION') {
    if (value === 2) return '🔴 강한 디커플링 — M2 ↑ 인데 IWM/HYG 약세 (video4 §7:31)';
    if (value === 1) return '🟡 디커플링 진행';
    if (value === -1) return '🟢 정상 — 유동성 → 위험자산 전이';
    return null;
  }
  if (key === 'DGS30_3W_CHANGE_BPS') {
    if (value >= 30) return '🔴 30Y 3주 +30bp+ — 트러스 패턴 (video4 §9:46)';
    if (value >= 15) return '🟠 30Y 3주 +15bp+ — 채권자경단 활성화 조짐';
    if (value <= -20) return '🟢 30Y 3주 -20bp+ — 안전자산 회귀';
    return null;
  }
  if (key === 'GOLD_GEOPOLITICAL_PARADOX') {
    if (value === 1) return '🟠 Gold paradox — geoRisk + WTI ↑ + CPI 가속 → 단기 금 하락 가능 (video2 §10:01)';
    return null;
  }
  if (key === 'RETAIL_INSTITUTION_DIVERGENCE') {
    if (value <= -2) return '🔴 개인 낙관 + 기관 감축 — 축제 끝물 (video4 §1:34)';
    if (value >= 2) return '🟢 개인 비관 + 기관 매집 — 역발상 매수';
    return null;
  }
  if (key === 'BOK_QUARTERLY_OUTLOOK_DDAY') {
    if (value === 0) return '🟡 BOK 분기 경제전망 오늘 발표';
    if (value <= 7) return '🟡 BOK 분기 경제전망 D-7 이내';
    return null;
  }
  if (key === 'NAVER_ECONOMY_REPORT_DAYS_AGO') {
    if (value <= 1) return '🟢 네이버 경제분석 최신 (1일 이내)';
    if (value >= 7) return '🟡 네이버 경제분석 7일+ 공백';
    return null;
  }
  if (key === 'NASDAQ_DRAWDOWN_CRISIS_LEVEL') {
    if (value <= -2) return '🔴 시스템 위기 (-55%↓) — video1 §3부';
    if (value <= -1) return '🟠 큰 조정 (-30~-55%)';
    if (value === 0) return '🟡 조정 진행 (-15~-30%)';
    return null;
  }

  // 20차 누락 보강 — 7건
  if (key === 'BOND_VIGILANTE_SCORE') {
    if (value >= 4) return '🔴 채권 자경단 4축+ 충족 — video4 §137-147 정책 신뢰 이탈';
    if (value >= 3) return '🟠 채권 자경단 3축 — 프리커서 활성';
    if (value >= 2) return '🟡 채권 자경단 2축 — 경계';
    return null;
  }
  if (key === 'STAGFLATION_VERIFIED') {
    if (value === 1) return '🔴 스태그플레이션 verified — video4 §145';
    return null;
  }
  if (key === 'KOSPI_FX_FOREIGN_DIVERGENCE') {
    if (value <= -0.5) return '🟠 외인 매도 적정 대비 큰 괴리 — ATM화 진행';
    if (value >= 0.5) return '🟢 외인 매도 적정 이내';
    return null;
  }
  if (key === 'GOLD_PRIORITY_SCORE') {
    if (value >= 0.7) return '🟢 금 우선순위 4축 정합 강함 (video2 §1부)';
    if (value <= 0.3) return '🟠 금 우선순위 미충족';
    return null;
  }
  if (key === 'M2_YOY_CROSS_DAYS') {
    if (value >= 60) return '🟢 M2 YoY 양수 60일+ 지속 — 유동성 확장 정착';
    if (value >= 0) return '🟡 M2 YoY 양수 진입';
    return null;
  }
  if (key === 'GEOPOLITICAL_UNWIND_EVENT') {
    if (value === 2) return '🟢 지정학 unwind 강 — 휴전·완화 이벤트';
    if (value === 1) return '🟡 지정학 unwind 약';
    return null;
  }
  if (key === 'USDKRW_WEEKLY_CHANNEL_POSITION') {
    if (value >= 0.9) return '🔴 주봉 채널 상단 90%+ — 환율 과열';
    if (value <= 0.1) return '🟢 주봉 채널 하단 10% — 환율 우호';
    return null;
  }

  if (key === 'FX_FOREIGN_DEVIATION_RATIO') {
    if (value >= 6) return '🔴 외인 매도 ATM화 극단 — stt_kospi §3부 "12배 같은 비정상"';
    if (value >= 3) return '🟠 외인 매도 적정 대비 3배 초과 — ATM화 경계';
    if (value >= 1.5) return '🟡 외인 매도 적정 초과';
    return null;
  }
  if (key === 'FX_FOREIGN_DEVIATION_LEVEL') {
    if (value >= 3) return '🔴 ATM화 극단';
    if (value === 2) return '🟠 ATM화 경계';
    if (value === 1) return '🟡 적정 초과';
    return null;
  }
  if (key === 'NEUTRAL_RATE_TEMPERATURE') {
    if (value === 2) return '🔴 빨간불 — FFR-R* ≥3%p 고긴축 (video4 §매크로)';
    if (value === 1) return '🟡 노란불 — 긴축 진행';
    if (value === 0) return '🟢 초록불 — 중립~약긴축';
    if (value === -1) return '🟢🟢 초완화 (FFR < R*)';
    return null;
  }
  if (key === 'NASDAQ_RANGE_BREAK_FAKEOUT') {
    if (value === 1) return '🟠 페이크아웃 의심 — video3 §174 "단기 급락 가속 트리거"';
    return null;
  }
  if (key === 'HELIUM_AI_BOTTLENECK') {
    if (value === 2) return '🔴 헬륨/AI 반도체 공급 우려 — video5 §"카타르 LNG 차단 가설"';
    if (value === 1) return '🟡 헬륨 공급 경계';
    if (value === -1) return '🟢 카타르 정상 + SOXX 강세';
    return null;
  }
  if (key === 'NASDAQ_UPPER_WICK_IMPULSE') {
    if (value >= 3) return '🔴 강한 윗꼬리 매도세 — video2 §4부 "추세 통틀어 가장 강한"';
    if (value >= 1.5) return '🟡 윗꼬리 매도 압력';
    return null;
  }
  if (key === 'NASDAQ_UPPER_WICK_LEVEL') {
    if (value === 2) return '🔴 강한 매도세 (거래량 폭증 동반)';
    if (value === 1) return '🟡 매도 압력 경계';
    return null;
  }
  if (key === 'SCENARIO_GATE_A_B') {
    if (value === 1) return '🟢 시나리오 A — 추세 재개 (stt_kospi §4부)';
    if (value === -1) return '🔴 시나리오 B — 박스 하방 이탈';
    return '🟡 관망 — 시나리오 분기점';
  }
  if (key === 'KOSPI_HISTORIC_OVERSHOOT_FLAG') {
    if (value === 1) return '🔴 KOSPI 75%+ 1년 — stt_kospi §1부 "조정 없이 직행 사례 거의 없음"';
    return null;
  }
  if (key === 'HORMUZ_UNWIND_SEMI_TAILWIND') {
    if (value === 1) return '🟢 호르무즈 정상화 → 반도체 양의 연쇄 (video5)';
    return null;
  }
  if (key === 'GOLD_SILVER_RATIO_EXTREME') {
    if (value === 2) return '🔴 금은비 130+ — video2 §"코로나 130→은 150%" 사례 구간';
    if (value === 1) return '🟠 금은비 100+ 과열 — 은 매수 우호';
    if (value === -1) return '🟢 금은비 정상';
    return null;
  }
  if (key === 'FOMC_RATE_CUT_PROB_25BP') {
    if (value >= 70) return '🟢 25bp 인하 우세 (시장 베팅 70%+)';
    if (value >= 40) return '🟡 25bp 인하 분분 (40~70%)';
    return null;
  }
  if (key === 'FOMC_RATE_HIKE_PROB_25BP') {
    if (value >= 50) return '🔴 25bp 인상 우세 — 매파 베팅';
    return null;
  }
  if (key === 'M2_SP500_LEAD_ALIGNMENT') {
    if (value === 1) return '🟢 M2→S&P 13주 리드 방향 일치 — 유동성 신호 유효';
    if (value === -1) return '🟠 M2-S&P 방향 이탈 — 리드 상관 약화';
    return null;
  }
  if (key === 'ANALYST_TARGET_UPSIDE_PCT') {
    if (value >= 20) return '🟢 목표가 대비 +20%+ — 애널리스트 강한 강세';
    if (value >= 5) return '🔵 목표가 대비 +5~20% — 보통 강세';
    if (value <= -10) return '🟠 목표가 대비 -10%+ — 고평가 경고';
    return null;
  }
  if (key === 'EARNINGS_SURPRISE_PCT') {
    if (value >= 5) return '🟢 메가캡 EPS 서프라이즈 +5%+ — 실적 강세';
    if (value <= -3) return '🔴 메가캡 EPS 미스 -3%+ — 실적 약세';
    return null;
  }
  if (key === 'EARNINGS_DDAY_MEGACAP') {
    if (value === 0) return '🟡 메가캡 실적 오늘 — 변동성 주의';
    if (value <= 3) return '🟡 메가캡 실적 D-3 이내 — 대기';
    return null;
  }
  if (key === 'KR_MATERIAL_DISCLOSURE_LEVEL') {
    if (value >= 2) return '🔴 DART 주요공시 과다 — 한국 이벤트 집중';
    if (value === 1) return '🟡 DART 주요공시 증가 — 경계';
    return null;
  }
  if (key === 'SMART_MONEY_CLUSTER_BUY') {
    if (value >= 5) return '🟢 클러스터 매수 5종+ — 노션 §Insider Screener 강한 기관 합의';
    if (value >= 2) return '🔵 클러스터 매수 2-4종 — 내부자 관심 집중';
    return null;
  }
  if (key === 'INSIDER_DIP_BUY') {
    if (value === 1) return '🟢 Dip buy 확인 — 노션 "-5%+ 후 클러스터 매수"';
    return null;
  }
  if (key === 'INSTITUTIONAL_CONSENSUS_STRONG_COUNT') {
    if (value >= 5) return '🟢 3인+ 공유 종목 5개+ — Dataroma Big Bets 최강 합의';
    if (value >= 3) return '🔵 3인+ 공유 종목 3-4개 — 기관 합의 형성';
    return null;
  }
  if (key === 'BOND_SAFEHAVEN_BROKEN') {
    if (value === 2) return '🔴 Safe-haven 붕괴 — 30Y↑+DXY↓ (탈달러 구조 전환, video4 §10:49)';
    if (value === -1) return '🟢 글로벌 리스크오프 — 30Y↓+DXY↓';
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
