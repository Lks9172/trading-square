# PRD: 매크로 기반 포트폴리오 판단 시스템

> 버전: 2.0  
> 작성일: 2026-04-13  
> 근거 자료: 자산제곱 영상 5편 + STT 전사본 + Notion 자료 모음 + 자산제곱 대시보드  
> 제품명(가칭): **MacroSquare**

> 구현 상태: **PRD 기준 핵심 기능 대부분 구현 완료**  
> 현재 남은 핵심 미구현: **KRX 외국인 수급 연동**

---

## 목차

1. [제품 개요](#1-제품-개요)
2. [핵심 철학](#2-핵심-철학)
3. [사용자 정의](#3-사용자-정의)
4. [Phase 1 — 시장 판단 대시보드](#4-phase-1--시장-판단-대시보드)
5. [Phase 2 — 시그널 엔진](#5-phase-2--시그널-엔진)
6. [Phase 3 — 비중 조절 시스템](#6-phase-3--비중-조절-시스템)
7. [데이터 아키텍처](#7-데이터-아키텍처)
8. [기술 스택 권장안](#8-기술-스택-권장안)
9. [릴리즈 로드맵](#9-릴리즈-로드맵)
10. [리스크 및 제약](#10-리스크-및-제약)
11. [실제 구현 현황](#11-실제-구현-현황)

---

## 1. 제품 개요

### 1.1 한 줄 정의

> 거시경제·유동성·기술적 지표를 종합해 **"지금 어떤 자산을 얼마나 들고 있어야 하는가"**를 판단하는 시스템

### 1.2 배경

투자 시장에서 대부분의 콘텐츠는 "뭘 살까"에 집중한다.  
하지만 영상 분석 결과, 실제 성과를 결정하는 것은 **종목이 아니라 타이밍과 비중**이다.

- 영상 1: 1억을 빠르게 불리는 4가지 전략 → **저점 집중 + 레버리지 조건 + 비중 조절**
- 영상 2: 원자재 매매 → **금/은/구리 각각 다른 국면 판단 기준**
- 영상 3: 이동평균선 매매법 → **200DMA + 실업수당으로 조정 vs 붕괴 구분**
- 영상 4: 저점 잡는 7가지 기준 → **7개 렌즈(정책/지정학/유동성/매크로/모멘텀/기관/차트) 종합 판단**

이 4개 영상의 공통 결론:

> "뭘 샀느냐가 아니라, **언제·얼마나** 샀느냐가 결과를 결정한다."

### 1.3 제품이 해결하는 문제

| 문제 | 현재 | 이 시스템 |
|---|---|---|
| 시장 상태 판단 | 뉴스/유튜브에 의존, 감정적 | 지표 기반 국면 분류 |
| 매수 타이밍 | "느낌"으로 진입 | 조건 기반 신호 |
| 비중 결정 | 올인 또는 방치 | 국면별 자산 비중 제안 |
| 공포 구간 대응 | 패닉셀 또는 동결 | 구조적 분할매수 가이드 |
| 자산군 선택 | 인기 종목 추종 | 국면에 맞는 자산군 우선순위 |

### 1.4 제품이 하지 않는 것

- 개별 종목 추천
- 완전 자동매매
- 100% 예측 보장
- 단타/스캘핑 신호

이 시스템은 **국면 판단 + 비중 가이드 + 분할매수 구조**에 집중한다.

---

## 2. 핵심 철학

영상에서 반복된 원칙들을 시스템 설계의 기초로 삼는다.

### 2.1 영상에서 추출한 설계 원칙

| 원칙 | 출처 | 시스템 반영 |
|---|---|---|
| 차트는 결과이고, 지표가 원인이다 | 영상 2 | 거시지표 → 차트 순서로 판단 |
| 하나의 렌즈로 보면 왜곡된다 | 영상 4 | 다중 지표 종합 점수 |
| 유동성은 총량보다 방향이다 | 영상 4 | 유동성 방향 지표 설계 |
| 이평선은 신호등이 아니라 진단 도구다 | 영상 3 | MA를 국면 분류 입력으로 사용 |
| 골든크로스는 늦고 데드크로스는 기회일 수 있다 | 영상 3 | 크로스를 역발상 필터로 활용 |
| 추격매수 금지, 분할매수 필수 | 영상 1,2,3 | 단계별 진입 구조 |
| 200일선 아래 = 도망이 아니라 기회일 수 있다 | 영상 3 | 200DMA + 매크로 필터 조합 |
| 실질금리 > 달러 > 중앙은행 매수 > 지정학 | 영상 2 | 금 판단 우선순위 로직 |
| 은은 금의 저렴한 버전이 아니다 | 영상 2 | 은 별도 판단 엔진 |
| 구리는 경기 선행, 주식보다 2~3개월 빠르다 | 영상 2 | 구리 신호를 주식 판단에 활용 |
| 2%의 확률적 우위를 반복하면 압도적 차이가 된다 | 영상 4 | 확률 기반 설계, 적중률 100% 불추구 |

### 2.2 설계 제약

- 정책/지정학은 시스템이 자동 점수화하기 어렵다 → **사용자 입력 또는 반자동**
- 차트 패턴(컵앤핸들, 웨지 등)은 주관성이 크다 → **보조 참고로만 사용**
- 포지션 사이징 구체 수치는 영상에 없다 → **합리적 기본값 + 사용자 조절**

---

## 3. 사용자 정의

### 3.1 주요 사용자

| 페르소나 | 특징 | 핵심 니즈 |
|---|---|---|
| 직장인 투자자 | 시간 부족, 장기 관점, ETF 중심 | "지금 뭘 얼마나 들고 있어야 해?" |
| 자산배분 학습자 | 매크로에 관심, 유튜브로 공부 중 | "이 지표들을 한눈에 보고 싶어" |
| 적극 투자자 | 레버리지/원자재 포함, 타이밍 중시 | "저점 신호 + 비중 가이드가 필요해" |

### 3.2 사용 시나리오

**시나리오 A: 평상시 (주 1~2회 확인)**
1. 대시보드에서 시장 국면 색상 확인
2. "현재 국면: 중립-방어적" 같은 한 줄 요약 확인
3. 자산별 신호 상태 확인 (금 우호 / 나스닥 대기 등)
4. 현재 포트폴리오 비중 제안 확인

**시나리오 B: 급락 발생 시**
1. 알림 수신: "VIX 35 돌파 + 200DMA 이탈"
2. 대시보드에서 저점 후보 점수 확인
3. 분할매수 시나리오 확인 (1차/2차/3차 구간)
4. 비중 제안: "나스닥 비중 20→30%, 레버리지 5% 허용"

**시나리오 C: 자산 교체 판단**
1. 금은비가 80 돌파 + 경기회복 신호 감지
2. 시스템이 "은 비중 확대 고려" 신호 표시
3. 사용자가 금→은 일부 교체 결정

---

## 4. Phase 1 — 시장 판단 대시보드

### 4.1 목적

> 시장의 현재 상태를 **한 화면**에서 판단할 수 있게 한다.  
> "지금 공격할 때인가, 방어할 때인가, 기다릴 때인가"를 보여준다.

### 4.2 대시보드 구성

#### 4.2.1 국면 판단 헤더

화면 최상단에 현재 시장 국면을 한 줄로 표시한다.

```
┌─────────────────────────────────────────────┐
│  현재 국면: 🟡 중립-경계    종합점수: 54/100  │
│  "조정 구간 진입 가능성. 분할매수 준비 단계"   │
└─────────────────────────────────────────────┘
```

**국면 분류 (6단계)**

| 국면 | 색상 | 의미 | 행동 가이드 |
|---|---|---|---|
| RISK_ON | 🟢 | 위험자산 우호, 유동성 확장 | 공격적 비중 유지 |
| NEUTRAL | 🔵 | 방향 미확정, 혼조 | 현 비중 유지, 관망 |
| CAUTION | 🟡 | 경계 신호 출현, 과열 징후 | 신규 매수 자제, 익절 고려 |
| CORRECTION | 🟠 | 조정 진행 중 | 분할매수 준비/시작 |
| PANIC_BUT_OK | 🔴 | 공포 극대화, 펀더멘털은 유지 | 적극 분할매수 구간 |
| RECESSION_RISK | ⚫ | 구조적 붕괴 위험 | 현금 비중 극대화 |

---

#### 4.2.2 지표 패널 구성

**A. 시장 가격 패널**

| 지표 | 소스 | 표시 항목 | 업데이트 |
|---|---|---|---|
| S&P 500 | Yahoo `^GSPC` | 가격, 전고점 대비 낙폭(%), 200DMA 대비 위치 | 일간 |
| 나스닥 | Yahoo `^IXIC` | 가격, 낙폭(%), 200DMA 위/아래 | 일간 |
| 코스피 | Yahoo `^KS11` | 가격, 낙폭(%) | 일간 |
| 금 | Yahoo `GC=F` | USD/온스, 200DMA 대비 | 일간 |
| 은 | Yahoo `SI=F` | USD/온스 | 일간 |
| 구리 | Yahoo `HG=F` | USD/파운드 | 일간 |
| WTI | Yahoo `CL=F` | USD/배럴 | 일간 |
| 원/달러 | Yahoo `KRW=X` | 환율 | 일간 |

**B. 거시·금리 패널**

| 지표 | 소스 | 의미 | 임계값 |
|---|---|---|---|
| DXY | Yahoo `DX-Y.NYB` | 달러 강약 | — |
| 미국 10Y 금리 | FRED `DGS10` | 장기금리 기준 | — |
| 장단기 금리차 | FRED `T10Y2Y` | 역전 시 침체 선행 | 음수 = 경고 |
| 실질금리 | 계산: `DGS10 - T10YIE` | 금 핵심 드라이버 | 하락 = 금 우호 |
| 기대인플레 BEI | FRED `T10YIE` | 실질금리 계산용 | — |
| CPI (YoY) | FRED/BLS | 물가 방향 | — |
| ISM 제조업 PMI | ISM/TradingEcon | 경기 체감 | 50 기준 |

**C. 유동성 패널**

| 지표 | 소스 | 의미 | 임계값/해석 |
|---|---|---|---|
| 연준 총자산 | FRED `WALCL` | QE/QT 방향 | 증가=완화, 감소=긴축 |
| 지급준비금 | FRED `WRESBAL` | 은행 유동성 체력 | 3조$ 이상 안전 |
| RRP | FRED `RRPONTSYD` | 초과유동성 흡수 | 감소=시장 유입 |
| TGA | FRED `WTREGEN` | 재무부 지출/흡수 | 감소=유동성 공급 |
| MMF 잔액 | FRED `WRMFNS` | 대기 자금 규모 | 감소=위험자산 이동 |
| M2 | FRED `M2SL` | 광의 통화량 | YoY 방향 |
| SOFR/IORB 스프레드 | 계산 | 자금시장 긴장도 | SOFR>IORB = 경고 |

**D. 심리·변동성 패널**

| 지표 | 소스 | 의미 | 임계값 |
|---|---|---|---|
| VIX | FRED `VIXCLS` | 공포지수 | <20 안정, 20~30 주의, >30 경계, >40 위기 |
| Fear & Greed | CNN 비공식 API | 심리 종합 | 0~25 극단공포, 75~100 극단탐욕 |
| 하이일드 스프레드 | FRED `BAMLH0A0HYM2` | 신용 스트레스 | >6% 주의, >8% 위기 |
| 금융스트레스지수 | FRED `STLFSI4` | 시스템 스트레스 | >1 경고, >3 위기 |
| SKEW | CBOE | 블랙스완 우려 | >140 경고 |
| Put/Call Ratio | CBOE | 매수/매도 심리 | >1.0 공포, <0.7 과열 |

**E. 고용 패널**

| 지표 | 소스 | 의미 | 임계값 |
|---|---|---|---|
| 실업률 | FRED `UNRATE` | 고용 건강도 | <4% 완전고용 |
| 신규 실업수당 | FRED `ICSA` | 고용 악화 속도 | >300K 침체 신호 |

**F. 비율·파생 지표 패널**

| 지표 | 계산 | 의미 | 임계값 |
|---|---|---|---|
| 금은비 | 금 / 은 | 은 저평가 판단 | >80 은 저평가 |
| 구리금비 | 구리 / 금 | 경기 vs 안전 | 상승=경기회복 |
| 나스닥 200DMA 이격도 | (가격-200DMA)/200DMA×100 | 과매도/과매수 | <-25% 과매도 |
| 나스닥 드로우다운 | (가격-ATH)/ATH×100 | 조정 깊이 | >-20% 약세장 |

**G. 사용자 수동 입력 패널**

시스템이 자동 점수화하기 어려운 항목은 사용자가 직접 판단해 입력한다.

| 항목 | 입력 방식 | 범위 |
|---|---|---|
| 정책 방향성 | 슬라이더 | -2(긴축) ~ +2(완화) |
| 지정학 리스크 | 슬라이더 | 0(안정) ~ 4(위기) |
| 스마트머니 방향 | 선택 | 유입 / 중립 / 유출 |

---

#### 4.2.3 시각화 요소

1. **국면 게이지**: 상단 중앙, 현재 국면 색상 + 점수
2. **유동성 방향 차트**: TGA/RRP/지급준비금/MMF 주간 변동 막대 그래프
3. **자산 히트맵**: 자산별 1W/1M/3M 수익률 색상 매트릭스
4. **상관관계 히트맵**: 주요 자산 90일 상관계수
5. **섹터 강도 바**: 11개 S&P 섹터 ETF 상대 강도

---

### 4.3 Phase 1 기능 목록

| ID | 기능 | 우선순위 | MVP |
|---|---|---|---|
| D-01 | FRED API 데이터 수집 (20+ 시계열) | P0 | ✅ |
| D-02 | Yahoo Finance 가격 수집 (15+ 종목) | P0 | ✅ |
| D-03 | 국면 분류 로직 (6단계) | P0 | ✅ |
| D-04 | 지표 패널 렌더링 (A~F) | P0 | ✅ |
| D-05 | 비율/파생 지표 자동 계산 | P0 | ✅ |
| D-06 | 사용자 수동 입력 패널 (G) | P1 | ✅ |
| D-07 | 유동성 방향 차트 | P1 | ❌ |
| D-08 | 자산 히트맵 | P1 | ❌ |
| D-09 | 상관관계 히트맵 | P2 | ❌ |
| D-10 | 섹터 강도 바 | P2 | ❌ |
| D-11 | CNN Fear & Greed 수집 | P1 | ❌ |
| D-12 | 모바일 반응형 | P1 | ❌ |

---

### 4.3.1 대시보드 UI 패널 목록

현재 구현된 대시보드는 15개 패널로 구성된다 (Dashboard.tsx 참고):

| 순서 | 패널명 | 역할 | 주요 표시 요소 |
|---|---|---|---|
| 1 | RegimeHeader | 국면 헤더 | 현재 국면 색상, 종합점수, 설명 |
| 2 | IndicatorPanel | 지표 표시 | 거시·금리·유동성·가격·섹터 지표 |
| 3 | SignalPanel | 자산별 신호 | 7자산 신호 상태 (STRONG_BUY ~ SELL) |
| 4 | AllocationPanel | 비중 제안 | 기본 비중 vs 신호 조정 비중 |
| 5 | ExecutionPlanPanel | 실행 계획 | 자산별 매수/익절/손절 진입 조건 |
| 6 | LensPanel | 7렌즈 분석 | 정책·지정학·유동성·매크로·모멘텀·기관·차트 |
| 7 | MultiTimeframePanel | 멀티 타임프레임 | 월봉·주봉·일봉 캔들 패턴 경고 |
| 8 | CalendarPanel | 경제지표 달력 | 주간 예정된 주요 경제지표 발표 |
| 9 | HistoryPanel | 히스토리 차트 | FRED/Yahoo 시계열 (드래그, 기간 선택) |
| 10 | OptionsVolatilityPanel | 옵션/변동성 | VIX, PCR, Put/Call ratio |
| 11 | RealtimePanel | 실시간 시세 | 주요 자산 현재가 + 변동률 |
| 12 | SmartMoneyPanel | 스마트머니 | 기관/내부자 매도/매수 신호 |
| 13 | StalenessPanel | 데이터 신선도 | 각 지표별 마지막 업데이트 시각 |
| 14 | SectorPanel | 섹터 모멘텀 | 11개 S&P 섹터 상대강도 |
| 15 | ManualInputsPanel | 수동 입력 | 정책/지정학/ISM/중앙은행 구매 |
| 16 | BacktestPanel | 백테스트 | 포트폴리오 과거 성과 시뮬레이션 |

**핵심 인터랙션:**
- 모든 차트는 반응형 (모바일·태블릿·데스크톱)
- 히스토리 차트: 드래그로 구간 선택, 기간/간격(1D/1W/1M) 선택 가능
- 신호 클릭 시 근거 표시 (어떤 조건이 충족/미충족)
- AllocationPanel: 총자산 입력 + 자산별 배분금액(원) 표시, 0% 자산도 명시 표시, 과열 배너

---

### 4.3.2 AllocationPanel — 비중 제안 & 자산별 배분금액

포트폴리오 비중 제안 패널에서는 다음 기능을 제공한다:

**입력 및 표시:**
- 총자산 입력 필드: 원화 숫자 입력 (억/만 단위 포맷 자동 변환)
- 막대 그래프: 자산별 비중 시각화 (0% 자산 제외, >0 만 렌더)
- 자산 목록: 전체 자산 항상 나열 (0% 자산도 muted 색상 + 0.3 opacity로 명시 표시)
- 배분금액: 총자산 × 비중(%)으로 계산한 자산별 배분금액(원) 동시 표시
- 눈금 라벨: 0%, 25%, 50%, 75%, 100% 위치 표기

**상태 표시:**
- **buyStage**: 분할매수 구간 명시. `null` 시 "데이터 없음 (200DMA 결측)" 표기
  - 0: 매수 구간 아님
  - 1: 1차 분할매수 구간 (200DMA 터치) — 목표 비중의 33% 투입
  - 2: 2차 분할매수 구간 (강한 조정) — 목표 비중의 33% 추가 투입
  - 3: 3차 분할매수 구간 (공포 극대화) — 나머지 34% 투입
- **과열 배너** (OVERHEATED 시): "⚠️ 과열 구간 감지: 현금 +20%, 금 +5%로 방어 강화. 나스닥/한국/신흥국/구리 비중이 자동 축소됨."
- **레버리지 게이트**: 3단계 티어 표시 (SOFT 5% / MEDIUM 10% / HARD 15% / 미발동 0%)

---

### 4.4 국면 분류 로직 상세

국면은 개별 지표의 상태를 점수화한 뒤 종합해서 결정한다.

#### 4.4.1 개별 지표 → 점수 변환

각 지표를 -2 ~ +2 범위로 정규화한다.

| 지표 | -2 (매우 부정) | -1 (부정) | 0 (중립) | +1 (긍정) | +2 (매우 긍정) |
|---|---|---|---|---|---|
| VIX | >40 | 30~40 | 20~30 | 15~20 | <15 |
| 장단기 금리차 | <-0.5% | -0.5~0% | 0~0.5% | 0.5~1.5% | >1.5% |
| 하이일드 스프레드 | >8% | 6~8% | 4~6% | 3~4% | <3% |
| 실업수당 | >350K | 300~350K | 250~300K | 200~250K | <200K |
| 나스닥 vs 200DMA | <-25% | -25~-10% | -10~0% | 0~+10% | >+10% |
| M2 YoY 방향 | 급감 | 감소 | 횡보 | 증가 | 급증 |
| RRP 방향 | 급증 | 증가 | 횡보 | 감소 | 급감(시장유입) |
| TGA 방향 | 급증(흡수) | 증가 | 횡보 | 감소 | 급감(공급) |
| DXY 방향 | 급등 | 상승 | 횡보 | 하락 | 급락 |
| 금융스트레스 | >3 | 1~3 | 0~1 | -0.5~0 | <-0.5 |
| 정책 방향(수동) | -2 | -1 | 0 | +1 | +2 |
| 지정학(수동) | 4 → -2 | 3 → -1 | 2 → 0 | 1 → +1 | 0 → +2 |

#### 4.4.2 종합 점수 계산

```
종합점수 = Σ(지표점수 × 가중치) / Σ가중치 × 100

가중치:
  VIX: 1.5
  장단기 금리차: 1.0
  하이일드 스프레드: 1.2
  실업수당: 1.5
  나스닥 vs 200DMA: 1.0
  M2 방향: 0.8
  RRP 방향: 0.7
  TGA 방향: 0.7
  DXY 방향: 0.8
  금융스트레스: 1.0
  정책(수동): 0.5
  지정학(수동): 0.5
```

#### 4.4.3 점수 → 국면 매핑

| 점수 범위 | 국면 |
|---|---|
| 75~100 | 🟢 RISK_ON |
| 55~74 | 🔵 NEUTRAL |
| 40~54 | 🟡 CAUTION |
| 25~39 | 🟠 CORRECTION |
| 10~24 | 🔴 PANIC_BUT_OK (실업수당 <300K일 때) |
| 10~24 | ⚫ RECESSION_RISK (실업수당 >300K일 때) |
| 0~9 | ⚫ RECESSION_RISK |

핵심: **PANIC과 RECESSION을 구분하는 것이 이 시스템의 가장 중요한 판단**이다.
이는 영상 3에서 가장 강조한 부분이기도 하다.

```
IF 종합점수 < 25:
  IF 실업수당 < 300K:
    → PANIC_BUT_OK ("공포지만 경제 체력은 유지, 분할매수 구간")
  ELSE:
    → RECESSION_RISK ("구조적 위험, 현금 비중 극대화")
```

#### 4.4.4 최우선 override (감사 Fix #5, 총 8종)

score 기반 일반 분기보다 먼저 평가하는 3가지 override:

1. **STAGFLATION_WARNING === 1** → `STAGFLATION` (영상4 §145)
   - CPI 유가 압력 + (ICSA 악화 or ISM<50) 동시 충족.
   - 물가↑ + 성장↓. 위험자산 축소, 금·은 방어 극대화.

2. **BOND_VIGILANTE_WARNING === 1** → `BOND_VIGILANTE` (영상4 §137-147)
   - DGS30 20일 +0.15%p↑ + DXY 장기 약세 + HY 확대 3축 중 2+ 충족.
   - "정책 신뢰 이탈 프리커서". 현금·금 극단 방어.

3. **OVERHEATED === 1 && score ≥ 55** → `CAUTION`
   - 이격도 +20% + F&G 75+ 또는 이격도 +15% + VIX<15.
   - "과열 시 강세장으로 해석하지 말라" (영상 5).

#### 4.4.5 CREDIT_STRESS_FLAG 강등 (감사 Fix #4)

score 분류 및 override 이후 추가로 적용:

```
IF CREDIT_STRESS_FLAG = 1:  # HY OAS ≥ 600bp OR HYG/IEF z ≤ -2
  RISK_ON → NEUTRAL
  NEUTRAL → CAUTION
  (CAUTION 이하 및 STAGFLATION/BOND_VIGILANTE 는 이미 방어적 → 추가 강등 없음)
```

---

## 5. Phase 2 — 시그널 엔진

### 5.1 목적

> 대시보드의 지표를 종합해 **자산별 행동 신호**를 생성한다.  
> "지금 이 자산을 사야 하는가, 기다려야 하는가, 줄여야 하는가"를 판단한다.

### 5.2 신호 체계

#### 5.2.1 신호 종류

| 신호 | 의미 | 색상 |
|---|---|---|
| STRONG_BUY | 적극 매수 구간 | 🟢 |
| BUY | 분할매수 시작 가능 | 🔵 |
| HOLD | 현 비중 유지, 관망 | ⚪ |
| REDUCE | 비중 축소 고려 | 🟡 |
| SELL | 비중 대폭 축소 / 현금화 | 🔴 |

#### 5.2.2 signalFromScore 5단계 임계치 (감사 Fix #1)

기존 `signalFromScore([hold, buy, strongBuy])` 3값 설정은 REDUCE/SELL 분기를
사실상 제거해 두 분기 모두 HOLD 로 귀결시키는 버그가 있었다. 아래 5값 객체로 전환:

```
signalFromScore(met, total, { sell, reduce, hold, buy, strongBuy })

판정 순서:
  met ≥ strongBuy → STRONG_BUY
  met ≥ buy       → BUY
  met ≥ hold      → HOLD
  met ≥ reduce    → REDUCE
  else            → SELL
```

자산별 실제 설정:

| 자산 | total | sell | reduce | hold | buy | strongBuy |
|---|---:|---:|---:|---:|---:|---:|
| NASDAQ | 7 | 0 | 2 | 3 | 4 | 5 |
| KOSPI  | 7 | 0 | 1 | 2 | 3 | 4 |

표준 권고: `strongBuy = total`, `buy = total-2`, `hold = total-4`, `reduce = total-5`, `sell = 0`.
자산별 PRD 스펙이 있으면 우선. 기존 HOLD 범위는 보존해 실서비스 회귀 최소화.

#### 5.2.3 과열 REDUCE override (감사 Fix #2)

met 계산 및 `signalFromScore` 판정 후, 최종 반환 직전에 과열 조건 2+ 충족 시
신호를 REDUCE 로 강등 (SELL 은 override 하지 않음).

**NASDAQ** — 4개 체크 중 2+ 발동 시 REDUCE:
- 이격도 ≥ +25%
- F&G ≥ 85
- VIX < 16
- `NASDAQ_CHASE_WARNING === 1` (이격률 ±15% 20일 지속)

**KOSPI** — 3개 체크 중 2+ 발동 시 REDUCE:
- 코스피 이격도 ≥ +20%
- `KOSPI_CHASE_WARNING === 1`
- `KOSPI_FX_ELASTICITY_DEVIATION ≥ 2` (외인 실매도가 환율 기대 대비 2배 이상)

#### 5.2.2 신호 출력 형태

```
┌──────────────────────────────────────────┐
│  나스닥 ETF(QQQ)    🔵 BUY              │
│  "200DMA 이탈 + 실업수당 안정 → 조정성   │
│   하락. 1차 분할매수 구간"               │
│  근거: 200DMA↓, ICSA 213K, VIX 32      │
│  충족 조건: 3/5                          │
├──────────────────────────────────────────┤
│  금(GLD)            🟢 STRONG_BUY       │
│  "실질금리 하락 + DXY 약세 + 중앙은행    │
│   매수 지속 → 강한 우호 환경"            │
│  근거: 실질금리↓, DXY↓, 200DMA↑        │
│  충족 조건: 4/4                          │
├──────────────────────────────────────────┤
│  은(SLV)            ⚪ HOLD             │
│  "금은비 높지만 경기회복 미확인"          │
│  근거: GSR 82, ISM 48.2                 │
│  충족 조건: 1/2                          │
├──────────────────────────────────────────┤
│  구리(CPER)         ⚪ HOLD             │
│  "ISM 바닥 미확인, 실업수당 미감소"       │
│  근거: ISM↓, ICSA flat, 구리RS neutral  │
│  충족 조건: 0/3                          │
└──────────────────────────────────────────┘
```

---

### 5.3 자산별 신호 규칙

#### 5.3.1 나스닥 / S&P 500 (지수 ETF)

**영상 근거**: 영상 1(저점 집중 매수), 영상 3(200DMA + 실업수당 조합)

| 조건 | 변수 | 신호 기여 |
|---|---|---|
| 가격 < 200DMA | `nasdaq_below_200dma = true` | +1 |
| 실업수당 < 300K | `icsa < 300000` | +1 |
| VIX > 30 | `vix > 30` | +1 |
| 이격도 < -15% | `disparity_200 < -15` | +1 |
| Fear & Greed < 25 | `fng < 25` | +1 |

**신호 결정:**

```
score = 위 조건 중 충족 개수 (보조 포함 total=7)

0~1개: SELL  (감사 Fix #1 REDUCE/SELL 복구)
2개:   REDUCE
3개:   HOLD
4개:   BUY
5~7개: STRONG_BUY

+ 보너스 (감사 Fix #4 / 2차 감사 Fix #3): PSYCH_SUBSCORE ≤ 0.20 (극공포) 이면 met 와 total 동시 +1
  — F&G·PC Ratio 10D·AAII·NAAIM 가중평균 저점 확인 시 저점 카테고리 보강.
  2차 감사 전에는 met 만 증가시켜 비율이 114% 까지 치솟고 임계가 붕괴했으나,
  total 도 +1 함께 증가시키고 임계는 total 상대값으로 재정의(strongBuy=total-2,
  buy=total-3, hold=total-4, reduce=total-5) 하여 보너스 발동 시 모든 임계가 +1 shift.

+ 과열 REDUCE override (감사 Fix #2): 4개 체크 중 2+ 충족 시 signal = REDUCE
  — 이격 +25%, F&G ≥ 85, VIX < 16, NASDAQ_CHASE_WARNING (이격률 ±15% 20일 지속)

+ CHASE_LEVEL 계층화 override (9차 후속 Fix #2): NASDAQ_CHASE_LEVEL / KOSPI_CHASE_LEVEL 소비
  - level 0: 무동작
  - level 1 (soft, 이격≥15% 또는 streak≥15일): unmetReasons 에 관측 경고만 추가, 신호 불변
  - level 2 (medium, soft + VIX<15 방심구간): STRONG_BUY → BUY (한 단계 강등)
  - level 3 (hard, 이격≥20% 또는 streak≥25일): STRONG_BUY/BUY → HOLD (기존 CHASE_WARNING 동등)
  - level null (CHASE_LEVEL 미발급): 기존 binary CHASE_WARNING override 경로 유지 (하위 호환)
```

**레버리지 허용 조건 (영상 1 원전 + 10차 Fix: 3단계 티어):**

영상 1 원전(-25/35/<300K=HARD)을 보존하면서, 저점 유사 구간을 확대해 발동
빈도를 실용 수준으로 조정. 실측상 지난 5년간 HARD 단일 조건은 0일 발동(너무 희소).

```
공통 게이트: 실업수당 < 300K (AND)

IF 이격도 <= -25% AND VIX >= 35 → HARD 티어
  → STRONG_BUY, 총자산 15% 이내

ELIF 이격도 <= -15% AND VIX >= 30 → MEDIUM 티어
  → BUY, 총자산 10% 이내

ELIF 이격도 <= -5% AND VIX >= 30 → SOFT 티어
  → BUY, 총자산 5% 이내

ELSE → 티어 미발동, HOLD, 0% (레버리지 불허)

판정 우선순위: HARD > MEDIUM > SOFT
목표 수익 20~30% 달성 또는 60/90일 타이머 만료 시 REDUCE (일반 ETF로 복귀)
LEVERAGE_TIER_RAW derived 발행 (0=none / 1=SOFT / 2=MEDIUM / 3=HARD)
```

**위험 신호:**

```
IF 가격 < 200DMA AND 실업수당 > 300K
THEN signal = SELL
  → "구조적 위험. 현금 비중 극대화"
```

---

#### 5.3.2 금 (Gold)

**영상 근거**: 영상 2 (실질금리 > DXY > 중앙은행 매수 > 지정학)

| 우선순위 | 조건 | 변수 | 가중치 |
|---|---|---|---|
| 1 | 실질금리 하락 추세 | `real_yield_falling = true` | 3 |
| 2 | DXY 약세 추세 | `dxy_falling = true` | 2 |
| 3 | 중앙은행 매수 지속 | `cb_buying = true` (수동 입력) | 1.5 |
| 4 | 지정학 리스크 확대 | `geo_risk >= 3` (수동 입력) | 0.5 |
| — | 가격 > 200DMA | `gold_above_200dma = true` | 1 (추세 확인) |

**신호 결정:**

```
gold_score = Σ(충족 조건 × 가중치) / 8.0 × 100

> 70: STRONG_BUY
50~70: BUY
30~49: HOLD
< 30: REDUCE
```

**8차 TOP7 추가 — GOLD_PRIORITY_SCORE 보조 조건 (4축 완성):**

```
GOLD_PRIORITY_SCORE: derived 4축 가중합 / 10 → 0~1
  - 축 1 (가중 4): REAL_YIELD_TREND < 0 (실질금리 하락)
  - 축 2 (가중 3): DXY_TREND < -0.5 (달러 단기 약세)
  - 축 3 (가중 2): manualInputs.cbBuying === true (중앙은행 매수)
  - 축 4 (가중 1): manualInputs.geoRisk >= 3 (지정학 위험 고조)
  → 총합 / 10 → 0~1 정규화
  - ≥ 0.7: reasons += "금 우선순위 스코어 ≥ 0.7 → 4축 (실질금리·DXY·CB매수·지정학) 금 매수 강화"
  - ≤ 0.3: unmetReasons += "금 우호 축 부족"
  (과거 일자 재계산 등 manualInputs 미전달 시 2축 폴백 /7 동작)
```

**예외 규칙 (영상 2):**

```
IF 실질금리_상승 AND DXY_강세:
  → 지정학 리스크만으로 금 매수 시 고점 물림 위험
  → signal = max(HOLD) 제한
```

---

#### 5.3.3 은 (Silver)

**영상 근거**: 영상 2 (금은비 + 경기회복 동시 충족 필요)

**이중 게이트 규칙** (6차 추가):

```
silver_signal =
  (금은비 >= 80)
  AND (ISM_제조업 > 50 OR regime ∈ {RISK_ON, NEUTRAL})
  AND (실업수당 감소 추세 OR 신규청구 < 300K)

이중 게이트 조건:
  1. GSR >= 70 (은이 저평가)
  2. ISM >= 50 OR 국면이 RISK_ON/NEUTRAL (경기회복 신호)
  
IF 이중 게이트 모두 충족:
  signal = BUY ("금 대비 은 비중 확대 고려")
ELSE:
  signal = HOLD ("침체 구간 오진입 억제")
```

**침체 방지 로직**:
- 게이트 설계 목표: GSR 단독으로는 침체 구간에 은을 과매수하지 않음
- ISM 50 미만 + 국면이 CAUTION/CORRECTION 이면 신호 억제 → 오진입 회피

---

#### 5.3.4 구리 (Copper)

**영상 근거**: 영상 2 (ISM 반등 + 구리/금 상대강도 + 실업수당 감소)

```
copper_buy_signal =
  (ISM_바닥_반등 = true)    # ISM이 저점 찍고 올라오기 시작
  AND (구리금비_상승전환 = true)  # 구리가 금 대비 강세 전환
  AND (실업수당_감소추세 = true)  # 고용 개선 중

충족 조건:
  3/3: STRONG_BUY
  2/3: BUY
  1/3: HOLD
  0/3: HOLD
```

---

#### 5.3.5 코스피 (KOSPI / 한국 주식)

**영상 근거**: 영상 5 "코스피 4축 판단: 환율·추세·거래량·외국인수급"

코스피는 나스닥과 달리 환율(원/달러), 외국인 수급, 거래량 확인이 핵심이다. 총 7조건:

| 조건 | 변수 | 신호 기여 |
|---|---|---|
| 가격 < 200DMA | `kospi_below_200dma = true` | +1 |
| 환율 우호 (1480원 이하) | `krw_fx_level >= 1` | +1 |
| 이격도 < -15% | `kospi_disparity < -15` | +1 |
| 유가 안정 ($80 미만) | `wti < 80` | +1 |
| VIX 낮음 (<25) | `vix < 25` | +1 |
| 거래량 확인 (최근 5일 ≥ 20일 평균 110%) | `kospi_volume_confirm = 1` | +1 |
| 외국인 20일 순매수 | `kospi_foreign_net_20d > 0` | +1 |

**신호 결정:**

```
score = 위 조건 중 충족 개수 (total=7)

0개:   SELL   (감사 Fix #1 REDUCE/SELL 복구)
1개:   REDUCE
2개:   HOLD
3개:   BUY (분할매수 준비)
4~7개: STRONG_BUY (여러 축 확인)

+ 과열 REDUCE override (감사 Fix #2): 3개 체크 중 2+ 충족 시 signal = REDUCE
  - 코스피 이격도 ≥ +20%
  - KOSPI_CHASE_WARNING === 1
  - KOSPI_FX_ELASTICITY_DEVIATION ≥ 2 (외인 실매도 ATM화)

+ USDKRW 주봉 채널 극단 (감사 Fix #4):
  - USDKRW_WEEKLY_CHANNEL_POSITION ≥ 0.9 → met -= 1 (FX 게이트 강화)
  - ≤ 0.1 → 보조 reason (원화 강세 복귀 우호)
```

**추세전환 게이트** (영상 5 코스피 편):

```
만약 STRONG_BUY 이지만 다음 중 2개 미만 충족:
  - 추세 회복 (KOSPI_TREND_RECOVERY = 1)
  - 거래량 확인 (kospi_volume_confirm = 1)
  - 환율 우호 (krw_fx_level ≥ 1)
THEN signal = BUY 로 강등
```

**위험 신호:**

```
IF 가격 < 200DMA AND 환율 > 1500원
THEN signal = SELL
  → "외국인 매도 압력 극대화 + 기술적 약세. 전량 청산"

IF 외국인 5일 이상 연속 매도
THEN signal 하향 (구조적 이탈 경고)
```

**다중 게이트** (영상 5 명시):

- **환율 그린 게이트**: KRW ≤ 1480원 → 외국인 복귀 우호
- **환율 레드 게이트**: KRW ≥ 1500원 → 외국인 매도 압력 임계

**8차 TOP7 추가 경고 (외인-개인 괴리):**

```
IF KOSPI_FOREIGN_INDIVIDUAL_DIVERGENCE === 1
  (외인 5D 순매도 ≥ 3조 AND 개인 5D 순매수 ≥ 3조)
THEN unmetReasons += "⚠️ 개인이 외인 매물 흡수 (역사적 악성 구도)"
  (met 변동 없음, 경고만)

IF KOSPI_FOREIGN_INDIVIDUAL_DIVERGENCE === -1
  (외인 5D 순매수 AND 개인 5D 순매도)
THEN reasons += "외인 주도 강세 후보"
```

---

#### 5.3.6 신흥국 (EMERGING)

**영상 근거**: 영상 2 §30 (달러약세 수혜) + 영상 4 (유동성·정책 렌즈)

신흥국은 달러 약세, 글로벌 유동성, 정책 완화에 직결된다. 총 3조건:

| 조건 | 변수 | 신호 기여 |
|---|---|---|
| DXY 약세 추세 | `dxy_trend < -0.5 or dxy < 103` | +1 |
| 글로벌 M2 확장 | `global_m2_proxy > 0` | +1 |
| 정책 완화 방향 | `policy_direction > 0` | +1 |

**신호 결정:**

```
score = 위 조건 중 충족 개수

0~1개: HOLD
2개:   BUY (2개 이상 확인 시 신흥국 수혜)
3개:   STRONG_BUY (모든 축 우호)
```

**방어 조건:**

```
IF DXY 단기 강세 (DXY_TREND > +1)
AND score < 3
THEN signal = REDUCE
  → "달러 급등 시 신흥국 자본 유출 경고"
```

**보조 신호:**

- 실질금리 하락 추세 (`real_yield_trend < -0.05`) → 신흥국 유동성 수혜 강화
- DXY 장기 약세 (`dxy_trend_long < -2`) → 구조적 우호 확인

---

#### 5.3.7 현금

현금은 다른 자산의 신호를 반전시킨 결과로 도출된다.

```
IF 국면 == RECESSION_RISK:
  현금 신호 = STRONG_BUY ("현금 비중 극대화")
IF 국면 == PANIC_BUT_OK:
  현금 신호 = REDUCE ("현금을 써서 분할매수")
IF 국면 == RISK_ON:
  현금 신호 = REDUCE ("투자 비중 확대")
```

---

### 5.4 Phase 2 기능 목록

| ID | 기능 | 우선순위 | MVP |
|---|---|---|---|
| S-01 | 나스닥/S&P 신호 엔진 | P0 | ✅ |
| S-02 | 금 신호 엔진 | P0 | ✅ |
| S-03 | 은 신호 엔진 | P0 | ✅ |
| S-04 | 구리 신호 엔진 | P0 | ✅ |
| S-05 | 현금 신호 (국면 역산) | P0 | ✅ |
| S-06 | 레버리지 허용 판정 | P1 | ✅ |
| S-07 | 신호 히스토리 저장 | P1 | ❌ |
| S-08 | 신호 변경 시 알림 (웹/텔레그램) | P1 | ❌ |
| S-09 | 근거 표시 (어떤 조건이 충족/미충족) | P0 | ✅ |
| S-10 | 백테스트 모듈 | P2 | ❌ |

---

## 6. Phase 3 — 비중 조절 시스템

### 6.1 목적

> 국면 판단 + 자산 신호를 종합해 **"지금 포트폴리오를 어떻게 구성해야 하는가"**를 제안한다.

이것이 영상들이 가장 강조한 "얼마나 사느냐"에 대한 답이다.

### 6.2 자산 유니버스

| 자산군 | 대표 종목 | 역할 |
|---|---|---|
| 현금/단기채 | 파킹, CMA, 단기채 ETF | 방어/대기 |
| 미국 지수 ETF | QQQ, SPY, VOO | 핵심 성장 |
| 미국 레버리지 ETF | QLD, TQQQ | 전술적 공격 (조건부) |
| 금 | GLD, IAU, KODEX 금선물 | 안전자산/인플레 헤지 |
| 은 | SLV, SIVR, KODEX 은선물 | 경기회복 + 저평가 시 |
| 구리/원자재 | CPER, COPX, TIGER 구리 | 경기회복 베팅 |
| 한국 주식 | KODEX 200, 개별주 | 국내 시장 노출 |
| 신흥국 ETF | EWZ, INDA, VNM | 달러약세 수혜 |

---

### 6.3 국면별 기본 비중 템플릿

#### 6.3.1 비중 매트릭스 (8종, 감사 Fix #5 반영)

| 자산군 | 🟢 RISK_ON | 🔵 NEUTRAL | 🟡 CAUTION | 🟠 CORRECTION | 🔴 PANIC_OK | ⚫ RECESSION | STAGFLATION | BOND_VIGILANTE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 현금 | 8% | 8% | 28% | 13% | 15% | 50% | 25% | 30% |
| 나스닥 | 32% | 38% | 29% | 35% | 35% | 15% | 15% | 10% |
| 레버리지 | 0% | 0% | 0% | 0% | 10% | 0% | 0% | 0% |
| 금 | 7% | 14% | 21% | 19% | 20% | 25% | 30% | 35% |
| 은 | 10% | 8% | 7% | 8% | 5% | 0% | 10% | 8% |
| 구리 | 6% | 7% | 4% | 5% | 5% | 0% | 5% | 3% |
| 코스피 | 18% | 14% | 11% | 11% | 5% | 5% | 8% | 7% |
| 신흥국 | 19% | 11% | 0% | 9% | 5% | 5% | 7% | 7% |

**최적화 근거** (Monte Carlo sweep, 10년 누적, DD penalty 0.15):

- **RISK_ON/NEUTRAL**: 신흥국·코스피 비중 상향 → 2016~2026 신흥시장 강세 구간 흡수
- **CAUTION/CORRECTION**: 은 편입 (7~8%) → 영상 2 "금은비 저평가 + 경기 신호 동반" 정합
- **현금 전반 축소**: 강세장 기간 수익 기여 극대화
- **PANIC_BUT_OK**: 레버리지 10% 허용 → 영상 1 "저점 집중 2~3개월 짧게" 구현
- **STAGFLATION** (영상4 §145): 물가↑+성장↓ → 금·은 방어, 위험자산 -50% 수준 축소
- **BOND_VIGILANTE** (영상4 §137-147): 장기금리 급등 → 현금·금 극단 방어 (gold 35 최대치)

**재선정 도구 — Top-Decile Sweep (샘플 내 과적합 완화):**

현재 수치는 Monte Carlo sweep **top1** (단일 최적해) 결과라 샘플 내 과적합 리스크가 있다.
이를 보완하기 위해 `server/src/scripts/portfolio-sweep.ts` 에 `--mode=decile` 옵션 추가:
상위 10% 조합의 **평균 비중** 을 regime 별로 출력한다 (robust 재선정 후보).

```bash
# 실행 (연산 비용 큼 — 수동 트리거)
cd server && npx tsx src/scripts/portfolio-sweep.ts --mode=decile
# 출력: regime 별 top1 / top10% 평균 / 현재 BASE diff 표

# 9차 후속 Fix #3: --output 플래그로 JSON 저장
cd server && PORTFOLIO_SWEEP_MODE=decile npx tsx src/scripts/portfolio-sweep.ts 40 --output
# 저장 경로: server/data/sweep-results/top-decile-<ISO-timestamp>.json (자동 생성)
#   또는 --output=<path> 로 명시 경로 지정
# JSON 스키마: { ranAt, mode, N, baseline, composed, perRegime:{ [regime]: {current, top1, decileMean, diff, top1Score} } }
```

**실제 BASE_ALLOCATIONS 숫자 교체는 별도 세션에서 검증 후 사용자 승인 거쳐 진행.**
이 도구는 후보 제시만 수행하며, 자동 덮어쓰기는 하지 않는다.

**핵심 통찰:**
- NASDAQ과 달리 한국(KOSPI) + 신흥국은 **환율과 달러 약세**에 민감
- 금은비가 70 이상 + ISM 50 이상 → 은 비중 추가 확대 고려 (영상 2 명시)
- 과열(OVERHEATED) 또는 재정스트레스(FISCAL_STRESS) 발동 → 현금/금 강화

---

#### 6.3.2 신호 기반 비중 조정

기본 템플릿 위에 개별 자산 신호로 미세 조정한다.

```
최종_비중[자산] = 기본_비중[국면][자산] × 신호_배수[자산] → 정규화

신호_배수 (영상 5 공통 원칙 "추격매수 금지, 분할매수 필수" 반영):
  STRONG_BUY → 1.3  (기본에서 +30%)
  BUY        → 1.1  (기본에서 +10%)
  HOLD       → 1.0  (유지)
  REDUCE     → 0.7  (기본에서 -30%)
  SELL       → 0.3  (기본에서 -70%)

주의: 기존 1.4/1.2/0.65/0.25 에서 더 보수적으로 조정 (과도한 추격매수 방지)

승수는 NASDAQ/KOSPI/GOLD/SILVER/COPPER/CASH/EMERGING 7자산에 적용된다.
(LEVERAGE 는 별도 게이트로 처리 — 아래 6.3.6 참고)

**자산 키 매핑 (2차 감사 Fix #8):** allocation/execution_plan 두 엔진은 자산→비중 키 매핑을
`engines/asset-keys.ts` 의 공통 상수 `ASSET_TO_ALLOC_KEY` 를 공유한다. LEVERAGE 는 맵에 포함되지만
signal multiplier 루프에서는 `sig.asset !== 'LEVERAGE'` 가드로 배제 — 별도 게이트로만 통제.
```

**예시:**

```
국면: CORRECTION (🟠)
금 신호: STRONG_BUY

기본 금 비중 = 25%
신호 배수 = 1.3
조정 비중 = 25% × 1.3 = 32.5%

→ 전체 합이 100%가 되도록 정규화
```

---

#### 6.3.3 환율 보정 (KRW_FX_LEVEL 게이트)

코스피 비중은 환율 레벨에 따라 자동 조정된다 (영상 5 "환율이 방향의 70% 결정").

```
| 환율 레벨 (원/달러) | KRW_FX_LEVEL | 코스피 조정 | 대상 비중 |
|---|---|---|---|
| ≤1480원 | >= 1 | 무조정 (우호) | — |
| 1480~1500원 | 0 | 무조정 | — |
| 1500~1550원 | -1 | -30% 삭감 | cash += 삭감분 |
| >1550원 | -2 | -50% 삭감 | cash += 삭감분 |

철학: "외국인 복귀 시 반발 가능성"(영상 5 명시) → 최악의 환율에서도 최소 포지션 유지 필요
```

---

#### 6.3.4 방어 보정 우선순위 정책 (감사 Fix #7)

기존: FISCAL_STRESS 와 OVERHEATED 가 **독립 if 블록**으로 순차 발동 가능했음.
동시 on 이면 cash / gold 이중 팽창으로 포트폴리오 분산이 왜곡 (cash>60%, gold>45%).

**채택 정책: 더 강한 쪽 하나만 적용 (exclusive)**

```
우선순위:
  FISCAL_STRESS_HARD  > FISCAL_STRESS / BOND_VIGILANTE > OVERHEATED

defenseMode =
  fiscalStressHard ? 'fiscal-hard' :
  fiscalStress     ? 'fiscal' :
  overheated       ? 'overheated' :
                     'none'

철학: FISCAL 은 "구조적/거시 위기", OVERHEATED 는 "단기 추세 과열" — 성격이
다른 두 보정을 동시 가중하면 dampen 이 과도해져 포지션 자체가 소멸.
판정 확정 후 보정 단계에서는 가장 강한 원인 하나로 집중.
```

##### 6.3.4.1 FISCAL_STRESS 보정

30년 금리 급등 + 높은 수준 → 위험자산 축소, 금·현금 방어. (영상 4 §07 채권 자경단)

```
IF defenseMode ∈ {'fiscal-hard', 'fiscal'}:
  위험자산 총합 (nasdaq, leverage, korea, emerging) 중
  amount = fiscal-hard ? 15 : 8  (hard 는 더 강하게)

  이관 비율: cash 60%, gold 40%

점화 조건 (감사 Fix #4 반영, OR 합성):
  FISCAL_STRESS === 1
  OR BOND_VIGILANTE_WARNING === 1  (4축 중 3+ 충족 — 아래 Fix #FE2 정의)
```

**Fix #FE2 — BOND_VIGILANTE 4축 정의 (영상4 §137-147 원 정의 복원)**

```
BOND_VIGILANTE_SCORE = sum of:
  1) 30y-10y 스티프닝 : (DGS30 - DGS10) > 0.4%p
  2) 장기금리 레벨    : DGS30 >= 4.8%
  3) DXY 약세         : DXY < 100 OR DXY_TREND_LONG < -2%
  4) HY 확대          : CREDIT_HY_OAS_BP >= 500 OR HYG/IEF z <= -1.5

BOND_VIGILANTE_WARNING = (SCORE >= 3)
```

기존 3축(장기금리 rise · DXY 약세 · HY) 에서 스티프닝/장기금리 레벨
분리. 미충족 축은 derived[].formula 에 명시돼 /api/snapshot 에서 관측.

**Fix #FE1 — 레짐/플래그 히스테리시스 (5분 스냅샷 whipsaw 방지)**

이진 플래그가 1↔0 깜빡일 때 BASE_ALLOCATIONS 즉시 전환되는 문제를
차단하기 위해 다음 플래그에 **minDays 히스테리시스** 적용.

| 플래그 | minDays |
|---|---|
| OVERHEATED | 7 |
| FISCAL_STRESS / FISCAL_STRESS_HARD | 7 |
| BOND_VIGILANTE_WARNING | 7 |
| STAGFLATION_WARNING | 14 (구조적 경보) |
| CREDIT_STRESS_FLAG | 7 |
| NASDAQ_CHASE_WARNING / KOSPI_CHASE_WARNING | 5 |

구현: `server/src/services/flagPersistence.ts` 가
`data/runtime/flag-state.json` 에 {value, sinceDate, lastChecked,
lastRaw} 영속. `cache.ts` snapshot 경로에서만 적용 (히스토리 재계산은
날짜별 확정값이라 제외). raw 가 confirmed value 와 다르면 sinceDate
로부터 경과일 누적, minDays 도달 시 flip. 중간에 raw 꺾이면 reset.

**Fix #FE3 — 근거 불명 상수 TODO 주석**

BASE_ALLOCATIONS(Monte Carlo top1), score 컷 {75,55,40,25}, scoreVIX
{40,30,20,15}, copperSignal CGR > 0.00125 등 경험적 상수 4곳에 근거
보강/재선정 TODO 주석 추가. portfolio-sweep.ts 에 각 regime top-decile
평균 출력 모드 추가(top1 과 diff 함께) — BASE 교체 후보로 사용 예정.
실제 숫자 교체는 sweep 재실행 + 백테스트 회귀 후 별도 커밋.

##### 6.3.4.2 OVERHEATED 보정

월봉 및 주봉이 극단적 상승으로 "아래꼬리 없는 장대양봉" (영상 5 과열 신호).

```
IF defenseMode === 'overheated':  (FISCAL 이 발동 안 했을 때만)
  위험자산 (nasdaq, leverage, korea, emerging, copper) 축소
  actual = min(available, 25)

  이관 비율: cash 20/25, gold 5/25

감지 기준:
  - 나스닥 이격도 +20% AND F&G 75+  →  OVERHEATED=1
  - 또는 나스닥 이격도 +15% AND VIX<15  →  OVERHEATED=1
```

##### 6.3.4.3 M2 유동성 쿠션 (감사 Fix #4 / 2차 감사 재적용)

```
IF !overheated AND 0 ≤ M2_YOY_CROSS_DAYS ≤ 90:
  NASDAQ 비중 +5% (cash 에서 이관)

철학: 글로벌 M2 YoY 음→양 교차 직후 90일은 유동성 랠리 초기 구간.
OVERHEATED 중에는 비활성 — 과열 위 쿠션은 리스크 증폭.
```

**적용 시점 (2차 감사 Fix #4):** 쿠션은 BASE_ALLOCATIONS 복사 + horizon shift 직후,
signal multiplier 루프 **이전** 에 적용한다. 기존에는 승수 적용 후 base.nasdaq 에 +5 를
덧셈해 "승수된 값에 쿠션 덧셈" 으로 누적 팽창했으나, 이제는 "쿠션된 원점에서 승수 적용"
으로 해석 일관성을 유지한다. 쿠션은 NASDAQ 에만 적용 — 레버리지는 leverageAllowed
게이트로 별도 통제되므로 누적 금지.

---

#### 6.3.5 USDKRW 주봉 채널 극단 경고 (감사 Fix #4)

KOSPI 신호 계산 시 USDKRW_WEEKLY_CHANNEL_POSITION 참조:

```
IF position ≥ 0.9 (상단 근접, 원화 약세 극단):
  KOSPI met -= 1  (FX 게이트 강화)
  reason 추가: "원화 약세 극단 → 외국인 매도 리스크"

IF position ≤ 0.1 (하단 근접, 원화 강세 극단):
  보조 reason: "원화 강세 복귀 우호"
  (met 가점은 없음 — 외국인 매수 전환은 별도 축에서 확인)
```

---

#### 6.3.6 레버리지 게이트 (LEVERAGE / 영상 1 저점 집중)

레버리지(2x ETF)는 극한 신호일 때만 허용하며, 자동 타이머로 관리된다 (영상 1 "2~3개월 짧게").

**allocation 게이트 (감사 Fix #3 + 10차 Fix: 티어별 상한):**

```
leverageCap = HARD → 15%, MEDIUM → 10%, SOFT → 5%, 없음 → 0%
leverageAllowed = leverageCap > 0 AND signal ∈ {BUY, STRONG_BUY}

기존은 === 'BUY' 만 통과시켜 STRONG_BUY 로 승격 시 레버리지 0% 로 비대칭 처리되는 모순 존재.
3/3 조건 충족 후 승격된 STRONG_BUY 에서도 레버리지 허용되도록 수정.
normalize 이후 leverageCap 초과분은 cash 로 이관.
```

**진입 조건 (3단계 티어, 10차 Fix):**

```
공통 게이트: 실업수당 < 300K

HARD  : 이격도 ≤ -25% AND VIX ≥ 35 → STRONG_BUY, 15% 상한 (영상1 원전)
MEDIUM: 이격도 ≤ -15% AND VIX ≥ 30 → BUY, 10% 상한
SOFT  : 이격도 ≤ -5%  AND VIX ≥ 30 → BUY, 5%  상한

판정 우선순위: HARD > MEDIUM > SOFT
  → 티어 미발동 시 HOLD, 0% (레버리지 불허)

배경: 영상1 원전(HARD) 단독은 실측 5년간 0일 발동. 저점 유사 구간(SOFT/MEDIUM)
확대로 발동 빈도 실용화. 리스크는 티어별 상한(5/10/15%)으로 분할 통제.
```

**자동 관리 (영상 1 §211-232 "시스템적 투자 규칙"):**

```
진입일 파일 저장 (data/leverage-entry.json)

상태 모니터링:
  - 진입 후 60일: ⚠️ 경고 발동 ("2~3개월 짧게" 명시)
  - 진입 후 90일: 강제 REDUCE 신호 (원금잠식 위험 회피)
  
목표 익절:
  - 목표 수익 20~30% 달성 시 자동 청산 → 일반 나스닥 비중으로 복귀
  
제약:
  - 최대 총자산의 15% (PANIC_BUT_OK 국면 기본값 10%)
  - 2x ETF만 허용 (3x 금지)
```

---

#### 6.3.7 결측 데이터 처리 원칙 (감사 Fix #6)

**원칙**: 결측은 명시적으로 표기·스킵한다. 0 이나 중립값으로 암묵 대체하지 않는다.
0 대체는 "알 수 없음" 을 "중립" 으로 위장해 신호에 거짓 안정성을 주입한다.

구체 조정:

| 위치 | 기존 | 수정 |
|---|---|---|
| `allocation.ts` `KRW_FX_LEVEL` | `?? 0` (환율 중립 가정) | `?? null` + 가드 → 결측이면 FX 보정 블록 전체 skip |
| `allocation.ts` `NASDAQ_ABOVE_200DMA` | `?? 1` (암묵 상승 가정) | `?? null` → `determineBuyStage` 가 `null` 반환, AllocationPlan.buyStage 타입 `0\|1\|2\|3\|null` 로 확장 |
| `derived.ts` `T10Y2Y` | `?? 0` (곡선 평탄 가정) | `null` 유지 + `curveSteepening = (!= null && > 0.1)` 로 "결측 → false" 를 명시 |

UI (`AllocationPanel.tsx`) 도 `buyStage === null` 일 때 "데이터 없음 (NASDAQ 200DMA 수집 실패)" 로 명시 표시.

---

### 6.4 분할매수 실행 구조

비중을 한 번에 바꾸지 않고, 단계적으로 전환한다.

#### 6.4.1 매수 단계

| 단계 | 조건 | 비중 전환 비율 |
|---|---|---|
| 1차 | 200DMA 터치 또는 이격도 -10% | 목표 비중의 33% |
| 2차 | 주요 지지선 도달 또는 이격도 -20% | 목표 비중의 33% |
| 3차 | W형 반등 확인 또는 이격도 -25% + VIX 정점 | 나머지 34% |

#### 6.4.2 축소 단계

| 단계 | 조건 | 비중 전환 비율 |
|---|---|---|
| 1차 축소 | 국면이 CAUTION으로 전환 | 초과 비중의 50% 축소 |
| 2차 축소 | 국면이 RECESSION으로 전환 | 목표 비중까지 전량 축소 |

---

### 6.5 출력 화면

```
┌─────────────────────────────────────────────────┐
│  📊 포트폴리오 비중 제안                          │
│  국면: 🟠 CORRECTION  |  종합: 34/100             │
├──────────┬──────────┬───────────┬────────────────┤
│ 자산     │ 현재비중  │ 제안비중   │ 행동           │
├──────────┼──────────┼───────────┼────────────────┤
│ 현금     │ 40%      │ 25%       │ 🔽 축소 (-15%) │
│ 나스닥   │ 25%      │ 33%       │ 🔼 확대 (+8%)  │
│ 금       │ 15%      │ 25%       │ 🔼 확대 (+10%) │
│ 은       │  0%      │  0%       │ ⬜ 유지        │
│ 구리     │  5%      │  5%       │ ⬜ 유지        │
│ 한국     │ 10%      │  7%       │ 🔽 축소 (-3%)  │
│ 신흥국   │  5%      │  5%       │ ⬜ 유지        │
├──────────┴──────────┴───────────┴────────────────┤
│ 💡 레버리지: 허용 (이격도 -27%, VIX 38, ICSA 215K)│
│    → QLD 최대 10% 진입 가능                       │
│ 📋 분할매수: 1차 구간 진입 (200DMA 터치)           │
└─────────────────────────────────────────────────┘
```

---

### 6.6 사용자 커스텀 설정

| 설정 항목 | 기본값 | 설명 |
|---|---|---|
| 투자 성향 | 중립 | 보수/중립/공격 → 비중 템플릿 시프트 |
| 총 투자금 | 사용자 입력 | 비중 → 금액 변환용 |
| 레버리지 사용 여부 | OFF | ON 시 조건 충족 시만 허용 |
| 한국 주식 포함 | ON | OFF 시 해당 비중 재분배 |
| 암호화폐 포함 | OFF | ON 시 위험자산 일부로 편입 |
| 리밸런싱 알림 주기 | 주간 | 일간/주간/월간 |

---

### 6.7 Phase 3 기능 목록

| ID | 기능 | 우선순위 | MVP |
|---|---|---|---|
| P-01 | 국면별 기본 비중 템플릿 | P0 | ✅ |
| P-02 | 신호 기반 비중 미세 조정 | P0 | ✅ |
| P-03 | 레버리지 게이트 판정 | P0 | ✅ |
| P-04 | 분할매수 단계 관리 | P0 | ✅ |
| P-05 | 비중 제안 화면 | P0 | ✅ |
| P-06 | 현재 비중 입력 (수동) | P0 | ✅ |
| P-07 | 비중 차이 → 행동 가이드 | P0 | ✅ |
| P-08 | 사용자 성향 설정 | P1 | ✅ |
| P-09 | 리밸런싱 히스토리 | P1 | ❌ |
| P-10 | 비중 변경 알림 | P1 | ❌ |
| P-11 | 백테스트: 과거 국면 기반 성과 시뮬레이션 | P2 | ❌ |
| P-12 | 증권사 API 연동 (실제 잔고 연동) | P3 | ❌ |
| P-13 | 트랑셰 영속화 (자산별 분할매수 추적) | P0 | ✅ |
| P-14 | 추격 경고 배지 (진입 후 국면 개선 시) | P0 | ✅ |
| P-15 | 이격 streak 경고 (OVERHEATED/OVERSOLD) | P0 | ✅ |
| P-16 | M2 방향 전환 추적 | P0 | ✅ |
| P-17 | 신용 스트레스 플래그 (HY OAS/z-score) | P0 | ✅ |
| P-18 | 심리 서브스코어 (심리 4지표 종합) | P0 | ✅ |

---

## 6.8 실행 플레이북 (Execution Plan)

국면 + 신호 + 파생지표를 종합해 **자산별 진입·손절·익절·유효기간**을 제시한다.

각 자산별 플레이북은 3단계 분할매수 구조를 따른다 (영상 3·5 "3단계 분할").

### 나스닥 플레이북

| 신호 | 1단계 | 2단계 | 3단계 | 손절 | 익절 |
|---|---|---|---|---|---|
| STRONG_BUY | 현재가 40% | -5% 30% | -10% 30% | SMA200 × 0.85 | +20% 또는 15Y 채널 상단 |
| BUY | 현재가 50% | -20% 30% | W반등 20% | SMA200 × 0.90 | 모멘텀 둔화 |
| HOLD | — | — | — | — | — |
| REDUCE | 현재가 30% 익절 | — | — | — | 15Y 채널 상단 터치 |
| SELL | 전량 청산 | — | — | — | — |

### 코스피 플레이북

| 신호 | 1단계 | 2단계 | 3단계 | 손절 | 익절 |
|---|---|---|---|---|---|
| STRONG_BUY | 현재가 40% | 이격도 -15% 30% | 외국인 trend 30% | SMA200 × 0.90 | +15% 또는 월봉 고점 |
| BUY | 현재가 50% | 거래량 확인 30% | 환율 개선 20% | SMA200 × 0.95 | 외국인 매도 전환 |
| HOLD | — | — | — | — | — |
| REDUCE | 현재가 30% 익절 | — | — | — | 월봉 고점 터치 |
| SELL | 전량 청산 | — | — | — | — |

### 금 플레이북

| 신호 | 진입 | 추가 진입 | 익절 시기 | 손절 | 비고 |
|---|---|---|---|---|---|
| STRONG_BUY | 현재가 진입 + 분할 | 실질금리 추가 하락 시 | +10~15% | 실질금리 급반등 | 중장기 보유 |
| BUY | 현재가 진입 | DXY 약화 시 | +8~10% | DXY 급등 시 | 유동성 확장 시 추가 진입 |
| HOLD | — | — | — | — | 관망 |
| REDUCE | 30% 익절 | — | 모멘텀 약화 | — | 과열 시 |
| SELL | 50% 이상 축소 | — | 즉시 익절 | — | 정책 긴축 신호 |

**기타 자산**(은, 구리, 신흥국)도 유사한 3단계 구조로 관리.

---

### 6.8.1 트랑셰 영속화 및 추격 경고 (6차 추가)

각 자산의 분할매수 진입을 추적하여 UI에서 체크박스로 관리하고, 추격 경고 배지를 표시한다.

#### 트랑셰 상태 저장

```
POST /api/execution-plan/tranche
{
  "asset": "NASDAQ",          # whitelist: NASDAQ/KOSPI/GOLD/SILVER/COPPER/LEVERAGE/EMERGING
  "stage": 1,                 # integer 1..5 (2차 감사 Fix #7: 입력 검증 강화)
  "priceAtEntry": 14500       # 제공 시 finite 양수 필수 (400 아니면 reject)
}

저장 위치: data/execution/tranche-state.json
```

**입력 검증 (2차 감사 Fix #7):**
- asset 허용 목록 밖 → 400 "invalid asset"
- stage 정수 1..5 벗어남 → 400 "invalid stage; integer 1..5 required"
- priceAtEntry 제공됐는데 Number.isFinite 실패 또는 ≤ 0 → 400 "invalid priceAtEntry"

#### UI 트랑셰 패널

```
자산 카드 예시:

[나스닥] BUY_NOW (목표 15%)
현재 14,500 | 유효 30일
  🗑️ 집행 취소 (집행 기록 있을 때만)

✓ 1차 33% @ 현재가 — [실행 가능] 집행됨
✓ 2차 33% @ -5% — [발동] 집행됨
□ 3차 34% @ W반등 — [대기] 집행 버튼

🛑 손절: SMA200 × 0.90 @ 13,050
🎯 익절: +20% 또는 15Y 채널 상단 @ 17,400

추격 경고 (있을 시):
  ⚠️ PANIC_BUT_OK → RISK_ON (상승)
```

**체크박스 동작:**
- 상태 표시 전용 (readOnly)
- 사용자 직접 클릭 불가
- "N차 집행" 버튼으로만 체크 가능
- 집행 취소 시 전부 초기화

#### 추격 경고 배지

```
IF regimeAtEntry < regimeCurrent (진입 후 국면이 상향):
  badge = "⚠️ 추격 경고"
  color = orange
  message = "진입 이후 환경이 개선됨. 기존 계획 재검토."
  
기본값: PANIC_BUT_OK (regimeAtEntry) → RISK_ON (현재)
  → 추격 경고 발동
```

---

### 6.8.2 집행 기록 취소 (7차 추가)

자산별 집행 기록을 완전히 초기화할 수 있는 "집행 취소" 기능이 추가되었다.

#### 사용자 인터페이스

자산 카드 우측 (유효일 아래) 에 "집행 취소" 버튼 추가:
- 집행 기록이 있는 자산(executedStages > 0) 에만 표시
- 클릭 시 커스텀 확인 모달 표시

#### 커스텀 확인 모달

native `confirm()` 대신 앱 디자인에 맞춘 모달 UI:

**스타일:**
- 백드롭: `bg-black/70 backdrop-blur-sm` (검은색 반투명 + blur)
- 카드: 앱 기본 테마 변수 사용 (`border-[var(--card-border)]`, `bg-[var(--card)]`)
- 헤더: 🗑️ 아이콘 + "집행 기록 초기화" 제목
- 바디: 자산명(볼드) + 취소 불가능 경고 문구

**버튼:**
- 취소: 투명 배경, muted 색상, 호버 시 `bg-white/5`
- 초기화: 빨간 배너 (`border-red-500/40 bg-red-500/15 text-red-200`), 처리 중 상태 표시

**클로징:**
- 백드롭 클릭
- 취소 버튼 클릭
- 초기화 완료 후 자동 닫기

#### API 엔드포인트

```
DELETE /api/execution-plan/tranche/:asset

응답: { success: true, message: "자산 NASDAQ 의 집행 기록 초기화됨" }

효과:
  - 해당 자산의 모든 트랑셰 기록 삭제 (executedStages 초기화)
  - 추격 경고 배지 리셋
  - 체크박스 전부 unchecked (readOnly 상태 유지)
```

#### Next.js 프록시 라우트

클라이언트 fetch 요청을 서버 API로 프록시:

```
client/src/app/api/execution-plan/tranche/[asset]/route.ts
  → DELETE: INTERNAL_API_URL + /api/execution-plan/tranche/:asset
```

---

### 6.8.3 API 계약 명시 (3차 감사 Fix #5)

GET `/api/execution-plan/tranche` 응답의 `summary` 필드는 **항상 배열** 이며 각 원소는 `AssetTrancheSummary` 구조를 따른다.

**응답 스키마:**

```typescript
interface AssetTrancheSummary {
  asset: string;                 // 자산명 (NASDAQ, KOSPI, GOLD, ...)
  executedStages: number[];      // 집행된 단계 배열 (e.g., [1, 2])
  nextStage: number | null;      // 다음 예정 단계
  latestRegime: string | null;   // 마지막 집행 시 국면
  latestExecutedAt: string | null; // 최신 집행 시각 (ISO 8601)
}

GET /api/execution-plan/tranche
Response: {
  entries: TrancheEntry[],
  summary: AssetTrancheSummary[]  // ← 항상 Array<T>, null 아님
}
```

**방어적 파싱 (클라이언트):**

UI에서 summary 를 사용할 때 Array.isArray() + asset 타입 가드로 null/비배열 fallback 처리:

```typescript
const summary = response.summary ?? [];
const validSummary = Array.isArray(summary) 
  ? summary.filter(s => typeof s.asset === 'string')
  : [];
```

---

## 6.9 캔들 형태 분석 (Candle Shape Analysis)

영상 3·4·5 "월봉 → 주봉 → 일봉" 위계적 판단을 자동화한다.

### 캔들 형태 분류

| 형태 | 정의 | 신호 | 검증 요소 |
|---|---|---|---|
| 장대양봉 (Marubozu) | body >= 90% | 강한 상승 압력 | 아래꼬리 < 5% |
| 장대음봉 | body >= 90%, close < open | 강한 하락 압력 | 윗꼬리 < 5% |
| 아래꼬리 (Hammer) | 아래꼬리 >= 60%, body < 40% | 매수 반전 신호 | 윗꼬리 < 10% |
| 윗꼬리 (Shooting Star) | 윗꼬리 >= 60%, body < 40% | 매도 반전 신호 | 아래꼬리 < 10% |
| 핀바 - 강세 (Bullish Pin) | 아래꼬리 >= 60%, body < 30%, 윗꼬리 < 10% | 하방 반전 후보 | 내일 상승 확인 필요 |
| 핀바 - 약세 (Bearish Pin) | 윗꼬리 >= 60%, body < 30%, 아래꼬리 < 10% | 상방 반전 후보 | 내일 하락 확인 필요 |

### 특수 패턴 (영상 5 명시)

**W 반등 패턴** (영상 3·5):

```
감지 조건:
  1. 최근 90일 내 local low 두 개 (low1, low2) 발견
  2. 중간 peak >= low1 × 1.05 (최소 5% 반등)
  3. low2 >= low1 × 0.97 (두번째 저점이 첫 저점 근처)
  4. 현재 close > peak × 1.005 (neckline 돌파)
  5. 현재 close > low2 × 1.05 (두번째 저점 대비 5% 반등)

→ 모든 조건 충족 시 "W 반등 확인" 신호 → execution plan stage 3 진입 가능
```

**월봉 과열 신호** (영상 5 코스피 편):

```
조건: 3개월 연속 장대양봉(body >= 90%) + 아래꼬리 없음(< 5%)

해석:
  - 매수 심리 극도로 높음
  - 조정 가능성 높음 (다음 월봉 음봉 또는 강한 조정)
  - NASDAQ/KOSPI 월봉 모두 점검 필수

대응: 신규 매수 자제, REDUCE 또는 TAKE_PROFIT 단계 진입
```

**15년 채널** (나스닥):

```
계산: 15년 일일 데이터 기반 선형회귀 채널
  - 상단: mean + 1.5σ
  - 중앙: mean
  - 하단: mean - 1.5σ

용도:
  - 나스닥 익절 기준 (상단 터치 → 익절 신호)
  - 장기 추세 방향성 판단
  - 극단 위치 (상단 >= 95%) → 과열 경고
```

---

## 6.10 파생 지표 (Derived Indicators)

약 50개 이상의 파생지표를 자동 계산해 국면·신호·실행계획에 활용한다.

### 지표 분류 및 히스토리 정책 (3차 감사 Fix #2/#3/#4)

#### 원자적 쓰기 정책

히스토리 저장(`writeHistory`)은 임시 파일 → rename 패턴으로 **원자적** 을 보장한다.
부분 쓰기 상태에서 프로세스 강제 종료 시에도 마지막 완성된 버전이 유지된다.

```typescript
// server/src/state/history-store.ts
const tmpPath = historyFile + '.tmp';
await fs.writeFile(tmpPath, JSON.stringify(combined, null, 2));
await fs.rename(tmpPath, historyFile); // ← 원자적 (OS 레벨)
```

#### 증분 재계산 규칙 (REFRESH_FULL 강제 플래그)

`refreshComputedHistories` 는 성능 최적화를 위해 다음 조건을 확인한다.

```
IF signal-REGIME 의 마지막 기록 >= 오늘 날짜 (KST):
  → 당일 재계산 완료된 상태. full backfill SKIP.
  → REFRESH_FULL=false 면 backfill 없음.
ELSE:
  → 계산 미완료. 과거 일자 전체 재계산 수행.
  → REFRESH_FULL=true 면 항상 full backfill (강제).
```

이를 통해 매 5분 스냅샷 시 불필요한 히스토리 재계산을 피하면서,
필요할 때만(시작 또는 강제) 과거 데이터를 정확히 복원한다.

#### Sentiment 실패 관측성

CNN F&G, P/C Ratio, AAII, NAAIM 수집 실패 시 silent skip 대신 **명시적 로깅** :

```typescript
// server/src/engines/sentiment.ts
if (!data) {
  logger.warn(`[sentiment] 수집 실패: source=${source}, key=${key}, date=${date}, reason=...`);
  // append skip — null 저장 금지 (멱등성 유지)
}
```

- **모니터링 가능**: 서버 로그에서 `[sentiment]` 필터로 실패 추적.
- **멱등성**: 같은 (source, key, date) 조합은 1회만 append. 재시도 시 스킵.
- **신호 정상화**: 결측 지표는 `signalEngine` 의 `dv()` 가드로 자동 제외 (met 카운트 왜곡 없음).

---

### 지표 분류

**가격 관련:**
- NASDAQ_DISPARITY (200DMA, 50DMA, 20DMA 대비 이격도)
- NASDAQ_DRAWDOWN (전고점 대비 낙폭)
- NASDAQ_ABOVE_200DMA (200DMA 위/아래 플래그)
- NASDAQ_DISPARITY_STREAK_OVERHEATED (200DMA 대비 +15% 연속 유지일)
- NASDAQ_DISPARITY_STREAK_OVERSOLD (200DMA 대비 -15% 연속 유지일)
- NASDAQ_DISPARITY_CHASE_WARNING (streak ≥ 20일 시 추격 경고)
- KOSPI_DISPARITY, KOSPI_DRAWDOWN, KOSPI_DISPARITY_STREAK_* 등

**신용 위험 및 유동성** (5차 추가):
- CREDIT_HY_OAS_BP (FRED BAMLH0A0HYM2: 하이일드 스프레드 basis points)
- CREDIT_HYG_IEF_RATIO (Yahoo HYG/IEF 비율: 신용 심화도)
- CREDIT_HYG_IEF_ZSCORE (252일 z-score: 신용 스트레스 정도)
- CREDIT_STRESS_FLAG (HY OAS ≥ 600bp OR z ≤ -2 시 경고)

**유동성 방향 및 M2** (5차·6차 추가):
- RRP_DIRECTION (역레포 변화 추세)
- TGA_DIRECTION (재무부 계정 변화)
- MMF_DIRECTION (머니마켓펀드 순변화)
- LIQUIDITY_DIRECTION (종합 유동성 점수)
- M2_YOY_PCT (M2 전년대비 %: 실제 공급량 추세)
- M2_YOY_DELTA_3M (M2_YOY 3개월 변화도: 방향 전환 감지)
- M2_YOY_CROSS_DAYS (음→양 교차 이후 경과일: "총량보다 방향" 원칙)

**비율 및 스프레드:**
- GOLD_SILVER_RATIO (금은비)
- COPPER_GOLD_RATIO (구리금비)
- SOFR_EFFR_SPREAD, SOFR_IORB_SPREAD (자금시장 긴장도)

**추세 지표:**
- DXY_TREND (단기 1~20일), DXY_TREND_LONG (장기 50~200일)
- REAL_YIELD_TREND (실질금리 추세)
- NASDAQ_CROSS, KOSPI_CROSS (골든/데드크로스 플래그)

**환율 및 외국인 수급:**
- KRW_FX_LEVEL (환율 레벨 분류: -2, -1, 0, 1, 2)
- KRW_FX_GREEN (≤1480원), KRW_FX_RED (≥1500원)
- KOSPI_FOREIGN_NET_20D (외국인 20일 순매수)
- KOSPI_FOREIGN_TREND (추세), KOSPI_FOREIGN_BUY_STREAK (연속매수일수)

**심리 서브스코어** (6차 추가):
- PC_RATIO_10D (CBOE Put/Call Ratio 10일 MA: 매수/매도 심리)
- AAII_BULL_BEAR_SPREAD (AAII Bull% - Bear%: 개인투자자 심리)
- NAAIM_EXPOSURE (NAAIM Exposure Index: 기관 리스크 노출도)
- PSYCH_SUBSCORE (F&G·PC·AAII·NAAIM 가중평균: null 시 재정규화)

**스마트머니 점수** (8차 추가):
- SMART_MONEY_SCORE (Insider+Dataroma 합성 점수: regime.components.smartMoney와 동일값 publish)
- SMART_MONEY_INSIDER_BUY_RATIO (OpenInsider 매수 비율)
- SMART_MONEY_DATAROMA_SCORE (Dataroma 포트폴리오 점수)
- SMART_MONEY_DATAROMA_NET_FLOW (Dataroma 순유입)

**다중 타임프레임:**
- NASDAQ_MONTHLY_EXHAUSTION (3개월 연속 장대양봉 + 아래꼬리 없음)
- NASDAQ_WEEKLY_REVERSAL (주봉 반전 신호)
- NASDAQ_MONTH_POS (월봉 위치 0~100%)
- 유사 지표: KOSPI, 금 등 모든 자산

**섹터 모멘텀:**
- SECTOR_XLK (기술섹터 나스닥 대비 상대강도)
- SECTOR_XLI (산업재)
- SECTOR_XLE (에너지)

**경고 신호:**
- BOND_VIGILANTE_WARNING (30년 금리 급등)
- STAGFLATION_WARNING (스테그플레이션)
- STAGFLATION_VERIFIED (9차 TOP3 Fix #1: WARNING + 실질금리↑ + 금↓ 3축 확증)
- GEOPOLITICAL_UNWIND_EVENT (9차 TOP3 Fix #2: KOSPI↑/USDKRW↓/WTI↓ 동시 급변, 0/1/2)
- SHORT_COVER_SUSPECTED (9차 TOP3 Fix #2: EVENT + 외인 1조+ 순매수)
- NASDAQ_CHASE_LEVEL / KOSPI_CHASE_LEVEL (9차 TOP3 Fix #3: 0/1/2/3 soft/medium/hard 계층)
- OVERHEATED (월봉 과열)
- FISCAL_STRESS, FISCAL_STRESS_HARD (재정 스트레스)

**기타:**
- NASDAQ_W_BOTTOM (W 반등 확인)
- NASDAQ_CHANNEL_POSITION (15Y 채널 내 위치 %)
- ISM_PROXY (ISM 제조업 PMI 프록시)
- CHASE_NASDAQ / CHASE_KOSPI (20일 상승률 → 추격매수 주의)

### 6.10.2 히스토리 백필 경로 (2차 감사 Fix #1+#2)

백필 파이프라인(`server/src/state/history-store.ts` → `refreshComputedHistories`)은
라이브 스냅샷과 **같은 시그니처** 로 derived 를 재계산한다:

1. 저장된 FRED/Yahoo 히스토리에서 anchor 날짜의 `raw` 를 재구성 (`buildRawForDate`).
2. 라이브 경로 `computeDerived(raw)` 를 anchor 루프 **이전에 1회 호출** 해
   STAGFLATION/BOND_VIGILANTE/OVERHEATED/CREDIT_STRESS_FLAG/PSYCH_SUBSCORE/MTF 등
   전체 derived 세트를 캐싱.
3. `recomputeFullDerivedForDate` 로 각 날짜별 NASDAQ(SMA200/DISPARITY/DRAWDOWN/ABOVE_200DMA)
   + 가격 비율(REAL_YIELD/GOLD_SILVER_RATIO/COPPER_GOLD_RATIO) 를 해당 date 기준으로 덮어써
   시계열 변동을 보존.
4. `classifyRegime({ raw, derived, manualInputs, smartMoneyScore: 0 })` — smart-money
   시계열 미보유라 0 명시.

**성능:** computeDerived 는 내부 Yahoo/FRED live fetch 가 많아 per-date 호출은 비용 폭발.
모듈 스코프 캐시로 1회만 수행. 1차 감사에서 추가된 파생지표들이 2차 감사 이후 히스토리
경로에도 정확히 반영된다 — 기존 `buildDerivedForDate` 는 NASDAQ 4개 필드 + 2개 비율만
채웠기 때문에 발생하던 "1차 Fix 성과 히스토리 미반영" 문제 해소.

**3차 감사 보강 — `cachedLiveDerived` 과거 일자 오염 차단 (날짜 의존 재계산 정책):**

2차 감사 이후 tracer 분석으로 확인된 추가 결함: shallow-clone 된 `cachedLiveDerived` 가
1,258 과거 일자 전체에 오늘 값으로 주입되어 STAGFLATION/MTF/PSYCH_SUBSCORE 등 11개+ 단발
snapshot 지표가 시계열 신호를 왜곡 (HOLD 되어야 할 날이 REDUCE 로 기록되는 등).

수정 정책:
- **오늘 일자(`todayIso === date`)** 만 `cachedLiveDerived` 를 그대로 사용. 라이브 스냅샷과
  완전 동일 시그니처 보장.
- **과거 일자**에는 빈 derived 맵에서 시작. `cachedLiveDerived` 는 일부러 무시.
- **date-aware 재계산 가능 지표 (history 만으로 복원 가능)** 만 채움:
  - `REAL_YIELD`, `GOLD_SILVER_RATIO`, `COPPER_GOLD_RATIO` (raw 가격 비율)
  - `NASDAQ_SMA200`/`DISPARITY`/`DRAWDOWN`/`ABOVE_200DMA`/`DISPARITY_STREAK_OVERHEATED`/
    `DISPARITY_STREAK_OVERSOLD`/`CHASE_WARNING` (yahoo:NASDAQ history)
  - `KOSPI_SMA200`/`DISPARITY`/`DRAWDOWN`/`ABOVE_200DMA`/`DISPARITY_STREAK_*`/`CHASE_WARNING`
    (yahoo:KOSPI history)
  - `CREDIT_HY_OAS_BP`, `CREDIT_HYG_IEF_RATIO`, `CREDIT_HYG_IEF_ZSCORE`, `CREDIT_STRESS_FLAG`
    (FRED BAMLH0A0HYM2 raw + Yahoo HYG/IEF history)
  - `M2_YOY_PCT`, `M2_YOY_DELTA_3M`, `M2_YOY_CROSS_DAYS` (FRED WM2NS history)
  - **(신규)** `PSYCH_SUBSCORE` + 패스스루 `PC_RATIO_10D` / `AAII_BULL_BEAR_SPREAD` /
    `NAAIM_EXPOSURE` — 센티먼트 raw history (cnn / sentiment 소스) + `reconstructPsychSubscore`.
    KST 07:00 cron 의 `appendSentimentDaily` 가 daily append (CNN F&G + CBOE P/C 10D + AAII
    spread + NAAIM exposure). 결측 시 append skip(null 저장 금지), `(source,key,date)` 멱등.
  - **(신규)** `OVERHEATED` — `reconstructOverheated` (NASDAQ_DISPARITY + F&G + VIX 룰 동일).
  - **(신규)** `BOND_VIGILANTE_SCORE` / `BOND_VIGILANTE_WARNING` / `FISCAL_STRESS` /
    `DGS30_20D_CHANGE` — `reconstructBondVigilante` (FRED DGS30 history + Yahoo DXY history +
    raw HY OAS). DGS30 < 100 (DXY 약세) · DGS30 Δ20 ≥ 0.15 · HY OAS ≥ 4.5 의 3축 합산.
- **단발 snapshot 지표 (history 미저장 → 과거 복원 불가)** 는 과거 일자에서 명시적으로
  null 유지 (= derived 맵에서 키 자체가 없음). signals.ts 의 `dv()` 가 null 가드로 깔끔히
  스킵하므로 met 카운트 왜곡이 발생하지 않음. 해당 지표:
  - `RRP_DIRECTION`, `TGA_DIRECTION`, `MMF_DIRECTION`, `GLOBAL_M2_PROXY` (raw snapshot)
  - `MTF_EXHAUSTION`, `NASDAQ_MONTHLY_*`, `KOSPI_MONTHLY_*`,
    `NASDAQ_WEEKLY_REVERSAL`, `KOSPI_WEEKLY_REVERSAL` (월/주봉 OHLC 리샘플링 필요)
  - `STAGFLATION_WARNING`/`SCORE` (CPI_OIL_LAG_PRESSURE · ICSA_REGIME_LABEL · ISM_PROXY 다단계
    derived 의존 — 별도 헬퍼 `reconstructStagflation` 으로 분리 예정)
  - `FISCAL_STRESS_HARD` (T10Y2Y 곡선 스티프닝 + DGS30 동시 조건 — 별도 헬퍼 분리 예정)
  - `SECTOR_*`, `SMART_MONEY_*`, `KRX_*`/`KOSPI_FOREIGN_*`, `USDKRW_WEEKLY_CHANNEL_*`
- 향후 단발 snapshot 지표를 history 에 보존하려면 해당 collectors 에 별도 history append
  (현 라이브 호출 결과를 daily snapshot 으로 기록) 가 필요. 센티먼트 4축은 본 정책에 이미
  반영. 섹터·SM·KRX·MTF·USDKRW 주봉은 별도 PRD 작업으로 분리.

**검증 — 커버리지 로그:** `refreshComputedHistories` 종료 시 최근 1년 평균 non-null
derived 키 수를 출력. 회귀 시 이 수치를 기준으로 데이터 손실 여부 점검.

### 6.10.3 SILVER/COPPER REDUCE 분기 (2차 감사 Fix #6)

SILVER/COPPER 신호는 `signalFromScore` 기반 임계로 통일:

- **SILVER** (total=2): `{strongBuy:2, buy:1, hold:1, reduce:1, sell:0}` + 이중 게이트 유지.
  - GSR≥70 AND (ISM≥50 OR regime RISK_ON/NEUTRAL) 메인 1개, ICSA<250K 메인 1개.
  - aux 2+ 면 STRONG_BUY 승격, 메인 1 + aux 2+ 면 BUY 승격, aux 0 면 BUY 차단 → HOLD.
  - met=0 → REDUCE (이전 HOLD 였던 약세 강등 분기 복구).

- **COPPER** (total=3): `{strongBuy:3, buy:2, hold:1, reduce:0, sell:0}`.
  - 기존 `met>=3 STRONG_BUY / met===2 BUY / else HOLD` 수치는 보존하고 met=0 → REDUCE 복구.

### 6.10.4 스케줄 timezone (2차 감사 Fix #5)

모든 `cron.schedule(...)` 호출에 `{ timezone: 'Asia/Seoul' }` 옵션 명시. 일일 히스토리
append cron 은 `0 22 * * *` (UTC) → `0 7 * * *` (KST) 로 표기만 변경 — 실제 실행 시각
동일(KST 07:00).

---

### 6.10.5 8차 TOP7 파생지표 확장 (2026-04-16)

8차 TOP7 감사 이후 추가된 파생지표 7종. 기존 함수 시그니처·임계치 체계를 보존하고
신규 키만 추가하는 원칙.

**1. KOSPI 개인 순매수 + 외인-개인 괴리 (Fix #1)**
- `KOSPI_INDIVIDUAL_NET_1D/5D/20D` (억원) — 네이버 금융 집계
- `KOSPI_FOREIGN_INDIVIDUAL_DIVERGENCE`: 외인·개인 5D 순매수 3조 기준 (+1 악성, -1 반대, 0 평시)
- kospiSignal 에 경고만 추가 (met 변동 없음).

**2. 호르무즈 연쇄 체인 (Fix #2)**
- `HORMUZ_CHAIN_SCORE`: WTI 60D + OVX + DXY_TREND + KRW_FX_LEVEL + KOSPI_FOREIGN_SELL_STREAK
  5축 가중합 → [-5..+5] 클램프
- `HORMUZ_CHAIN_LABEL`: ≤-3 "악성 연쇄" / -3~-1 "주의" / -1~+1 "중립" / +1~+3 "우호" / ≥+3 "완화 연쇄"

**3. GOLD_PRIORITY_SCORE (Fix #3)**
- derived 2축(REAL_YIELD_TREND 가중 4 + DXY_TREND 가중 3) / 7 → 0~1
- goldSignal 에서 ≥0.7 보강, ≤0.3 감산 reason 추가
- cbBuying/geoRisk 2축은 manualInputs 접근 제약으로 signal 레벨에서 이미 가산

**4. NASDAQ_CHANNEL_MID_CROSS (Fix #4)**
- 전일/당일 NASDAQ 종가와 15Y 회귀 중단선(MID) 비교
- +1 상향 크로스, -1 하향 크로스, 0 유지

**5. ICSA 52주 최저 재돌파 (Fix #5)**
- `ICSA_52W_LOW_RETEST`: 현재 ICSA 가 52주 최저 ×1.05 이내면 1
- `ICSA_RECOVERY_SIGNAL`: 재테스트(=1) AND 최근 4주 중 3회 이상 상승 → 1 (반등 추세)

**6. PSYCH_DIVERGENCE (Fix #6)**
- F&G ≤25 극공포 AND AAII spread ≥0 탐욕 → +1 (공포혼재)
- F&G ≥75 탐욕 AND AAII spread ≤-20 극공포 → -1 (탐욕혼재)
- 시그널 영향 없음, 관측 전용

**7. 추경·WGBI 장기 정책 이벤트 (Fix #7)**
- `calendar.ts STATIC_POLITICAL_EVENTS` 에 하드코드:
  - 2026-04-15 추경 국회 제출 (성장률 +0.2%p 효과)
  - 2026-10-15 추경 6개월 효과 반영일
  - 2026-10-01 WGBI 편입 예상일 (외국인 자금 유입 장기 환율 안정)
  - 2027-01-01 WGBI 1~2분기 지연 반영 시점
- `/api/snapshot` meta.calendar 에 `OTHER` 카테고리로 합류.

### 6.10.6 9차 gap TOP3 파생지표 (2026-04-17)

9차 감사 gap TOP3 반영. 원 영상·stt 인과 체인을 정량 교차검증 단계로 격상.

**1. STAGFLATION_VERIFIED (Fix #1) — 실질금리 교차검증**
- 위치: STAGFLATION_WARNING 직후
- 근거: video2 §67-68 "전쟁→유가↑→인플레↑→금리인하 꺾임→실질금리↑→금↓" 인과 체인
- 3축 교차검증 (모두 충족 시 1, 아니면 0, 결측 시 null):
  - 축 1: `STAGFLATION_WARNING === 1` (기존 2축 WARNING 발동)
  - 축 2: `REAL_YIELD_TREND > 0` (실질금리 상승)
  - 축 3: `GOLD` 20D 변화 `< 0` (금 하락)
- formula 에 3축 Y/N + 값 명시 (단서 투명성)
- 용도: WARNING 단독 발동과 실제 스태그플레이션 진입을 분리, 오판 차단
- **9차 후속 Fix #1: 히스토리 재계산 경로 편입**
  - 라이브 경로: 금 20D 소스를 `readHistory('yahoo','GOLD')` 우선 + `fetchYahooHistory('GC=F')` fallback
  - 백필 경로: `recomputeFullDerivedForDate` 가 `reconstructStagflationVerified` 헬퍼로 REAL_YIELD_TREND(DGS10·T10YIE 저장 히스토리) + GOLD 20D 재구성 → 과거 스냅샷에도 VERIFIED 채움
  - 결측 시 `value: null` 유지 (0 대체 금지)

**2. GEOPOLITICAL_UNWIND_EVENT + SHORT_COVER_SUSPECTED (Fix #2) — 점프 이벤트**
- 근거: stt_kospi 4:58·5:06 — 지정학 리스크 해소 시점 KOSPI↑/USDKRW↓/WTI↓ 동시 급변
- `readHistory('yahoo', 'KOSPI'/'USDKRW'/'WTI')` 마지막 2일 비교로 일봉 변동률 계산
- 3축:
  - 축 1: KOSPI 일봉 ≥ +5%
  - 축 2: USDKRW 일봉 ≤ -1.5%
  - 축 3: WTI 일봉 ≤ -10%
- `GEOPOLITICAL_UNWIND_EVENT`: 2축 충족=1, 3축 전부=2, 아니면 0, 결측 null
- `SHORT_COVER_SUSPECTED`: EVENT ≥ 1 AND `KOSPI_FOREIGN_NET_1D ≥ 10000`(1조, 억원 단위) → 1

**3. NASDAQ/KOSPI_CHASE_LEVEL (Fix #3) — 추격경고 hard/soft 계층화**
- 근거: video3 추격금지 원칙 정량화
- 0~3 단계 (clamp):
  - 0 (none): 조건 없음
  - 1 (soft): 이격도 ≥ +15% 또는 streak ≥ 15일
  - 2 (medium): level=1 조건 + VIX < 15 (방심 구간)
  - 3 (hard): streak ≥ 25일 또는 이격도 ≥ +20%
- 입력: `NASDAQ_DISPARITY`, `NASDAQ_DISPARITY_STREAK_OVERHEATED`, raw `VIXCLS` (KOSPI 동일)
- 기존 `NASDAQ_CHASE_WARNING` / `KOSPI_CHASE_WARNING` (binary) 는 signals.ts 소비 중이라 **병렬 유지**
  — LEVEL 은 raw 단계 노출, 히스테리시스는 기존 flagPersistence 로 WARNING 만 대상
- 결측 시 null, formula 에 각 단계 근거 명시 (예: "streak 26일 + 이격 +12% → 3(hard)")

---

## 7. 데이터 아키텍처

### 7.1 데이터 소스 총괄

| 소스 | 데이터 | 수집 방법 | 주기 |
|---|---|---|---|
| FRED | 금리, 유동성, 고용, 스트레스 등 20+ 시리즈 | REST API (무료, 키 필요) + retry/fallback | 일간~월간 |
| Yahoo Finance | 가격 15+ 종목 | yfinance 라이브러리 | 일간 |
| CBOE | VIX, SKEW, VVIX, OVX, PCR | 공개 데이터/delayed_quotes options 직접 집계 | 일간 |
| CNN | Fear & Greed Index | 비공식 API | 일간 |
| AAII | Bull/Bear Sentiment Spread | 공식 XLS 직접 파싱 | 주간 |
| NAAIM | Exposure Index | 페이지 HTML 테이블 파싱 | 주간 |
| TradingEconomics | ISM, CPI, PCE (보조) | 스크래핑 또는 API | 월간 |
| 사용자 입력 | 정책/지정학/스마트머니 | UI 입력 | 수시 |

#### 7.1.1 FRED 수집 안정성: Retry + History Fallback

22개 FRED 시리즈를 병렬 호출 시 transient 네트워크 실패(rate-limit, 일시 장애)로 일부가 누락되어,
signal 조건(예: "실업수당 < 300K") 이 결측으로 불발되는 문제가 발생했다. 대책:

1. **fetchSeries 1회 재시도** (내부):
   - 초기 요청 실패 시 200~500ms jitter 백오프 후 1회 재시도.
   - 2회 모두 실패 시 Error throw (호출자가 처리).

2. **fetchAllFred 히스토리 fallback** (호출자):
   - 22개 시리즈를 Promise.allSettled 로 병렬 호출.
   - 실패한 시리즈(`rejected`) 또는 빈 결과(`[]`)에 대해 `readHistory('fred', key)` 로 마지막 저장값 로드.
   - 원천 + 히스토리 모두 없을 때만 null (완전 결측 케이스).

3. **명시적 실패 로깅**:
   - 모든 실패 사유를 logger.warn 으로 기록 (silent drop 제거).
   - 모니터링: 서버 로그에서 `[FRED]` 필터로 누락 추적 가능.

**효과**: 당일 ICSA/UNRATE/IORB/WTREGEN/M2SL/M3_EURO 등이 누락되더라도, 신호 계산은 과거 값으로 진행되어
signal 조건이 완전히 불발되는 사태는 회피. 실패 지표를 명시적으로 추적하여 데이터 품질 문제 조기 발견 가능.

#### 7.1.2 센티먼트 소스 3종 교체 (2026-04)

기존 경로 일부 차단으로 신규 무료 대안 3가지 도입:

**Put/Call Ratio (PCR):**
- 기존: CBOE `PCR_ALL.csv` (403), Yahoo `^CPC`/`^CPCE` (404) → 모두 차단.
- **신규**: CBOE delayed_quotes options chain API (`_SPX`, `SPY`, `QQQ`) put/call volume 직접 집계.
  ```
  PCR = Σ(put_volume) / Σ(call_volume)
  ```
  당일 snapshot 값; 10일 MA는 history rolling (appendSentimentDaily 시 계산).

**AAII Bull/Bear Spread:**
- 기존: stooq 유료화로 접근 중단 (주석에 403 표기됨).
- **신규**: AAII 공식 XLS 직접 파싱 (실제로 200 OK, 기존 판단 오류).
  - URL: `https://www.aaii.com/files/surveys/sentiment.xls`
  - SENTIMENT 시트 row 7~ : Date(Excel serial) + Bullish% + Bearish% + Spread(소수).

**NAAIM Exposure Index:**
- 기존: CSV 다운로드 404 → 접근 불가.
- **신규**: 페이지 HTML 테이블 직접 파싱.
  - 페이지에서 `<tr><td>MM/DD/YYYY</td><td>값</td></tr>` 패턴 검색.
  - 최신 행의 exposure 값 사용.

**심리 렌즈 (6.10 PSYCH) 4축 복원:**
- F&G (CNN) + PCR (CBOE 직접 집계) + AAII (XLS 파싱) + NAAIM (HTML 파싱) 전부 가동.
- 각 소스 실패 시 append skip (null 저장 금지, 멱등성 유지).
- signalEngine 의 `dv()` 가드로 결측 지표 자동 제외 (met 카운트 왜곡 없음).

### 7.2 데이터 모델 (핵심 엔티티)

```
┌──────────────────┐
│ MarketData       │
│──────────────────│
│ id               │
│ indicator_code   │  # "VIXCLS", "DGS10", "^IXIC" 등
│ value            │
│ date             │
│ source           │  # "FRED", "YAHOO", "CBOE", "USER"
│ fetched_at       │
└──────────────────┘

┌──────────────────┐
│ DerivedIndicator │
│──────────────────│
│ id               │
│ indicator_name   │  # "real_yield", "gold_silver_ratio" 등
│ value            │
│ date             │
│ formula          │  # 계산식 참조
│ calculated_at    │
└──────────────────┘

┌──────────────────┐
│ RegimeState      │
│──────────────────│
│ id               │
│ date             │
│ regime           │  # RISK_ON ~ RECESSION_RISK
│ score            │  # 0~100
│ component_scores │  # JSON: 각 지표별 점수
│ calculated_at    │
└──────────────────┘

┌──────────────────┐
│ AssetSignal      │
│──────────────────│
│ id               │
│ asset            │  # "NASDAQ", "GOLD", "SILVER", "COPPER", "CASH"
│ signal           │  # STRONG_BUY ~ SELL
│ conditions_met   │  # JSON: 충족/미충족 조건
│ date             │
│ calculated_at    │
└──────────────────┘

┌──────────────────┐
│ AllocationPlan   │
│──────────────────│
│ id               │
│ date             │
│ regime           │
│ allocations      │  # JSON: {asset: percentage}
│ leverage_allowed │
│ buy_stage        │  # 1차/2차/3차/없음
│ calculated_at    │
└──────────────────┘

┌──────────────────┐
│ UserProfile      │
│──────────────────│
│ id               │
│ risk_tolerance   │  # conservative/moderate/aggressive
│ total_capital    │
│ leverage_enabled │
│ include_crypto   │
│ include_kr       │
│ rebalance_freq   │
│ manual_inputs    │  # JSON: 정책/지정학/스마트머니
└──────────────────┘
```

### 7.3 데이터 흐름

```
[FRED/Yahoo/CBOE/CNN] → 수집기 → MarketData
                                    ↓
                              계산 엔진 → DerivedIndicator
                                    ↓
                              국면 엔진 → RegimeState
                                    ↓
                              신호 엔진 → AssetSignal
                                    ↓
                    비중 엔진 + UserProfile → AllocationPlan
                                    ↓
                              UI / 알림
```

---

## 8. 기술 스택 권장안

| 계층 | 기술 | 이유 |
|---|---|---|
| 프론트엔드 | Next.js + TailwindCSS | 빠른 개발, SSR, 반응형 |
| 차트 | Recharts 또는 Lightweight Charts | 금융 데이터 시각화 |
| 백엔드 | NestJS (TypeScript) | 기존 레포 패턴과 호환 |
| DB | PostgreSQL | 시계열 + 관계형 혼합 |
| 캐시 | Redis | 실시간 지표 캐싱 |
| 스케줄러 | Bull Queue (Redis 기반) | 정기 데이터 수집 |
| 외부 API | FRED API, yfinance, CBOE | 무료/저비용 |
| 알림 | Telegram Bot API | 영상 채널과 동일 플랫폼 |
| 배포 | Vercel (프론트) + Railway/Fly.io (백엔드) | 빠른 배포 |

---

## 9. 릴리즈 로드맵

### Phase 1: 시장 판단 대시보드

| 마일스톤 | 기간 | 산출물 |
|---|---|---|
| M1.1 데이터 수집기 | 1주 | FRED + Yahoo 수집 파이프라인 |
| M1.2 파생 지표 계산 | 1주 | 실질금리, 금은비, 이격도 등 |
| M1.3 국면 분류 엔진 | 1주 | 점수 계산 + 6단계 분류 |
| M1.4 대시보드 UI | 2주 | 지표 패널 + 국면 헤더 |
| **Phase 1 완료** | **5주** | |

### Phase 2: 시그널 엔진

| 마일스톤 | 기간 | 산출물 |
|---|---|---|
| M2.1 나스닥/S&P 신호 | 1주 | 5조건 점수 + 레버리지 게이트 |
| M2.2 금/은/구리 신호 | 1주 | 자산별 룰 엔진 |
| M2.3 신호 UI + 근거 표시 | 1주 | 신호 카드 + 조건 충족 표시 |
| M2.4 알림 시스템 | 1주 | 텔레그램/웹 알림 |
| **Phase 2 완료** | **4주** | |

### Phase 3: 비중 조절 시스템

| 마일스톤 | 기간 | 산출물 |
|---|---|---|
| M3.1 비중 템플릿 + 신호 조정 | 1주 | 국면×자산 매트릭스 + 배수 조정 |
| M3.2 분할매수 단계 관리 | 1주 | 1차/2차/3차 트래킹 |
| M3.3 비중 제안 UI | 2주 | 현재 vs 제안 비교 화면 |
| M3.4 사용자 설정 | 1주 | 성향/자본금/옵션 설정 |
| **Phase 3 완료** | **5주** | |

### 총 예상 기간: 약 14주 (3.5개월)

---

## 10. 리스크 및 제약

### 10.1 기술적 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| FRED API 속도 제한 | 데이터 지연 | 캐싱 + 배치 수집 |
| Yahoo Finance 비공식 | 구조 변경 가능 | yfinance 업데이트 추적, 대체 소스 준비 |
| CNN F&G 비공식 API | 중단 가능 | 자체 심리지표 계산 대안 |
| ISM 등 일부 매크로 지표 | 유료/지연 | TradingEconomics 무료 티어 또는 수동 입력 |

### 10.2 설계적 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| 국면 분류 가중치가 자의적 | 신호 품질 | 백테스트로 검증 후 조정 |
| 정책/지정학 수동 입력 의존 | 업데이트 지연 | 뉴스 NLP 보조 도입 (Phase 4) |
| 비중 템플릿이 과거 편향 | 미래 시장에 안 맞을 수 있음 | 정기 리뷰 + 파라미터 조정 UI |
| 레버리지 게이트 오발동 | 과도한 위험 | 보수적 임계값 + 사용자 최종 승인 |

### 10.3 법적/규제 리스크

| 리스크 | 대응 |
|---|---|
| 투자 자문 해당 여부 | "정보 제공 목적이며 투자 권유가 아님" 면책 명시 |
| 금융 상품 추천 규제 | 구체 종목이 아닌 자산군/ETF 카테고리로 제안 |
| 데이터 재배포 | FRED(공공), Yahoo(개인용) 라이선스 준수 |

---

## 부록 A: 영상별 시스템 기여 매핑

| 영상 | 시스템에 기여하는 핵심 내용 |
|---|---|
| 1. 1억 빠르게 불리는 4가지 전략 | 저점 집중 매수 5조건, 레버리지 3조건(이격도/VIX/실업수당), 비중 조절 철학 |
| 2. 원자재 매매 | 금 판단 4순위(실질금리>DXY>중앙은행>지정학), 금은비, 구리 3조건(ISM/상대강도/실업수당), 계절성 |
| 3. 이동평균선 매매법 | 200DMA 기준, 실업수당 조합 규칙, 분할매수 3단계, 골든/데드크로스 역발상, 손절/익절 원칙 |
| 4. 저점 잡는 7가지 기준 | 7개 렌즈 프레임(정책/지정학/유동성/매크로/모멘텀/기관/차트), 차트 분석 순서(월→주→일→시간) |

## 부록 B: 지표 수집 소스 총괄

| 지표 | FRED 코드 / 소스 | 주기 | 자동 수집 |
|---|---|---|---|
| S&P 500 | Yahoo `^GSPC` | 일간 | ✅ |
| 나스닥 | Yahoo `^IXIC` | 일간 | ✅ |
| 금 | Yahoo `GC=F` | 일간 | ✅ |
| 은 | Yahoo `SI=F` | 일간 | ✅ |
| 구리 | Yahoo `HG=F` | 일간 | ✅ |
| WTI | Yahoo `CL=F` | 일간 | ✅ |
| DXY | Yahoo `DX-Y.NYB` | 일간 | ✅ |
| 원/달러 | Yahoo `KRW=X` | 일간 | ✅ |
| 코스피 | Yahoo `^KS11` | 일간 | ✅ |
| 10Y 금리 | FRED `DGS10` | 일간 | ✅ |
| 기대인플레 | FRED `T10YIE` | 일간 | ✅ |
| 장단기 금리차 | FRED `T10Y2Y` | 일간 | ✅ |
| VIX | FRED `VIXCLS` | 일간 | ✅ |
| 하이일드 스프레드 | FRED `BAMLH0A0HYM2` | 일간 | ✅ |
| 금융스트레스 | FRED `STLFSI4` | 주간 | ✅ |
| 연준 총자산 | FRED `WALCL` | 주간 | ✅ |
| 지급준비금 | FRED `WRESBAL` | 주간 | ✅ |
| RRP | FRED `RRPONTSYD` | 일간 | ✅ |
| TGA | FRED `WTREGEN` | 주간 | ✅ |
| MMF | FRED `WRMFNS` | 주간 | ✅ |
| M2 | FRED `M2SL` | 월간 | ✅ |
| 실업률 | FRED `UNRATE` | 월간 | ✅ |
| 신규실업수당 | FRED `ICSA` | 주간 | ✅ |
| SOFR | FRED `SOFR` | 일간 | ✅ |
| EFFR | FRED `EFFR` | 일간 | ✅ |
| 하이일드 스프레드 | FRED `BAMLH0A0HYM2` | 일간 | ✅ |
| HYG 가격 | Yahoo `HYG` | 일간 | ✅ |
| IEF 가격 | Yahoo `IEF` | 일간 | ✅ |
| Fear & Greed | CNN 비공식 | 일간 | ⚠️ |
| Put/Call Ratio | CBOE delayed_quotes (_SPX+SPY+QQQ) | 일간 | ✅ |
| AAII Bull/Bear | AAII 공식 XLS 파싱 | 주간 | ✅ |
| NAAIM Exposure | 페이지 HTML 테이블 파싱 | 주간 | ✅ |
| ISM PMI | TradingEcon/수동 | 월간 | ⚠️ |
| CPI | FRED/BLS | 월간 | ✅ |
| M2 (미국/유로/일본) | FRED `M2SL` / ECB / BOJ | 월간 | ✅ |
| 정책 방향 | 자동(EFFR), 수동 입력 | 수시 | 수동 |
| 지정학 리스크 | GPR 지수, 수동 입력 | 수시 | ✅/수동 |
| 중앙은행 금 매수 | 자동(중앙은행 프록시), 수동 | 분기 | ✅/수동 |

---

## 11. 실제 구현 현황

아래는 PRD 작성 이후 실제 코드 구현으로 확장된 기능들이다.

### 11.1 현재 구현 완료 항목

| 영역 | 구현 상태 |
|---|---|
| 시장 데이터 수집 | FRED + Yahoo + CNN Fear & Greed + GPR + OpenInsider + Dataroma + Earnings + CBOE P/C + AAII + NAAIM |
| 자동화 입력 | 정책 방향 자동화(EFFR), 지정학 자동화(GPR), 중앙은행 금 매수 프록시 자동화, ISM 자동화(TradingEconomics + INDPRO fallback) |
| 파생 지표 | 50개 이상 구현 (실질금리, DXY 단/장기 추세, 나스닥/코스피/금 이격도, 피보나치, 골든/데드크로스, W바닥, 유동성 방향, 섹터 모멘텀, 글로벌 M2 프록시, 신용 스트레드, M2 YoY 방향, 이격 streak, 심리 서브스코어 등) |
| 국면 엔진 | 11개 컴포넌트 기반 점수화 (VIX, Yield Curve, HY Spread, Jobless Claims, Nasdaq Disparity, FinStress, DXY, LiquidityDir, WTI, Global M2, Smart Money, Policy, GeoRisk) |
| 자산 신호 | 나스닥, 코스피, 금, 은, 구리, 현금, 레버리지 |
| 비중 조절 | 국면 템플릿 + 신호 배수 + 환율 보정 + 과열 시 현금/금 방어 강화 |
| 히스토리 | FRED 10년 / Yahoo 5년 영속 저장 + 일일 append |
| 시각화 | 반응형 대시보드, 실시간 패널, 히스토리 차트(드래그, 기간/간격 선택, 이중 y축), 섹터 모멘텀, 백테스트 차트 |
| 백테스트 | 나스닥 벤치마크 + 포트폴리오 비중 기반 백테스트 |
| 알림 | 텔레그램 신호 변경 + 포트폴리오 변경 + 전체 현황 알림 |
| 배포 | Docker Compose 기반 홈서버 배포 완료 |
| 5차 누적 | 신용 스트레드(CREDIT_HY_OAS/HYG/IEF), M2 YoY 방향(M2_YOY_PCT/DELTA/CROSS_DAYS), 은 이중 게이트(GSR≥70 AND ISM≥50/regime) |
| 6차 누적 | 이격 streak(OVERHEATED/OVERSOLD/CHASE_WARNING), 트랑셰 영속화 + 추격 경고 배지, 심리 서브스코어(F&G/PC/AAII/NAAIM 가중평균) |
| 7차 TOP3 | 센티먼트 4지표 UI(AAII/NAAIM/PCR/PSYCH 카드 + 임계 라벨 + 결측 "데이터 없음"), FX_FOREIGN_COMBO_ALERT 이중 게이트(환율×외인 streak, HARD=2/SOFT=1/WATCH=-1; HARD 시 EMERGING 30% cut), 분할매수 default 30/30/40 표준(execution_plan.DEFAULT_TRANCHE_WEIGHTS + POST tranche 시 weightPct 영속화) |

### 11.2 아직 미구현인 핵심 항목

| 항목 | 이유 |
|---|---|
| 외국인 수급 | KRX Open API 인증키 필요 |

### 11.3 전략 반영률 요약

| 분류 | 상태 |
|---|---|
| 5개 영상 핵심 전략 | 거의 전부 반영 |
| 노션 링크 기반 데이터 | 주요 핵심 소스 대부분 반영 |
| 미세 조정 | 지속 가능 |
| 핵심 누락 | 외국인 수급 1개 |

## 12. Observability 스택 (2026-04 추가)

Docker 로그가 container 재시작 시 휘발돼 5분 스냅샷·KST 07:00 append 사이클의 진단이
어려웠던 문제를 해결하기 위해 OpenTelemetry + Jaeger 기반 분산 추적을 도입.

### 12.1 구성

| 레이어 | 구성 요소 | 역할 |
|---|---|---|
| 계측 | `@opentelemetry/sdk-node` + `auto-instrumentations-node` | axios / http / express 자동 span |
| 수동 span | `server/src/observability/trace.ts` `withSpan` | 핵심 경로(수집/엔진/히스토리)에 span 부여 |
| OTLP 전송 | `@opentelemetry/exporter-trace-otlp-http` | `http://jaeger:4318/v1/traces` |
| 수집/저장 | Jaeger all-in-one 1.63 + Badger | `./jaeger-data` 바인드 마운트로 홈서버 재시작해도 영속 |
| UI | Jaeger UI | `http://192.168.0.200:16686` (service = `macrosquare-server`) |
| 로그 | docker-compose json-file driver | max-size 10m × max-file 5 로 휘발 완화 |

### 12.2 수동 span 네이밍 컨벤션

`macrosquare.<module>.<operation>` 형식.

- `macrosquare.snapshot.build` — buildSnapshot 전체
- `macrosquare.collector.collectAll` — 4개 수집기 병렬 집계 루트
- `macrosquare.collector.{fred,yahoo,cnn,sentiment,smartMoney,calendar}.*`
- `macrosquare.engine.{derived,regime,signals,allocation,executionPlan}`
- `macrosquare.history.refreshComputed` — 히스토리 재계산 루트

부모 span 에는 실패한 소스(`collector.failed_sources`), regime 라벨/점수,
히스토리 재계산 커버리지 등 진단 attribute 를 부여.

### 12.3 안전장치

- telemetry 초기화 실패는 애플리케이션 로직을 중단시키지 않음 (try/catch + NoOp tracer).
- `OTEL_EXPORTER_OTLP_ENDPOINT` 미설정 시 `http://localhost:4318` 기본.
- SIGTERM/SIGINT 에서 SDK graceful shutdown.

### 12.4 남은 TODO

- client(Next 16) 측 브라우저 RUM 계측 여부는 추후 판단 (우선순위 낮음, 서버 사이클 진단이 더 시급했음).
- metrics / logs signal 확장(OTel Collector 분리, Loki 연동) — 현 단계는 traces only.

## 13. 성능 최적화 (2026-04 추가)

### 13.1 FRED probe 빈도/타임아웃 조정

로그 관측 결과 collector cycle 이 간헐적으로 17~22s 로 지연되는 원인이
FRED verification probe 의 10s axios timeout 과 5분 간격 과잉 호출이었음.

- `FRED_VERIFY_INTERVAL_MS`: 5min → **15min** (FRED 자체 일간 갱신이라 5분 probe 과잉)
- `fetchSeriesLatest` timeout 인자화 (기본 10s / probe 시 5s fail-fast)
- `probeFredSource`: retry 0 + timeout 5s

### 13.2 `/api/backtest/portfolio` 3계층 캐싱

년 버튼(1Y/3Y/5Y) 전환마다 서버가 N × 252일 일별 루프 (recomputeFullDerivedForDate
+ classifyRegime + computeSignals + computeAllocation) 를 반복 계산해 1~5s
체감 지연이 발생.

1. **서버 메모리 TTL 캐시**: `portfolioCache: Map<years, {value, at}>`, TTL 6h.
   KST 07:00 append 이후 자연 expire. 동일 파라미터 재호출 0ms 응답.
2. **History 읽기 병렬화**: 16개 `readHistory` 순차 await → `Promise.all`.
   첫 비캐시 호출의 I/O 절반 이하로 단축.
3. **클라이언트 prefetch + map 캐시**: `BacktestPanel` mount 시 1/3/5Y 동시
   요청, `portfolios: Record<years, PortfolioResult>` 로 보관. 버튼은 캐시
   swap 이라 전환 체감 즉시. 버튼 상태 (캐시됨/prefetch 중/로딩) 시각 힌트.

결과: 첫 페이지 방문 시 3개 년 병렬 prefetch → 이후 전환 0ms.
