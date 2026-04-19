# CHANGELOG

## 2026-04-18 (14차 세션)

### 14차 노션 대시보드 정합 9종 (Phase A)
- **TAIL_RISK_LEVEL 재정합**: SKEW/VVIX/OVX soft(120/110/40) + hard(140/130/60) 2단계 합산 → level 0/1/2
- **유동성 tier 5종 신규**: WALCL_TIER / TGA_TIER / MMF_TIER / SOFR_IORB_TIER / DGS10_TIER (UI 대시보드 전용)
- **경제 건강 tier 3종 신규**: UNRATE_TIER / M2SL_LEVEL_TIER / DXY_TIER (UI 대시보드 전용)

### 14차 Phase A — 5차 감사 후속
- regime 통합 2종 (scoreDGS10Level + scoreUnrateLevel 각 가중치 0.5)
- 14 → **16 컴포넌트 regime 점수화**
- 나머지 6종 tier UI 전용 재분류 주석 (중복 지표 회피)

### 14차 Phase B — 영상 정합 3종 신규
- **KOSPI_YEARLY_AREA_INDEX/LEVEL** (video5_analysis §1부 "연봉 아래꼬리 <15% 위험")
- **NASDAQ/KOSPI W 반등 reason 노출** (video3 분할매수 3차 타이밍)
- **FEDERAL_DEBT_GDP_TIER** (video4 §채권 자경단 + IMF 2031 140% 예측)
  - FRED `GFDEGDQ188S` 신규 수집

### 14차 Signal 통합
- NASDAQ: W_BOTTOM=1 → met +1 가점
- KOSPI: W_BOTTOM=1 → met +1 / AREA_INDEX<15% → unmet 경고

### 14차 테스트
- regime.test.ts PANIC_BUT_OK 경계 케이스에 DGS10/UNRATE override 추가
- 64/64 통과 유지

---

## 2026-04-18 (13차 세션)

### 13차 Critical (N8 / FE3 / 옵션 D)
- **N8 DMA_CONVERGENCE_LEVEL**: video3 §수렴 "폭발 직전" 5/20/60/120/200 DMA
  변동계수(CV) 5단계 레벨화 (+2 극수렴 ~ -2 극확산)
- **FE3-1 Regime 점수 컷 근거 강화**: TODO 제거, 10년 sweep 활성일 기반 해설
- **FE3-2 top-1→top-decile 이관 상태 명시**: NEUTRAL/CAUTION 완료, 저표본
  6레짐은 수동 영상 시정 계승
- **옵션 D NASDAQ 과열 REDUCE 재설계**: 저점 구간(이격도<-5%) 가드 +
  13F NASDAQ_FLOW/TECH_FLOW 중복 통합 + 과열/조정-확인 2분류.
  웹 전문가 관점 재검증 후 "이미 진행된 조정을 과열 REDUCE 오판" 버그 해소

### 13차 High (A3 / A7 / B3 / B6)
- **A3 WTI_COPPER_LAG_LEVEL**: video2 §3부 "유가 2-3개월 선행" WTI t-90~t-60 vs
  COPPER 30D 비교, ±1 레벨
- **A7 ECONOMY_STOCK_DIVERGENCE**: video4 "실물 약한데 주가 오른다" ISM vs
  NASDAQ_DISPARITY 유동성 왜곡 / 회복 저점 감지
- **B3 FX_FOREIGN_BETA**: stt_kospi 회귀 계수 하드코딩(-30000)을 rolling
  회귀(최근 1년) 로 교체. KRX history 부족 시 영상 기본값 fallback
- **B6 docs/history-alignment-audit.md**: 일간/주간/월간 시계열 정렬 감사

### 13차 수집 단순화 (M2 단일화)
- FRED 제거: `EA19MABMM301IXOBSAM` (유로 M3) / `JPNMABMM301IXOBSAM` (일본 M3)
  — 960일+ 정체로 실질 기여 없었음
- `GLOBAL_M2_PROXY` = 미국 M2SL YoY% 단일 (값 변화 없음, formula 명시)
- derived: EURO_M3_YOY / JAPAN_M3_YOY 제거, US_M2_YOY 유지

### 13차 Signal 통합
- NASDAQ 과열 REDUCE override 11개 조건 → 2분류 (진짜 과열 4 + 조정-확인 7)
- 저점 구간 가드로 "관찰 reason" 모드 신설
- NASDAQ 긍정 reason: DMA 수렴 / ECONOMY 회복 저점 / WTI_COPPER 회복 조기 추가

### 13차 배포 버그 수정
- COPPER_STOCK_DIVERGENCE / CB_GOLD_STRUCTURAL_DEMAND fetch 요청량 증가
  (length<21 회피)
- 13F infotable 파일명 자유 형식 대응 — 6/10 → 10/10 펀드 파싱 복구
- CNN F&G fallback 누락 (200+빈데이터 케이스) 수정

---

## 2026-04-18 (11차-12차 세션)

### 11차 envelope / BASE 재선정
- sweep envelope 영상 원문 정합 재조정 (근거 없는 수치 규칙 제거)
- BASE_ALLOCATIONS NEUTRAL/CAUTION 재선정 (top-decile, α +35.65%p)
- RISK_ON/CORRECTION envelope 수동 시정 (video2/video5_analysis 정합)
- STAGFLATION/BOND_VIGILANTE silver 감축 + envelope silver≤5 6개 레짐 확장
- CAUTION 12차 재선정 (sweep 신규 derived 반영, α +39.48%p)

### 12차 신규 파생지표 (10종)
- **KOSPI_FOREIGN_HISTORIC_EXTREME**: stt_kospi "2025년 2~3월 45~60조 매도" 수준 감지 (±20조)
- **COPPER_GOLD_RATIO_UPTURN/DOWNTURN**: video2 §3부 "금구리비 하락 전환 = 경기회복 전조"
- **COPPER_STOCK_DIVERGENCE**: video2 "구리 2~3개월 선행" 방향 괴리 감지
- **GOLD_SEASONAL**: video2 §4부 "금의 계절성" — 20년 월별 평균 수익률
- **CB_GOLD_STRUCTURAL_DEMAND**: video2 §1부 "중앙은행 구조 매수" proxy (12M 금↑/DXY↓/실질금리↓)
- **TAIL_RISK_LEVEL**: video4 §꼬리 위험 — SKEW/VVIX/OVX 3개 통합
- **FNG_TIER**: 노션 F&G 5단계 정합 (극공포~극탐욕 -2~+2)
- **WRESBAL_ABSOLUTE_LEVEL**: 노션 "3조 이상 안전" (millions 단위)
- **RRP_ABSOLUTE_LEVEL**: 노션 "100B/50B 임계" (billions 단위)
- **NASDAQ_STRATEGY_B (signals.ts)**: video1 §전략B "5가지 겹침 확신 깊이 최대"

### 13F 기관 추적 (Phase 1~3)
- `collectors/institutional-13f.ts` 신규 (10곳 헤지펀드 CIK 하드코딩)
- Phase 1: 현재 분기 NASDAQ 메가캡 비중 스냅샷 (INSTITUTIONAL_NASDAQ_EXPOSURE_PCT)
- Phase 2: 최근 2분기 비교 → 분기 변화 FLOW 레벨 -2~+2
- Phase 3: 섹터별 확장 (TECH/FIN/ENERGY)
- infotable XML 파일명 자유 형식 대응 (10/10 전체 파싱 성공)
- 단위 테스트 11개

### Signal 통합
- NASDAQ 과열 REDUCE override 조건 8개로 확장
  (이격도 / F&G tier / VIX / CHASE / COPPER_DIVERG / TAIL_RISK / INST_FLOW / TECH_FLOW / HY 5-7%)
- NASDAQ/KOSPI 극저점(-25%) 계층화 (+2 가점)
- KOSPI WTI 임계 80→65 (video5_analysis 정합)
- KOSPI 지정학 숏커버링 반등 가드 (stt_kospi §2부)
- GOLD cbBuying 자동 proxy (manual 보완)
- KOSPI HISTORIC_EXTREME=-1 시 met+1 가점
- COPPER UPTURN 시 met+1 가점

### 버그 수정
- CNN F&G fallback 누락 (200+빈데이터 케이스 커버)
- COPPER_STOCK_DIVERGENCE fetch 요청량 25→40
- CB_GOLD 260→400 (length<250 회피)
- SignalPanel 진행바/불충족 조건/가중치 표시 복구

### 배포 인프라
- tsconfig types 에 jest 추가
- server HEAD 정합 회복 (explanation tracking, source-cache, logger, decision-logging)
- 12차 13F 캐시 키 v2→v3 (파서 개선 강제 적용)

### PRD 반영
- §6.3.1 비중 매트릭스 NEUTRAL/CAUTION/STAGFLATION/BOND_VIGILANTE 행 갱신
- envelope 제약 표에 영상 근거별 6개 레짐 silver≤5 명시
- BASE 교체 recommendation (CANDIDATE/BLOCKED) 정책 문서화

### TODO 문서화
- `docs/TODO-institutional-policy-integration.md` 신규
  - #8 Phase 4 CONSENSUS_DIVERGENCE 설계 스켈레톤
  - #9 정책 자동화 NLP 장기 로드맵

---

## 2026-04-13 (초기)

### Added
- FRED / Yahoo / CNN Fear & Greed / GPR / OpenInsider / Dataroma / Earnings 수집
- 정책 방향 자동화(EFFR), 지정학 리스크 자동화(GPR), 중앙은행 금 매수 프록시 자동화
- ISM 자동화 (TradingEconomics scrape + INDPRO fallback)
- 국면 엔진 11개 컴포넌트
- 나스닥/코스피/금/은/구리/현금/레버리지 신호 엔진
- 코스피 200DMA/이격도/거래량/환율/유가 기반 신호
- 금 200DMA/피보나치/바닥권 BUY 승격 로직
- 유동성 방향 (RRP/TGA/MMF) + 글로벌 M2 프록시
- 섹터 모멘텀 (XLK/XLF/XLE/XLV/XLI/XLY)
- 스마트머니 패널 / 실적 발표 일정 패널 / 데이터 신선도 패널 / 실시간 패널
- 히스토리 시각화 (기간/간격 선택, 이중 y축, 드래그 pan)
- 백테스트 (나스닥 벤치마크, 포트폴리오 비중 기반)
- 텔레그램 알림 (신호/비중 변경 + 전체 요약)

### Changed
- 금 신호: 실질금리 절대값 → 추세 기반
- DXY 절대값 판단 → 단기/장기 추세 반영
- 과열 시 현금 +20%, 금 +5% 방어 강화
- 신호 텍스트 전부 가중치 표기 통일

### Remaining
- KRX 외국인 수급 연동
