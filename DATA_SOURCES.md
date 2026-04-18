# DATA SOURCES

MacroSquare가 현재 실제로 사용하는 데이터 소스 명세서.

---

## 1. 개요

시스템은 아래 3단계로 동작한다.

1. **원시 데이터 수집**
2. **파생 지표 계산**
3. **국면/신호/비중/백테스트/알림 생성**

---

## 2. 원시 데이터 소스

| 소스 | 경로/방법 | 인증 | 주기 | 용도 |
|---|---|---|---|---|
| FRED API | `api.stlouisfed.org` | `FRED_API_KEY` | 5분 스냅샷 + 일일 append | 금리, 유동성, 고용, 스트레스 |
| Yahoo Finance v8 chart API | `query1.finance.yahoo.com/v8/finance/chart` | 없음 | 5분 스냅샷 + 일일 append | 가격, 환율, 원자재, ETF |
| CNN Fear & Greed | `production.dataviz.cnn.io` | 없음 | 5분 스냅샷 | 공포탐욕지수 |
| Alternative.me F&G | `api.alternative.me/fng` | 없음 | CNN 실패 시 fallback | 공포탐욕지수 |
| GPR Index | Matteo Iacoviello xls 다운로드 | 없음 | 5분 스냅샷 시 자동 계산 | 지정학 리스크 |
| TradingEconomics HTML | `tradingeconomics.com/united-states/business-confidence` | 없음 | 5분 스냅샷 시 자동 계산 | ISM 제조업 PMI 원본 스크랩 |
| OpenInsider | HTML 스크랩 | 없음 | 5분 스냅샷 시 자동 계산 | 내부자 매수/매도 비율 |
| Dataroma | HTML 스크랩 | 없음 | 5분 스냅샷 시 자동 계산 | 슈퍼투자자 매수/축소 활동 |
| Yahoo Earnings Calendar | HTML 스크랩 | 없음 | 요청 시 | 실적 발표 일정 |
| **SEC EDGAR 13F-HR** | `data.sec.gov/submissions/CIK{id}.json` + `www.sec.gov/Archives/edgar/data/...` | User-Agent 필요 | 7일 캐시 / 30일 stale | 헤지펀드 10곳 분기 포지션 (12차) |
| **Naver Finance 외국인 수급** | HTML 스크랩 | 없음 | 5분 스냅샷 시 | KOSPI/KOSDAQ 20일 외국인 순매수 누적 |
| **CBOE delayed quotes** | `cdn.cboe.com/api/global/delayed_quotes/options` | 없음 | 5분 스냅샷 | P/C Ratio 자체 집계 (SPX+SPY+QQQ) |
| **AAII Substack feed** | `insights.aaii.com/feed` | 없음 | 21일 stale | Bull/Bear Spread |
| **NAAIM** | `naaim.org` | 없음 | 21일 stale | Exposure Index |

---

## 3. FRED 수집 시리즈

### 3.1 핵심 거시/금리

| 코드 | 이름 | 빈도 | 용도 |
|---|---|---|---|
| `DGS10` | 미국 10년물 금리 | 일간 | 장기금리, 실질금리 계산 |
| `T10YIE` | 10년 기대인플레이션 | 일간 | 실질금리 계산 |
| `T10Y2Y` | 장단기 금리차 | 일간 | 경기침체 선행 |
| `EFFR` | Effective Federal Funds Rate | 일간 | 정책 방향 자동화 |
| `SOFR` | SOFR | 일간 | 자금시장 상태 |
| `VIXCLS` | VIX | 일간 | 공포지수 |
| `BAMLH0A0HYM2` | 하이일드 스프레드 | 일간 | 신용 스트레스 |
| `STLFSI4` | 금융스트레스지수 | 주간 | 시스템 스트레스 |

### 3.2 고용/매크로

| 코드 | 이름 | 빈도 | 용도 |
|---|---|---|---|
| `ICSA` | 신규 실업수당 청구건수 | 주간 | 경기체력 / 공포구간 구분 |
| `UNRATE` | 실업률 | 월간 | 고용건강도 |
| `INDPRO` | 산업생산지수 | 월간 | ISM 프록시 |
| `M2SL` | 미국 M2 | 월간 | 미국 유동성 |
| `M3_EURO` | 유로 M3 성장률 | 월간 | 글로벌 유동성 프록시 |
| `M3_JAPAN` | 일본 M3 성장률 | 월간 | 글로벌 유동성 프록시 |

### 3.3 유동성

| 코드 | 이름 | 빈도 | 용도 |
|---|---|---|---|
| `WALCL` | 연준 총자산 | 주간 | QE/QT 방향 |
| `WRESBAL` | 지급준비금 | 주간 | 은행 유동성 체력 |
| `RRPONTSYD` | 역레포 | 일간 | 초과유동성 흡수 |
| `WTREGEN` | TGA | 주간 | 재무부 유동성 흡수/공급 |
| `WRMFNS` | MMF 잔액 | 주간 | 대기 자금 / 위험자산 이동 |

---

## 4. Yahoo Finance 수집 종목

### 4.1 지수/자산

| 심볼 | 용도 |
|---|---|
| `^GSPC` | S&P 500 |
| `^IXIC` | 나스닥 |
| `^KS11` | 코스피 |
| `^KQ11` | 코스닥 |
| `GC=F` | 금 |
| `SI=F` | 은 |
| `HG=F` | 구리 |
| `CL=F` | WTI |
| `DX-Y.NYB` | DXY |
| `KRW=X` | 원/달러 |
| `JPY=X` | 달러/엔 |

### 4.2 국가/지역 ETF

| 심볼 | 용도 |
|---|---|
| `EWZ` | 브라질 ETF |
| `INDA` | 인도 ETF |
| `VNM` | 베트남 ETF |
| `EWJ` | 일본 ETF |

### 4.3 섹터 ETF

| 심볼 | 섹터 |
|---|---|
| `XLK` | 기술 |
| `XLF` | 금융 |
| `XLE` | 에너지 |
| `XLV` | 헬스케어 |
| `XLI` | 산업재 |
| `XLY` | 임의소비재 |

---

## 5. 자동화 입력 소스

### 5.1 지정학 리스크
- **소스**: GPR Index (Caldara & Iacoviello)
- **형태**: xls 다운로드
- **계산**: 최근 30일 평균 → `geoRisk 0~4`

### 5.2 정책 방향
- **소스**: FRED `EFFR`, `T10Y2Y`
- **계산**: 최근/과거 금리 평균 비교 → `policyDirection -2~+2`

### 5.3 중앙은행 금 매수 프록시
- **소스**: 금 가격 + DXY 히스토리
- **계산**: “달러 강세/보합인데 금이 오르는가?”

### 5.4 ISM 제조업 PMI
- **1순위**: TradingEconomics 원문 scrape
- **2순위**: `INDPRO` 기반 프록시

---

## 6. 스마트머니/이벤트 데이터

### 6.1 내부자 거래
- **소스**: OpenInsider
- **지표**:
  - 최근 7일 매수 건수
  - 최근 7일 매도 건수
  - 내부자 매수 비율
  - `score -2~+2`

### 6.2 슈퍼투자자 활동
- **소스**: Dataroma `allact.php?typ=a`
- **지표**:
  - Buy/Add count
  - Sell/Reduce count
  - `dataromaScore -2~+2`

### 6.3 실적 발표 일정
- **소스**: Yahoo Finance earnings calendar
- **범위**: 향후 5일 주요 종목

---

## 7. 파생 지표 범주

### 7.1 금/원자재
- `REAL_YIELD`
- `REAL_YIELD_TREND`
- `DXY_TREND`
- `DXY_TREND_LONG`
- `GOLD_SMA200`
- `GOLD_DISPARITY`
- `GOLD_FIB_382/500/618`
- `GOLD_FIB_ZONE`
- `GOLD_SILVER_RATIO`
- `COPPER_GOLD_RATIO`

### 7.2 주식/코스피
- `NASDAQ_SMA200`
- `NASDAQ_DISPARITY`
- `NASDAQ_DRAWDOWN`
- `NASDAQ_CROSS`
- `NASDAQ_W_BOTTOM`
- `KOSPI_SMA200`
- `KOSPI_DISPARITY`
- `KOSPI_DRAWDOWN`
- `KOSPI_TREND_RECOVERY`
- `KOSPI_VOLUME_CONFIRM`
- `KOSPI_YEAR_RETURN`
- `KOSPI_OVERHEATED`

### 7.3 유동성/매크로
- `RRP_DIRECTION`
- `TGA_DIRECTION`
- `MMF_DIRECTION`
- `GLOBAL_M2_PROXY`
- `SOFR_EFFR_SPREAD`
- `KRW_FX_LEVEL`

### 7.4 섹터/추격 경고
- `SECTOR_XLK/XLF/XLE/XLV/XLI/XLY`
- `SECTOR_STRONGEST`
- `CHASE_NASDAQ`
- `CHASE_GOLD`
- `CHASE_KOSPI`
- `CHASE_COPPER`
- `OVERHEATED`

---

## 8. 수집/저장 주기

| 주기 | 작업 |
|---|---|
| 5분 | 최신 스냅샷 갱신 |
| 매일 KST 07:00 | 히스토리 append + 신호 히스토리 재계산 |
| 서버 시작 시 | 백필 보장 |

### 히스토리 보장 범위
- **FRED**: 10년
- **Yahoo**: 5년
- **공통 보장**: 최근 5년

---

## 9. 12차 신규 임계값 (2026-04)

### 영상/노션 근거 수치
| 임계 | 값 | 출처 | 용도 |
|---|---|---|---|
| USDKRW 그린 게이트 | ≤ 1480 | stt_kospi | 외국인 복귀 신호 |
| USDKRW 레드 게이트 | ≥ 1500 | stt_kospi | 외국인 이탈 임계 |
| WTI 유가 안정 | < 65 | video5_analysis | KOSPI signal "60달러대 안정" 정합 |
| 외국인 20일 역사적 | ±20조 (±200,000억) | stt_kospi | "2025년 2~3월 45~60조" 규모 감지 |
| NASDAQ 극저점 | DISPARITY ≤ -25% | video1 §전략C | +2 가점 "평균회귀 최강" |
| KOSPI 극저점 | DISPARITY ≤ -25% | stt_kospi 역대급 | +2 가점 |
| CAUTION cash | ≥ 30% | video5_analysis §3.3 | "숨고르기 30-40%" envelope |
| silver (경기 불안 6레짐) | ≤ 5% | video2 "금융위기 -50%" | CAUTION/CORRECTION/PANIC/RECESSION/STAGFLATION/BOND_VIGILANTE |
| gold 구조헷지 (4레짐) | ≥ 5% | video2 중앙은행 매수 | RISK_ON/NEUTRAL/CAUTION/CORRECTION |
| emerging FX 악화 (3레짐) | ≤ 10% | stt_kospi FX 이탈 | CORRECTION/PANIC/RECESSION |

### 노션 대시보드 정합
| 임계 | 값 | 용도 |
|---|---|---|
| WRESBAL 안전 | ≥ 3조 달러 | WRESBAL_ABSOLUTE_LEVEL |
| RRP 완화 | ≥ 100B | RRP_ABSOLUTE_LEVEL=+1 |
| RRP 바닥 | < 50B | RRP_ABSOLUTE_LEVEL=-1 |
| F&G 5단계 | 0-24/25-44/45-55/56-74/75-100 | FNG_TIER (-2~+2) |
| HY Spread signal 경고 | 5-7% 주의 | NASDAQ 과열 플래그 |

### 13F 집단 FLOW
- 메가캡 7종목 (AAPL/MSFT/GOOGL/AMZN/NVDA/META/TSLA)
- TECH 5종 / FIN 5종 / ENERGY 5종 CUSIP 하드코딩
- 분기 Δ 레벨: ±2 (>±2%p) / ±1 (>±0.5%p) / 0

---

## 10. 남은 핵심 데이터 소스

현재 시스템에서 **핵심 미구현 외부 데이터 소스**:

- **~~KRX Open API~~** — 외국인/기관/개인 수급 → ✅ **Naver Finance 스크랩으로 대체 구현 완료**
- **WGC (World Gold Council)** — 중앙은행 금 매수 공식 데이터 (현재는 video2 환경 proxy 로 대체)
- **뉴스/연준 NLP** — 베센트/워시 발언 sentiment (수동 `policyDirection` 입력으로 대체)
- **애널리스트 컨센서스 vs 13F divergence** — Phase 4 설계 완료 (`docs/TODO-institutional-policy-integration.md`)
