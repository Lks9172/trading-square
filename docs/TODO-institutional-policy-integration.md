# TODO: 기관 리포트(13F) + 정책 자동화 통합 (범위 큰 작업)

## 배경

영상 분석 결과 아래 2개 영역이 **원문에서 강조**되지만 현 시스템에서 **부분/미구현** 상태로 남음 — 범위가 커서 별도 설계/세션에서 다룬다.

---

## #8 기관 리포트 / 13F 대량 보유 변화 수집

### 영상 근거
- **video4 §기관 리포트**:
  > "기관 리포트를 볼 때 핵심은 뭘 추천하는가가 아니라 어디에 실제로 베팅하고 있는가를 읽는 겁니다. 말은 거짓말 할 수 있지만 돈은 거짓말을 하지 않거든요."
- **video4 §스마트머니**: 헤지펀드·기관 투자자들이 출구로 향하는 신호 = 고점 신호.
- **notion_content.json 자료 링크**:
  - `https://www.sec.gov/search-filings` — SEC EDGAR
  - `https://www.dataroma.com/m/home.php` — 13F 요약
  - `https://www.tipranks.com/dashboard` — 월스트리트 컨센서스

### 현 시스템 범위
- `server/src/collectors/smart-money.ts`: **인사이더 매매**만 (OpenInsider / SEC Form 4 / Dataroma 일부)
- **13F 대량 보유 변화 미수집**
- **애널리스트 컨센서스 변화 추적 없음**

### 요구사항
1. **13F 수집기 신설** (`server/src/collectors/institutional-13f.ts`):
   - 주요 헤지펀드 (Bridgewater, Citadel, Renaissance, Millennium, Berkshire 등 ~20곳) CIK 리스트 고정
   - SEC EDGAR 13F-HR 파일 분기별 파싱
   - 전 분기 대비 포지션 변화 (new/added/reduced/closed) 추출
   - TTL: 분기말 + 45일 공시 의무라 분기별 배치

2. **파생 지표 신규**:
   - `INSTITUTIONAL_NET_FLOW_NASDAQ`: 헤지펀드 나스닥 주요 종목 순포지션 변화 (+1/0/-1)
   - `INSTITUTIONAL_NET_FLOW_SECTOR`: XLK/XLF/XLE 등 섹터별 집단 이동
   - `RESEARCH_CONSENSUS_DIVERGENCE`: 리포트(추천) vs 13F(실제 베팅) 괴리 지수

3. **Signal 통합**:
   - NASDAQ 과열 REDUCE override 조건에 `INSTITUTIONAL_NET_FLOW_NASDAQ ≤ -1` 추가
   - KOSPI 에도 외국인 기관 집단 이탈 시 HOLD 강등 로직

### 예상 작업 규모
- 수집기: 1주 (SEC EDGAR 파싱, 캐시 설계, 분기 배치)
- 파생/시그널 통합: 3일
- 테스트: 2일
- **총 ~2주** (별도 sprint)

---

## #9 정책 뉴스 / 연준 발언 자동화

### 영상 근거
- **video4 §정책**:
  > "투자에서 정책을 무시하는 건 축구 경기에서 심판을 무시하는 것과 같습니다"
- **video4 §인물**: 재무장관 스콧 베센트, 차기 연준 의장 후보 케빈 워시. 발언 하나가 달러 방향을 바꿈.
- **video4 §채권 자경단**: 2022 리즈 트러스 총리 44일 해임 사례.

### 현 시스템 범위
- `profile.manualInputs.policyDirection`: **사용자가 UI 에서 수동 입력** (-2~+2).
- `FISCAL_STRESS` / `BOND_VIGILANTE_WARNING`: DGS30 + HY 기반 자동화 있음 (채권 자경단 일부 반영).
- **뉴스/연준 발언 NLP 없음**.

### 요구사항
1. **뉴스 수집기** (`server/src/collectors/policy-news.ts`):
   - FOMC 발표문 / 연준 의장 연설 / 재무부 보도자료 RSS 구독
   - FRED가 제공하는 FOMC 문서 (`PRS.txt`) 활용
   - Fed 발언 sentiment 스코어링 (매파/비둘기)

2. **파생 지표 신규**:
   - `FED_SENTIMENT`: -2(매파) ~ +2(비둘기)
   - `POLICY_NEWS_FLOW`: 최근 7일 주요 정책 뉴스 빈도 + 방향
   - `TRUMP_TARIFF_ANNOUNCEMENT`: 트럼프 관세 관련 발표 flag

3. **자동 반영**:
   - `policyDirection` manual 기본값을 `FED_SENTIMENT` 자동값으로 대체 (manual override 옵션 유지)
   - `BOND_VIGILANTE_WARNING` 판정에 정책 뉴스 factor 추가

### 기술 과제
- 한국어/영어 NLP 모델 (BERT 또는 사전 기반 lexicon)
- 뉴스 API 비용 (Bloomberg Terminal 없이 무료 소스만)
- Fed 발언은 PDF 파싱 필요 (FOMC minutes)

### 예상 작업 규모
- 수집/파싱: 1-2주
- NLP 점수화: 2주 (모델 튜닝)
- 파생/시그널 통합: 1주
- **총 ~4-5주** (별도 프로젝트 수준)

---

## 우선순위 권고

1. **#8 (13F)**: ROI 높음. 영상 명시도 강하고 SEC 공개 데이터 무료. 분기 배치로 운영 부담 낮음. → ✅ **12차 Phase 1-4 구현 완료** (Phase 4 는 CONSENSUS_DIVERGENCE 남음).
2. **#9 (정책 자동화)**: ROI 중간. 자동화 어려움 대비 manual input 이 이미 대체 수단. NLP 정확도 도달 전까지는 수동이 더 정확할 수 있음. → **장기 로드맵**.

## 작업 진입 전 확인
- **데이터 라이선스**: SEC EDGAR 는 public domain, 대부분 뉴스는 RSS 무료 가능.
- **백테스트 데이터**: 13F 과거 분기별 히스토리 SEC 에 존재 (2000년대 이후).
- **영상 정합 검증**: 구현 후 stt_kospi 2025년 2-3월 외국인 대량 매도 시기의 13F 변화가 감지되는지 확인.

---

## 🔵 #8 Phase 4 설계 스켈레톤 (2026-04 추가)

### 목표
RESEARCH_CONSENSUS_DIVERGENCE — 애널리스트 리포트의 추천(BUY/HOLD/SELL)과 13F 실제 베팅 사이 괴리 감지. video4 §기관 "말은 거짓말 할 수 있지만 돈은 거짓말을 하지 않거든요" 정합.

### 데이터 소스
**Option A (권장)**: Yahoo Finance analyst recommendations
- API: `quoteSummary?modules=recommendationTrend,upgradeDowngradeHistory`
- 종목별 strong_buy/buy/hold/sell 카운트 월별 제공
- 무료, rate limit 관대

**Option B**: Finviz scraper
- 종목별 analyst targets 테이블
- HTML 파싱 필요, 구조 변경 위험

**Option C**: TipRanks API
- 유료, 정밀도 높지만 비용 부담

### 파생 지표 설계
```
RESEARCH_CONSENSUS_DIVERGENCE (value: -2 ~ +2)
  계산:
    1. NASDAQ 메가캡 7종목 각각의 analyst consensus 점수화
       (strong_buy=+2, buy=+1, hold=0, underperform=-1, sell=-2)
       펀드 가중치 평균 → 추천 점수 [-2, +2]
    2. 13F INSTITUTIONAL_NASDAQ_FLOW level [-2, +2]
    3. divergence = 추천 점수 - 13F flow
       >+2: 추천 긍정 but 기관 매도 (≈ "말과 돈 괴리 BUY")
       <-2: 추천 부정 but 기관 매수 (≈ "말과 돈 괴리 SELL")
       나머지: 0
```

### 구현 범위 (예상 3-5일)
- `collectors/analyst-consensus.ts` 신규 (Yahoo quoteSummary 래퍼)
- `derived.ts` 에 RESEARCH_CONSENSUS_DIVERGENCE 추가
- signals.ts NASDAQ 에서 발동 시 경고 / 가점

### 테스트 전략
- 2022-12 (추천 다수 BUY 인데 헤지펀드 12월 tech 대거 처분) 역사 데이터로 divergence=+2 재현되는지
- 2023-06 (ChatGPT 붐 후 추천 갑자기 상향 + 기관 추종) divergence=0 재현
