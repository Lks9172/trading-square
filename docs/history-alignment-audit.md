# History Cross-Date Alignment 감사 (13차 B6)

MacroSquare 의 시계열 데이터는 **3가지 서로 다른 빈도** 를 섞어 계산한다.
정확한 판단을 위해 **date alignment 전략** 이 명확해야 한다.

---

## 1. 빈도별 데이터 분포

| 빈도 | 출처 | 예시 |
|---|---|---|
| **일간 (daily)** | Yahoo / FRED daily | ^IXIC, DGS10, T10YIE, VIXCLS, BAMLH0A0HYM2, WTI, DXY, USDKRW |
| **주간 (weekly)** | FRED weekly | ICSA, WALCL, WRESBAL, WRMFNS, STLFSI4 |
| **월간 (monthly)** | FRED monthly | UNRATE, INDPRO, M2SL, M3_EURO, M3_JAPAN |
| **분기 (quarterly)** | SEC 13F | institutional positions |
| **이벤트 기반** | AAII Substack / NAAIM / KRX | ~21일 stale tolerance |

---

## 2. 현재 정렬 전략: `latestBefore`

공통 헬퍼 `latestBefore(arr, date)` 는 **주어진 date 이전의 가장 최근 관측치** 반환.
= **Forward-fill 등가**.

### 2.1 기본 동작
```ts
function latestBefore(arr, date) {
  // arr 은 date 오름차순. 역방향 스캔해 첫번째 arr[i].date <= date 반환.
  for (let i = arr.length - 1; i >= 0; i--)
    if (arr[i].date <= date) return arr[i].value;
  return null; // date 이전 관측 없음
}
```

### 2.2 장점
- 단순, 안전 (미래 데이터 누출 없음)
- 주간/월간 데이터를 일간 계산에 쓸 때 자연스럽게 "최신 알려진 값" 사용
- backtest 시 "t 시점에서 얻을 수 있었던 정보" 보장

### 2.3 잠재 위험
| 케이스 | 위험 수준 |
|---|:---:|
| 주간 ICSA 가 목요일 발표, 월요일 판단 시 전주 값 사용 | 🟢 정상 |
| 월간 UNRATE 가 5월 2일 발표, 4월 30일 판단 시 **3월 값** 사용 | 🟢 정상 (과거 정보만 사용) |
| **데이터 결측** (예: FRED 주간 ICSA 발표 스킵된 주) | 🟡 forward-fill 로 여전히 이전 주 값 사용, 이벤트 누락 |
| **서로 다른 캘린더** (NASDAQ 영업일 vs KRX 영업일 vs FRED) | 🟡 휴일 직전 값 사용, 대체로 허용 |
| 장기 시리즈 파일에 **duplicate/out-of-order** 있을 경우 | 🔴 정렬 가정 위반 가능 |

---

## 3. 검증된 사용처

### 3.1 derived.ts
- NASDAQ_SMA50/200, KOSPI_SMA200 등 **일간 내부 계산** — alignment 이슈 없음
- REAL_YIELD = DGS10 - T10YIE (둘 다 FRED daily, same date) — 이슈 없음
- GOLD_SILVER_RATIO, COPPER_GOLD_RATIO — 둘 다 Yahoo daily — 이슈 없음
- **COPPER_STOCK_DIVERGENCE / WTI_COPPER_LAG_LEVEL**:
  - `fetchYahooHistory(A, n)` + `fetchYahooHistory(B, n)` 각자 날짜 배열 획득
  - 인덱스 기준 접근 (`arr[arr.length - N]`) → 두 시리즈의 **동일 인덱스가 동일 영업일 보장되지 않음** ⚠️
  - 휴장일 엇갈림 시 소폭 편차. 대부분 허용 수준이나 **날짜 매칭으로 교체 권장**.
  - **COPPER_GOLD_RATIO_UPTURN/DOWNTURN**: 이미 `goldDateMap` 으로 날짜 join 구현됨 ✓

### 3.2 portfolio-sweep.ts
- `baseSlice[i].date` / `prevDate` 로 매일 iteration
- 모든 raw/derived 재계산 시 `latestBefore(history, prevDate)` 사용 — **일관된 forward-fill** ✓
- Yahoo 시리즈는 baseSlice 기준 날짜로 sliced — misalignment 리스크 낮음

### 3.3 history-store.ts
- 각 시리즈 개별 파일 저장 (source + key 별)
- recompute 시 같은 date 의 raw 를 읽어 derived 재계산
- **cross-date 없음** (date 축 동일)

---

## 4. 발견된 경미 이슈

### 4.1 COPPER_STOCK_DIVERGENCE 인덱스 기반 접근
```ts
const nq0 = nasdaqHist20[nasdaqHist20.length - 21].close;
const cu0 = copperHist20[copperHist20.length - 21].close;
```
- NASDAQ 영업일 ≠ HG=F 선물 영업일 (일부 미국 휴일 엇갈림)
- 21번째 값이 정확히 "20영업일 전" 이 아닐 수 있음
- **영향**: 실제로 ±1~2일 편차, 20일 수익률이라 미미
- **개선안** (선택): date map 기반 join 으로 정확 매칭

### 4.2 WTI_COPPER_LAG_LEVEL 동일 패턴
- wtiHist[length-90], copperHistLag[length-30] 인덱스 접근
- 동일 편차 리스크
- 60일 lag 관찰이라 허용 범위

### 4.3 derived → raw 참조 순서
- PSYCH_SUBSCORE: F&G / P/C / AAII / NAAIM 가중평균
  - 4개 모두 null 일 수 있음 → null 스킵 + 재정규화 (line 1242~1270) ✓
- 결측 케이스 robust

---

## 5. 권장 개선 (Low priority)

| # | 항목 | 현재 | 개선 |
|---|---|---|---|
| 1 | COPPER_STOCK_DIVERGENCE date join | 인덱스 | date map (COPPER_GOLD_RATIO_UPTURN 패턴 재사용) |
| 2 | WTI_COPPER_LAG_LEVEL date join | 인덱스 | 상동 |
| 3 | readHistory 결과 sort 가정 검증 | 암묵 | assertion 추가 (debug only) |
| 4 | 장기 누락 시점 explicit audit | 없음 | 매일 append 로그에 gap 감지 |

---

## 6. 결론

- **현재 alignment 전략은 안전함** (`latestBefore` forward-fill, 미래 정보 누출 없음)
- 2차 감사 이후 추가된 신규 derived (COPPER_STOCK_DIVERGENCE, WTI_COPPER_LAG_LEVEL)
  에서 **인덱스 기반 접근** 이 있으나 영향 미미 (±1~2일)
- 심각한 정렬 버그 없음
- 장기 개선 여지: date map 기반 join 일관화 (선택)

**감사 완료**: 2026-04-18 (13차 B6)
