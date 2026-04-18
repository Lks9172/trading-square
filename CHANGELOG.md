# CHANGELOG

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
