# MacroSquare

거시경제·유동성·기술적 지표를 종합해 **"지금 어떤 자산을 얼마나 들고 있어야 하는가"**를 판단하는 시스템.

## 현재 구현 상태 (12차 2026-04)

- 시장 데이터 수집: **FRED / Yahoo / CNN Fear & Greed / 대체 F&G / GPR / OpenInsider / Dataroma / Earnings**
- 자동화 입력: **정책 방향 / 지정학 리스크 / 중앙은행 금 매수 proxy / ISM 자동 계산 / KRX 외국인 수급**
- 국면 엔진: **14개 컴포넌트 기반 점수화 + 8개 레짐 분류 (STAGFLATION/BOND_VIGILANTE override)**
- 자산 신호: **나스닥 / 코스피 / 금 / 은 / 구리 / 신흥국 / 현금 / 레버리지**
- 비중 조절: **국면 템플릿 + 신호 배수 + 환율/과열/방어 모드 3단계 보정**
- **기관 13F 추적**: 주요 헤지펀드 10곳 분기 포지션 변화 (NASDAQ + TECH/FIN/ENERGY 섹터)
- 히스토리: **FRED 10년 / Yahoo 5년 영속 저장 + 일일 append**
- 히스토리 차트: **기간/간격 선택 + 드래그 pan + 이중 y축**
- 백테스트: **벤치마크 + 포트폴리오 비중 기반 + Walk-forward OOS**
- 알림: **텔레그램 신호 변경 / 비중 변경 / 전체 현황 요약**

## 12차 파생지표 (영상/노션 정합 10종)

- 영상 정합: GOLD_SEASONAL / CB_GOLD_STRUCTURAL_DEMAND / TAIL_RISK_LEVEL
- 영상 정합: KOSPI_FOREIGN_HISTORIC_EXTREME / COPPER_GOLD_RATIO_UPTURN / COPPER_STOCK_DIVERGENCE
- 노션 정합: FNG_TIER / WRESBAL_ABSOLUTE_LEVEL / RRP_ABSOLUTE_LEVEL
- 13F: INSTITUTIONAL_NASDAQ_{EXPOSURE_PCT, FLOW} / SECTOR_{TECH, FIN, ENERGY}_FLOW

## 장기 로드맵

- **#9 정책 NLP 자동화** (베센트/워시 발언 sentiment)
- **#8 Phase 4 RESEARCH_CONSENSUS_DIVERGENCE** (애널리스트 추천 vs 13F 베팅 괴리)
- regime 점수 컷(75/55/40/25) Monte Carlo 재검증 (TODO FE3)

상세: `docs/TODO-institutional-policy-integration.md`

## 디렉토리 구조

```text
trading-square/
├─ client/      # Next.js 프론트엔드
├─ server/      # TypeScript 서버/엔진/수집기
├─ docker-compose.yml
├─ README.md
├─ CHANGELOG.md
└─ PRD-매크로-포트폴리오-시스템.md
```

## 실행 방법

### 로컬

```bash
# server
cd server
npm install
npm run dev

# client
cd client
npm install
npm run dev
```

### Docker

```bash
docker compose up -d --build
```

## 환경변수

### 루트 `.env`

```env
FRED_API_KEY=...
HOST_IP=...
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...
```

### server `.env`

```env
FRED_API_KEY=...
PORT=5846
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...
```

## 데이터 수집 주기

| 주기 | 내용 |
|---|---|
| 5분 | 최신 스냅샷 갱신 (FRED/Yahoo/CNN/파생지표/국면/신호/비중) |
| 매일 KST 07:00 | 히스토리 append + 신호 히스토리 재계산 |
| 서버 시작 시 | FRED 10년 / Yahoo 5년 백필 보장 |

## 주요 엔드포인트

### 서버 (`:5846`)
- `/api/snapshot`
- `/api/health`
- `/api/history/coverage`
- `/api/history/:source/:key`
- `/api/history-series`
- `/api/backtest/summary`
- `/api/backtest/portfolio?years=1|3|5`
- `/api/smart-money`
- `/api/earnings`

### Sweep (CLI)
```bash
# 분기별 재선정 후보 도출 (top-decile, walk-forward, tx-cost 5bp)
cd server && PORTFOLIO_SWEEP_MODE=decile npx tsx src/scripts/portfolio-sweep.ts 30 --walk-forward --tx-cost=5 --output
# 결과: server/data/sweep-results/top-decile-<timestamp>.json
# BASE 교체 CANDIDATE (활성일 ≥100 AND envelope 위반 0) 만 제시
```

### 프록시 (`:5847`)
- `/api/snapshot`
- `/api/history/*`
- `/api/backtest/*`
- `/api/smart-money`
- `/api/earnings`

## 핵심 전략 반영 요약

- 1억 전략 (video1): 저점 집중 매수 / 레버리지 3조건 + 2~3개월 / 5가지 겹침 플래그
- 원자재 전략 (video2): 금 4순위 / 금은비 이중 게이트 / 구리 ISM+상대강도 / 금구리비 하락 전환 / 금의 계절성
- 이동평균선 전략 (video3): 200DMA / 골든·데드크로스 역발상 / 분할매수 3단계 / W자 반등
- 저점 7기준 (video4): 정책 / 지정학 / 유동성 / 매크로 / 모멘텀 / 기관 (13F 10곳) / 차트
- 코스피 전략 (stt_kospi): 환율 1480/1500 이중 게이트 / 유가 65 임계 / 거래량 / 추세 회복 / 외국인 ±20조 역사적 / 지정학 숏커버 가드

## 배포

현재 홈서버 Docker 배포 중:

- 클라이언트: `http://115.21.105.43:5847`
- 서버 API: `http://115.21.105.43:5846`
