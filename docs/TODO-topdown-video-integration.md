# TODO: Top-down 투자 영상 반영 (거시 → 섹터 → 자산/기업)

## 배경

분석 대상 영상:
- `투자는 딱 2가지만 하면 성공합니다.mp4`

핵심 메시지:
1. 투자 의사결정은 크게 **무엇을 살까** / **언제 살까** 두 가지다.
2. 무엇을 살지는 **탑다운(거시 → 섹터 → 기업/자산)** 으로 좁혀가는 것이 개인 투자자에게 더 현실적이다.
3. 진입 시점은 방향 판단과 별개로 **차트 + 수급** 으로 확인해야 한다.
4. 좋은 기업은 단순 저평가 기업이 아니라, **돈이 몰릴 수밖에 없는 구조**를 가진 기업이다.

현재 MacroSquare 는 이미 거시/유동성/레짐/자산배분 엔진이 강하므로, 이번 반영의 목적은 아래와 같다.

- **거시 → 섹터 → 자산** 체인을 시스템에 더 명시적으로 드러내기
- 섹터를 **사이클 수혜 / 구조 수혜 / 방어형**으로 분리하기
- 자산 신호의 explanation 을 계층형으로 강화하기
- execution plan 에 **수급/차트 기반 타이밍 확인**을 더 명확히 넣기
- 바텀업 심화(기업 재무/병목/내러티브 판별)는 장기 TODO 로 남기기

---

## 범위 요약

### 이번 구현 범위
- 탑다운 설명 레이어
- 섹터 분류 레이어
- 자산 explanation 강화
- execution timing 보강
- UI 노출 강화

### 이번에 보류하는 범위
- 개별 기업 재무제표 기반 바텀업 분석
- 병목 기업 자동 선별
- 내러티브 초기/중반/과열 완전 자동 판별

---

# Phase 1. 거시 → 섹터 → 자산 체인 가시화

## 1-1. 타입 확장
### 파일
- `server/src/types/indicators.ts`

### TODO
- `SystemSnapshot.meta` 아래에 `topdown` 구조 추가
- 아래 타입 추가 검토:
  - `SectorTheme`
  - `SectorClassification`
  - `TopDownView`
  - `TopDownAssetRationale`

### 반영 목표
- API 응답에서 사용자가 “지금 거시 환경에서 왜 이 자산/섹터를 보는지”를 바로 읽을 수 있어야 함

### 완료 기준
- `snapshot.meta.topdown` 타입이 정의됨

---

## 1-2. 탑다운 요약 생성기 추가
### 파일
- `server/src/services/topdown-view.ts` (신규)

### TODO
- 입력:
  - `raw`
  - `derived`
  - `regime`
  - `signals`
- 출력:
  - `macroView`
  - `favoredSectors`
  - `avoidedSectors`
  - `assetRationale`

### 반영 규칙 예시
- 금리 인하 기대 + 유동성 개선 → 성장/기술 우호
- 달러 약세 → 원자재/신흥국/한국 우호 가능
- 경기 둔화 + 방어 환경 → 헬스케어/유틸리티/금 우위
- 지정학 리스크 확대 → 방산/에너지/금 논리 강화

### 완료 기준
- `buildTopDownView()` 같은 순수 함수로 snapshot 조립 시 재사용 가능

---

## 1-3. snapshot 메타에 탑다운 설명 연결
### 파일
- `server/src/state/cache.ts`

### TODO
- `buildSnapshot()` 내부에서 `topdown-view.ts` 호출
- `meta.topdown` 에 결과 주입

### 완료 기준
- `/api/snapshot` 응답에 `meta.topdown` 포함

---

# Phase 2. 섹터를 사이클 수혜 / 구조 수혜 / 방어형으로 분리

## 2-1. 섹터 분류 규칙 정의
### 파일
- `server/src/engines/sector-classification.ts` (신규)

### TODO
- 섹터를 아래 중 하나로 분류
  - `cyclical`
  - `structural`
  - `defensive`

### 예시 분류
- 사이클형: `XLI`, `XLF`, `XLY`, `XLB`, 일부 `XLE`
- 구조형: `XLK`, `SOXX`, `SMH`, AI 인프라 관련
- 방어형: `XLV`, `XLU`, 추후 `XLP` 추가 가능

### 완료 기준
- 각 섹터 ETF가 분류값을 가짐

---

## 2-2. 섹터 프록시 데이터 보강 점검
### 파일
- `server/src/collectors/yahoo.ts`

### TODO
- 현재 수집 중인 섹터 ETF 점검
- 필요 시 추가 검토:
  - `XLU`
  - `XLP`
  - `XLRE`
  - 방산 ETF (`ITA` 등)
  - 전력/인프라 proxy ETF

### 완료 기준
- 영상 논리를 표현하기 위한 최소 섹터 프록시 확보

---

## 2-3. 섹터 구조 점수 derived 도입 검토
### 파일
- `server/src/engines/derived.ts`

### TODO
- 기존 `SECTOR_*` 수익률 외에 설명용 파생지표 검토
  - `SECTOR_POLICY_SUPPORT_*`
  - `SECTOR_STRUCTURAL_DEMAND_*`
  - `SECTOR_SUPPLY_TIGHTNESS_*`
- 완전 자동화보다 규칙 기반/정책 기반 해석으로 시작

### 완료 기준
- 섹터별 “왜 우호적인가”를 숫자/설명으로 남길 수 있음

---

# Phase 3. 자산 신호 explanation 계층화

## 3-1. signal explanation 구조 확장
### 파일
- `server/src/types/indicators.ts`

### TODO
기존 explanation:
- `baseSignal`
- `finalSignal`
- `overrides`

확장 후보:
- `macroReasons`
- `sectorReasons`
- `assetReasons`
- `flowReasons`
- `timingNotes`

### 완료 기준
- 각 자산 신호에 대해 최소 3계층 이상의 근거 설명 가능

---

## 3-2. NASDAQ / KOSPI / GOLD explanation 강화
### 파일
- `server/src/engines/signals.ts`

### TODO
자산별로 이유를 계층 분리해 기록

#### NASDAQ 예시
- macro: 유동성/금리 인하 기대
- sector: 기술/반도체 상대강도
- asset: 200DMA, RSI, W-bottom
- flow: 13F, ETF, 심리, 기관 흐름
- timing: 과열/추격 경고

#### KOSPI 예시
- macro: 달러/원화/외인 환경
- sector: 반도체/수출주 회복
- asset: 추세 회복 여부
- flow: 외국인 순매수
- timing: 환율 경고/과열 경고

#### GOLD 예시
- macro: 실질금리/달러/지정학
- sector/theme: 비달러 안전자산
- flow: 중앙은행 금 매수
- timing: 과열 여부

### 완료 기준
- UI 에서 사람이 읽을 수 있는 explanation 생성

---

# Phase 4. “좋은 섹터” 조건 반영

영상의 섹터 조건:
1. 정책이 밀어주는가
2. 구조적 수요가 느는가
3. 공급이 빠르게 늘기 어려운가
4. 과점 구조인가

## 4-1. sector quality score 추가
### 파일
- `server/src/services/sector-quality.ts` (신규)

### TODO
섹터별 품질 점수 산출:
- `policySupport`
- `structuralDemand`
- `supplyTightness`
- `marketConcentration`
- `totalScore`

### 초기 구현 원칙
- 외부 정교한 산업 DB 없이 시작
- 현재 레짐/정책/지정학/유동성 + 사전 룰 기반

### 완료 기준
- favored sector 선정 시 quality score 반영 가능

---

## 4-2. snapshot 에 sector quality 노출
### 파일
- `server/src/state/cache.ts`

### TODO
- `meta.topdown.favoredSectors` 에 점수와 이유 포함

### 완료 기준
- 프론트가 그대로 렌더링 가능한 형태로 API 제공

---

# Phase 5. “병목 / 대체불가 / 구조 자금”을 자산 프록시 수준으로 반영

개별 기업 자동 선별은 보류하되, 현재 프로젝트 성격상 먼저 ETF/자산 프록시로 번역한다.

## 5-1. 자산/ETF 내러티브 맵 정의
### 파일
- `server/src/services/asset-theme-map.ts` (신규)

### TODO
자산/ETF 에 내러티브 태그 부여
- `NASDAQ` → AI/성장/기술
- `SOXX` / `SMH` → 반도체 병목/AI 인프라
- `GOLD` → 비달러 안전자산
- `KOSPI` → 반도체/수출/외인/환율 민감
- `COPPER` → 경기회복/산업금속
- `EMERGING` → 달러 약세/자금 유입 수혜

### 완료 기준
- explanation 생성 시 자산별 핵심 서사를 참조 가능

---

## 5-2. smart money / 13F / 수급 설명 연결
### 파일
- `server/src/collectors/institutional-13f.ts`
- `server/src/collectors/smart-money.ts`
- `server/src/engines/signals.ts`

### TODO
- 구조적 자금 흐름 설명에 아래 반영
  - ETF 자금 흐름
  - 13F 변화
  - 스마트머니/인사이더 흐름
  - 한국 주식의 외국인/기관 순매수

### 완료 기준
- “좋은 자산”이 아니라 “지금 자금이 붙는 자산” 설명 가능

---

# Phase 6. 실행 타이밍(언제 살까) 보강

영상 핵심:
- 방향은 탑다운
- 진입은 차트와 수급

## 6-1. execution plan 에 timing confirmation 추가
### 파일
- `server/src/engines/execution_plan.ts`

### TODO
아래 확인 항목 추가 검토
- `macroAligned`
- `sectorAligned`
- `flowConfirmed`
- `chartConfirmed`
- `overheatingRisk`

### 예시 해석
- `BUY_NOW`: 방향 일치 + 수급 확인 + 과열 아님
- `HOLD`: 방향은 맞으나 수급 미확인
- `WAIT/REDUCE`: 방향은 맞지만 과열 또는 추격 구간

### 완료 기준
- 실행 플랜에 “왜 지금 / 왜 대기”가 명시됨

---

## 6-2. tranche 기록에 진입 당시 맥락 저장
### 파일
- `server/src/routes/api.ts`
- `server/src/services/trancheStore.ts`
- `server/src/engines/execution_plan.ts`

### TODO
트랑셰 저장 시 아래 맥락 추가 검토
- sector alignment
- flow confirmation
- overheating status
- macro regime snapshot

### 완료 기준
- 사후 회고 시 “방향은 맞았는데 너무 빨랐는지” 판단 가능

---

# Phase 7. 프론트 UI 반영

## 7-1. LensPanel / MetaBar 에 탑다운 요약 표시
### 파일
- `client/src/components/LensPanel.tsx`
- `client/src/components/MetaBar.tsx`

### TODO
표시 항목
- 현재 거시 요약
- 선호 섹터
- 회피 섹터
- 핵심 자산 논리

### 완료 기준
- 첫 화면에서 “지금 어디를 봐야 하는지” 이해 가능

---

## 7-2. SectorPanel 강화
### 파일
- `client/src/components/SectorPanel.tsx`

### TODO
섹터별 표시 강화
- 사이클형 / 구조형 / 방어형 분류
- 현재 점수
- 우호 이유
- 주의 이유

### 완료 기준
- 단순 수익률 패널이 아니라 “섹터 선택 패널” 역할 수행

---

## 7-3. SignalPanel 설명 강화
### 파일
- `client/src/components/SignalPanel.tsx`

### TODO
각 자산 카드에 다음 요약 추가
- macro
- sector
- flow
- timing

### 완료 기준
- `BUY / HOLD / REDUCE` 의 논리가 UI 상에서 투명하게 보임

---

## 7-4. ExecutionPlanPanel 강화
### 파일
- `client/src/components/ExecutionPlanPanel.tsx`

### TODO
추가 표시
- 거시 일치 여부
- 섹터 일치 여부
- 수급 확인 여부
- 차트 확인 여부
- 과열 경고 여부

### 완료 기준
- 왜 지금 1차 진입인지 / 왜 대기인지 직관적으로 이해 가능

---

# Phase 8. 테스트 TODO

## 8-1. 탑다운/섹터 분류 테스트
### 파일
- `server/src/__tests__/topdown-view.test.ts` (신규)
- `server/src/__tests__/sector-classification.test.ts` (신규)
- `server/src/__tests__/sector-quality.test.ts` (신규)

### TODO
검증 포인트
- 금리/달러/유동성 조합별 favored sector 변동
- 구조형/사이클형 분류 정상 여부
- explanation 필드 누락 없는지

---

## 8-2. signal / execution regression
### 파일
- `server/src/__tests__/signals.test.ts`
- `server/src/__tests__/execution-plan.test.ts`
- `server/src/__tests__/allocation.test.ts`

### TODO
검증 포인트
- 기존 시그널/비중 정책이 깨지지 않는지
- explanation 강화 후 결과 일관성 유지
- execution plan 이 과도하게 보수화되지 않는지

---

# Phase 9. 구현 우선순위 추천

## 1차 (MVP)
### 서버
- `server/src/types/indicators.ts`
- `server/src/services/topdown-view.ts`
- `server/src/state/cache.ts`
- `server/src/engines/signals.ts`

### 프론트
- `client/src/components/SectorPanel.tsx`
- `client/src/components/SignalPanel.tsx`
- `client/src/components/ExecutionPlanPanel.tsx`

### 기대 효과
- “거시 → 섹터 → 자산” 체인이 사용자에게 보이기 시작함

---

## 2차
- `server/src/engines/sector-classification.ts`
- `server/src/services/sector-quality.ts`
- `server/src/engines/execution_plan.ts`
- 필요 시 `server/src/collectors/yahoo.ts` 보강

### 기대 효과
- 섹터 분류와 질 평가가 API 에 반영됨

---

## 3차
- 구조 자금/내러티브/병목 프록시 고도화
- ETF/기관 자금 흐름 결합 강화
- timing confirmation 정교화

### 기대 효과
- explanation 이 더 설득력 있고 반복 가능해짐

---

# 장기 TODO / 이번 범위 보류 항목

## LT-1. 개별 기업 바텀업 분석 모듈
### 상태
- **Long-term TODO**

### 보류 이유
- 현재 프로젝트는 자산배분 엔진에 가깝고, 개별 기업 재무 분석 도구가 아님
- 아래 항목은 현재 범위를 넘어섬
  - 재무제표 정밀 해석
  - 내재가치 평가
  - 경영진/IR 해석
  - 경쟁사 비교 모델링

### 선행 조건
- 종목 유니버스 정의
- 기업 데이터 소스 확보
- 자산배분 엔진과 기업 리서치 레이어의 연결 방식 설계

---

## LT-2. 병목 기업 자동 판별 프레임워크
### 상태
- **Research TODO / Mid-to-Long-term**

### 보류 이유
- 병목 판단에는 아래 데이터가 필요
  - 산업 공급망 데이터
  - 점유율 데이터
  - 경쟁 구조 데이터
- 현재 엔진은 이 수준의 산업구조 DB 를 갖고 있지 않음

### 현실적 중간 단계
- 당분간 ETF/대표 섹터 프록시 중심 유지
- 필요 시 수동 리서치/문서 보조 방식으로 병목 기업 리스트 관리

---

## LT-3. 내러티브 초기 / 중반 / 과열 자동 판별
### 상태
- **Heuristic TODO / Mid-term**

### 보류 이유
- 정성 요소 비중이 높아 완전 자동화가 어려움
- “모두가 알고 있는가 / 아직 반영이 덜 되었는가”를 직접 측정하기 어려움

### 현실적 중간 단계
완전 자동 대신 아래 프록시로 힌트 모델부터 검토
- 가격 이격
- 거래량 급증
- 뉴스 빈도
- ETF 유입
- 기관/외국인 순매수
- 검색 트렌드(추후)

### 목표
- 내러티브 단계 완전 판별이 아니라, 과열/초기 가능성에 대한 **보조 신호** 제공

---

# 최종 정리

이번 영상 반영의 본체는 아래 한 문장으로 요약된다.

> **거시로 방향을 잡고, 섹터로 압축하고, 자산/기업은 그 흐름 위에서 선택하며, 진입 시점은 차트와 수급으로 확인한다.**

MacroSquare 는 이미 거시/유동성/레짐 엔진이 강하므로, 이번 TODO 의 핵심은:
- 그 강점을 더 사용자에게 잘 보이게 만들고
- 섹터 레이어를 강화하고
- explanation 과 execution timing 을 더 정교하게 만드는 것
이다.

장기적으로는 바텀업/병목/내러티브 판별까지 갈 수 있으나, 현재 단계에서는 **탑다운 본체를 먼저 강화하는 것이 우선순위**다.
