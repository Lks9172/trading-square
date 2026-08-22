# 역사적 TODO: 기관 리포트(13F) + 정책 자동화 통합

- 문서 상태: **ARCHIVED**
- 현재 TODO 아님: Node 시기 설계 이력이다.

> **상태 안내 — 2026-07-21**
> 이 문서는 Node 시기 설계 이력이다. analyst consensus/history와 미국 8-K/IR뿐 아니라
> SEC 13F-HR 직접 수집·분기 변화 정규화, Federal Reserve 공식 원문 NLP도 Spring 운영 경로에 반영됐다.
> 현재 13F는 20개 주요 관리자, point-in-time CUSIP identity와 analyst-vs-money divergence까지 제공한다.
> 정책은 Fed·Treasury·USTR 원문과 FOMC 결정 walk-forward calibration까지 확장됐다. 정확한 현재 상태는
> [`TODO-STATUS-2026-07-21.md`](TODO-STATUS-2026-07-21.md)를 기준으로 한다.

## 배경

영상 분석에서 아래 2개 영역이 원문상 중요하게 강조됐다. 본문 요구사항은 구현 전 설계 이력이며,
현재 완료 범위와 의도적으로 남긴 후속 확장을 함께 기록한다.

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
- Spring `institutional` bounded context가 공식 SEC submissions/index/information-table을 직접 수집한다.
- 최근 두 분기의 보고 주식 수를 비교하여 new/increase/reduce/exit와 추정 순매매를 제공한다.
- PostgreSQL에는 정규화 결과와 MinIO object key만 저장하고 원문은 MinIO에 보관한다.
- 현재 관리자 universe는 Berkshire Hathaway, Bridgewater, Renaissance, Soros, Citadel 등 20곳이다.
- CUSIP→ticker/섹터를 유효기간으로 보존하고 analyst consensus와 13F 수량 변화의 괴리를 별도 표시한다.

### 역사적 요구사항과 후속 확장
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
- Spring `policy` bounded context가 Federal Reserve·U.S. Treasury·USTR 공식 원문을 수집한다.
- 매파/비둘기 lexicon, 가중치, 원문 excerpt, confidence를 PostgreSQL에 저장하고 원문은 MinIO에 보관한다.
- confidence 35 이상·180일 이내의 분석만 market context의 `policyDirection` 자동 입력으로 변환한다.
- 수동 입력은 override 경로로 유지하며, 수집 장애 시 마지막 유효 자동 입력을 보존한다.
- `FISCAL_STRESS` / `BOND_VIGILANTE_WARNING`의 기존 금리·HY 근거도 유지한다.
- 과거 FOMC 성명의 명시적 금리결정을 정답으로 confidence를 인과적 walk-forward 보정한다.

### 역사적 요구사항과 후속 확장
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

1. **#8 (13F)**: ✅ Phase 1-4와 CUSIP identity·20개 manager·CONSENSUS_DIVERGENCE 완료.
2. **#9 (정책 자동화)**: ✅ Fed·Treasury·USTR 원문, lexicon NLP, confidence gate·walk-forward calibration 완료.

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

---

## 🆕 16차 Phase 3 추가 TODO (2026-04)

### C2. 8-K / DART 중대 이벤트 수집 (장기)
원문 notion: "8-K(미국) / 전자공시(한국): 기업의 중대 이벤트"
- 범위: 합병/인수/CEO 교체/소송/리스트럭처링 등 종목별 이벤트
- 난도: 🔴 높음 (SEC EDGAR 8-K XBRL 파싱 + 이벤트 분류 NLP)
- 현재: ✅ 미국 8-K/6-K/Exhibit/IR과 한국 OpenDART 중대 이벤트·연결재무 parser 구현 완료.
- OpenDART 운영 수집은 공식 API 키가 주입된 환경에서만 활성화한다.

### E1. OBV / VWAP (경미)
- video2/3: 거래량 확인 강조
- 현재: ✅ 기업 OHLCV에서 OBV 방향·VWAP 괴리와 합성 확인 점수를 계산하고 API/UI에 노출
- 난도: 🟡 중 (fetchYahooHistory 를 OHLCV 확장 필요)
- 대체: SECTOR 20D return + STRONGEST 로 모멘텀 반영 중

### Phase 2 A2. horizon 기반 signal 가중 (중간 공수)
- video1 §5부 "시계열 먼저 정해야"
- 현재: ✅ 단기/중기/장기별 품질·가치·추세·바닥 가중치와 액션을 별도로 계산
- 확장 여지: signal 레벨 (예: short 투자자는 RSI 가중↑, long 은 월봉 아웃사이드 가중↑)
- 검증: ✅ 시점별 당시 데이터만 사용하는 causal walk-forward로 20/63/126 거래일 결과를 독립 평가
