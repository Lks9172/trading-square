# 스케줄러·동시성·멱등성 계약

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-17**
- Scheduler lock 물리 동시성 상한: **4**

## 1. 운영 스케줄

| 작업 | 시작 지연 | 운영 주기 | 소유 scheduler/task |
|---|---:|---:|---|
| Market Yahoo | 45초 | 5분 | market observation |
| FRED | 45초 | 6시간 | market observation |
| Fear & Greed | 45초 | 1시간 | market observation |
| Sentiment | 45초 | 6시간 | market observation |
| Stablecoin | 45초 | 6시간 | market observation |
| KRX flow | 45초 | 30분 | market observation |
| Sector total return | 45초 | 6시간 | sector total return |
| Sector ETF flow·가격 breadth | 90초 | 6시간 | research sector market evidence |
| Market snapshot | 75초 | 5분 | market snapshot |
| Company summary 277개 | 3분 | 30분 | company research summary |
| Analyst history | 15분 | 매시간 15분, 매일 | company analyst history |
| 13F | 30초 | 24시간 | institutional; 첫 실행은 최근 2시간 영속 수집 재사용 가능 |
| Policy | 40초 | 6시간 | policy |
| Narrative | 90초 | 6시간 | narrative |
| Peer taxonomy | 2분 | 6시간 | peer taxonomy |
| DART | 3분 | 6시간 | disclosure, 키가 있을 때 |
| Data integrity | 30초 | 1분 | integrity |
| Telegram market check | 2분 | 5분 | notification |
| Telegram company scan | - | 평일 매시간 20분 | notification |
| Telegram weekend scan | - | 4시간마다 20분 | notification |
| Telegram outbox dispatch | 10초 | 15초 | notification |
| Telegram weekly report | - | 월요일 08:00 KST | notification |
| Outbox maintenance | - | 매일 03:35 KST | notification |
| Host recurrence monitor | - | 1분 | cron+flock |
| Host full audit | - | 매일 07:20 KST | cron+flock |

`fixedDelay`는 이전 작업 완료 후 지연이다. 긴 수집이 발생해도 같은 JVM에서 즉시 겹치지 않는다.

startup Telegram은 5초에 영속 snapshot으로 발송하고, provider 집약형 후보 전수 재계산은 20분 뒤 시작한다.
company summary → analyst history → 후보 전수 재계산의 startup 순서를 지켜 readiness 직후 Yahoo·CPU
stampede를 만들지 않는다. 예상보다 긴 작업도 세 경로가 공통 `company:provider-heavy` advisory slot을 사용해
실제 provider 호출을 직렬화한다. startup 후보 스캔은 slot이 바쁘면 5분 간격 최대 6회 재시도하고, 정규
cron/fixed-delay 주기는 그대로 유지한다.

### 자동 대조용 운영 계약

아래 값은 `application.yml` 기본값과 production compose override 중 실제 운영값을 명시한다.
`scripts/verify-documentation.py`가 코드/config와 대조하므로 주기를 바꾸면 같은 변경에서 표와 이 블록을
함께 갱신한다.

```text
FRED_COLLECTION_FIXED_DELAY=6h
YAHOO_COLLECTION_FIXED_DELAY=5m
SECTOR_TOTAL_RETURN_COLLECTION_FIXED_DELAY=6h
SECTOR_MARKET_EVIDENCE_FIXED_DELAY=6h
FEAR_GREED_COLLECTION_FIXED_DELAY=1h
SENTIMENT_COLLECTION_FIXED_DELAY=6h
STABLECOIN_COLLECTION_FIXED_DELAY=6h
KRX_COLLECTION_FIXED_DELAY=30m
MARKET_SNAPSHOT_FIXED_DELAY=5m
COMPANY_RESEARCH_SUMMARY_FIXED_DELAY=30m
COMPANY_RESEARCH_SUMMARY_STARTUP_DELAY=3m
COMPANY_RESEARCH_SUMMARY_CONCURRENCY=4
COMPANY_ANALYST_HISTORY_STARTUP_DELAY=15m
SEC_13F_FIXED_DELAY=24h
SEC_13F_STARTUP_FRESHNESS=2h
POLICY_FIXED_DELAY=6h
PEER_DISCOVERY_FIXED_DELAY=6h
NARRATIVE_SOURCE_FIXED_DELAY=6h
DART_FIXED_DELAY=6h
DATA_INTEGRITY_MONITOR_FIXED_DELAY=1m
TELEGRAM_OUTBOX_DISPATCH_DELAY=15s
TELEGRAM_POST_STARTUP_RECALCULATION_DELAY=20m
```

## 2. 중복 실행 방지

모든 부수효과 scheduler는 두 겹의 보호를 사용한다.

1. 프로세스 내부 non-overlap guard
2. PostgreSQL session advisory lock

rolling deploy로 구·신 container가 잠시 겹치거나 replica가 늘어도 같은 task key의 실행권은 한 인스턴스만
획득한다. DB lock 획득 실패를 “로컬에서 그냥 실행”으로 우회하지 않는다.

## 3. DB pool 격리

- API/transaction: bounded Hikari pool, 최대 8
- 장시간 scheduler lock: 별도 unpooled connection source
- coordination connection: fair semaphore 최대 4

외부 SEC/Yahoo 지연이 API transaction connection을 고갈시키지 않게 한다. scheduler 수를 늘리면 task
thread만 늘리지 말고 coordination 상한과 PostgreSQL `max_connections`를 함께 검토한다.

## 4. Provider 동시성·rate limit

- Yahoo quote/price/analyst/macro는 공유 throttle 사용
- Yahoo IP-wide minimum interval 350ms, rate-limit backoff 30초
- Yahoo macro key는 전체 host pass 실패 시 같은 host 집합을 한 번만 추가 시도하며 모든 시도에 공유
  throttle을 적용
- Company summary concurrency 4. 수집 주기는 유지하되 4코어 홈서버의 순간 turbo/발열을 제한한다.
- SEC Company Facts/Submissions concurrency 2
- SEC filing detail concurrency 1, inter-request 150ms
- Analyst ticker 간 200ms
- FRED 2, Yahoo market 8, supplemental 4

각 adapter가 독립 thread pool로 provider 한도를 초과하지 않게 공용 throttle 또는 명시 semaphore를 사용한다.

13F는 첫 startup invocation에서만 `institutional.filing.collected_at`을 확인한다. 최근 2시간 이내의
영속 성공 수집이 있으면 약 9만 holding의 동일 파싱·삭제·재삽입을 생략한다. 이후 24시간 fixed-delay
invocation은 이 shortcut을 사용하지 않으므로 잦은 재배포가 정규 수집을 무기한 미루지 못한다.

## 5. Single-flight와 cache

동일 ticker/source의 동시 요청은 한 refresh를 공유한다. cache hit, stale-while-revalidate, last-valid는 다음
순서로 구분한다.

1. fresh cache: 즉시 반환
2. 허용 stale: last-valid 반환 + 단일 background refresh
3. stale window 초과: 현재 의사결정 fail-closed
4. cold start: pending/neutral projection, 과거 BUY 생성 금지

refresh 실패가 오래된 BUY/바닥 후보를 새 결과처럼 timestamp만 갱신하지 못한다.

매시간 Telegram 기업 scan은 2시간 이내의 완전한 V22 company summary가 있으면 점수·바닥·반전·MACD 근거를
그대로 재사용한다. 누락·부분·stale이면 직접 재검증하고 실패 시 fail-closed한다. 따라서 알림 주기를
줄이지 않으면서 정상 경로의 277개 차트 중복 계산을 제거한다.

## 6. 멱등 쓰기

- market observation: `(source,key,date)` upsert
- sector fund flow/price breadth: `(sector_key,observed_on)` upsert, 두 증거 부분실패 분리
- sector composite validation: market snapshot 경로에서 완료 공통 거래일당 최초 1 run+11 items transaction;
  21/63/126 세션이 성숙한 outcome만 append, GET 요청은 write 금지
- analyst snapshot: `(ticker,observed_on)` unique
- company summary: ticker aggregate 원자 교체
- peer/identity: point-in-time `valid_from/valid_to`
- object: immutable version + active pointer 교체
- notification: candidate fingerprint + transactional outbox
- execution PATCH: advisory lock + `SELECT FOR UPDATE` + version

재시도는 동일 결과를 만들거나 명시적 revision을 추가해야 하며 duplicate command를 새 금융 이벤트로 만들지
않는다.

## 7. 실패·재시도 의미

- 기업 요약·analyst history·earnings의 대상 universe는 raw catalog membership에서 읽는다. 현재 섹터 순환
  overlay는 단기·중기 momentum coverage와 거시 입력을 요구하므로 수집 대상 결정의 선행조건으로 사용하지
  않는다. 순환 입력 부족이 무관한 배치 stale로 전파되어서는 안 된다.
- collection report가 일부 저장이면 DEGRADED
- Yahoo FX 단일/복수 key DEGRADED는 실제 직전 관측이 30분 이내일 때만 hard alert를 유예하며
  DEGRADED status와 실패 key는 그대로 보존
- accepted observation 저장 실패가 있으면 SUCCESS 금지
- scheduler 예외는 swallow하지 않고 Micrometer가 실패를 볼 수 있게 재전파
- outbox는 지수 backoff, 최대 12회 후 DEAD
- outbox lease 5분, batch 20, terminal row 30일 보존
- 긴 작업은 bounded timeout과 body size limit 적용

## 8. 변경 체크리스트

새 scheduler 또는 수집기를 추가할 때 다음을 모두 작성한다.

- owning context와 task key
- startup/fixed delay와 source 발표주기
- provider timeout/body/concurrency/rate limit
- JVM non-overlap과 DB advisory lock
- idempotency key/unique constraint
- partial success 의미
- last-valid와 stale TTL
- metric/log/trace 이름
- data integrity metric 또는 audit query
- 정상/부분실패/중복실행/재시도 테스트
- 이 문서와 [데이터 신선도 문서](../finance/DATA-SOURCES-AND-FRESHNESS.md) 갱신
