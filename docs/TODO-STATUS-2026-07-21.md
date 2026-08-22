# MacroSquare 장기 TODO 실제 상태

- 문서 상태: **SNAPSHOT**
- 현재 TODO 아님: 2026-07-21 시점의 구현 감사를 보존한 문서다.
- 기준일: **2026-07-21**
- 운영 기준: `server-spring` Java 21 / Spring Boot 4.1
- 상태 정의: **완료**(운영 경로와 테스트 존재), **부분**(일부 지표·fallback/seed 의존), **미착수**(Spring 운영 구현 없음)

과거 TypeScript 파일 경로를 적은 TODO 문서는 설계 이력으로만 보존한다. 현재 작업 우선순위는 이 문서를
단일 기준으로 사용하며, Node 코드의 존재만으로 운영 완료로 판정하지 않는다.

> 2026-07-26 추가 감사: `@asset.x2` 채널 17개 영상의 자막·화면 논리를 다시 대조했고,
> 남아 있던 지지/저항 구간·다우 구조·252일 채널·추세 훼손 3단계·RSI 다중확인·
> 구조 기반 분할진입을 Spring domain, 회사 UI, 시장 지표, Telegram 근거에 반영했다.
> 상세 매핑은 `docs/ASSET-X2-VIDEO-COVERAGE-2026-07-26.md`를 기준으로 한다.

## 완료

| 영역 | 운영 상태 | 대표 근거 |
|---|---|---|
| 거시→섹터→기업/자산 탑다운 | 완료 | market snapshot, research sector/rotation API, `SectorRotationPolicy` |
| 표준 11개 섹터·전략 테마 분리 | 완료 | research catalog API와 sector/theme UI |
| 다음·다다음 주도 섹터와 예상 시계 | 완료 | rotation horizon/outlook domain 모델과 research 응답 |
| 기업 펀더멘털·Company/B Score | 완료 | SEC Company Facts normalization, `CompanyScoringPolicy`, `CompanyBuyScoringPolicy` |
| 8-K/6-K/Exhibit 99.x·IR/PDF 본문 | 완료(미국) | SEC submissions/filing detail adapters, PDF bounded parser |
| 매출·마진·CAPEX·FCF guidance | 완료 | `CompanyGuidanceParsingPolicy`, 숫자 value parser |
| segment/geography 비중 | 완료 | XBRL facts + percentage normalization + 대표기업 fallback |
| analyst consensus/history/revision 입력 | 완료 | Yahoo `earningsTrend` 기반 forward-EPS 7/30/90일 변화율, 목표가 상승여력 변화 분리, PostgreSQL history, 전체 research universe 수집 |
| 병목 후보 점수·근거 | 완료 | research bottleneck domain policy/API/UI |
| narrative heat/stage/history | 완료 | narrative domain policy/API/UI와 history 모델 |
| narrative 외부 실데이터 품질·결측·revision | 완료 | Google News RSS·Wikimedia API·선택적 YouTube API, 소스 등급/신선도/last-valid 감쇠, PostgreSQL V5/MinIO 원문, API/UI 45일 관측·revision 이력 |
| 가격·거래량 바닥/반전 확인 | 완료 | company bottom/reversal domain policy, 상세/UI/Telegram 연결 |
| KRX 투자자별 수급 | 완료 | Naver Finance의 KRX 집계표 6페이지를 EUC-KR로 직접 수집, 실제 60영업일 외국인·기관·개인·연기금 시계열과 환율 이중 게이트 계산 |
| 실행 plan/tranche/context | 완료 | PostgreSQL ACID execution aggregate와 실행 UI |
| Telegram 후보·시장·startup 알림 | 완료 | transactional outbox, lease/retry/dead-letter, PostgreSQL dedupe state |
| 기업 질 지표 확장 | 완료 | 감사 연간 XBRL 기반 ROIC·유효세율·3년 희석 CAGR·accrual quality |
| 주요 기관 13F 직접 수집·분기 변화 | 완료 | SEC 13F-HR 원문/정보표 수집, 주식 수 변화 기반 추정 순매매·공통 보유 API/UI |
| 연준 정책 원문 NLP | 완료 | Federal Reserve 공식 RSS/원문, lexicon evidence·confidence·market auto input |
| 기관 identity·괴리 고도화 | 완료 | 20개 관리자, point-in-time CUSIP→ticker/섹터, analyst-vs-13F divergence |
| 정책 공식원문·보정 확장 | 완료 | 과거 FOMC 결정 walk-forward calibration, Treasury·USTR 공식 원문 |
| 동적 산업 peer | 완료 | SEC SIC 계층 후보 발견, as-of 유효기간·상장 survivorship 관리 |
| 시장 입력 신선도 하드 게이트 | 완료 | 발표주기별 7/14/75일 및 공표지연 분기자료 270일 만료, stale 값 UI 보존·신호 산식 제외 |
| 현재 topdown·execution 네이티브 재계산 | 완료 | 신선도 통과 시장 입력으로 섹터 순환과 실행 체크리스트를 매 snapshot 재계산하고, 70%/85% 커버리지 게이트 미달 시 fail-closed |
| 순환 후보 전환 확인·개별 결측 차단 | 완료 | 단기/중기 상대강도 개별 완전성, 이익추정·수급 확인도, 훼손 조건을 분리하고 stale seed 재진입 차단 |
| 회사 현재성·주식분할 정합성 | 완료 | 가격 7일·재무 200/400일 액션 상한과 canonical split basis-break 희석 오인 차단 |
| 한국 OpenDART | 완료(키 기반 활성화) | 기업코드 ZIP, 중대공시 이벤트, 연결재무 parser와 PostgreSQL/MinIO 저장 |
| OBV/VWAP 확인 지표 | 완료 | 기업 OHLCV 기반 OBV 방향·VWAP 괴리 보조신호와 상세 차트 |
| 투자 horizon별 신호·검증 | 완료 | 단기/중기/장기 가중 정책과 미래정보를 사용하지 않는 causal walk-forward |

## 외부 자격증명에 따른 선택적 활성화

| 영역 | 기본 운영 | 자격증명 주입 시 |
|---|---|---|
| narrative 외부 실데이터 | Google News RSS와 Wikimedia 공식 Pageviews API를 키 없이 수집 | `YOUTUBE_API_KEY`가 있으면 YouTube Data API 30일 신호 추가 |
| 한국 OpenDART | API/UI는 `collecting`, 수집 비활성 | `DART_API_KEY`와 `DART_COLLECTION_ENABLED=true`로 공식 실데이터 수집 |

외부 키가 없는 소스는 중립값으로 가장하지 않고 `MISSING`·가중치 0으로 노출한다. 네이티브 관측이 한 번이라도
생긴 테마는 전체 소스 장애 시 legacy 값으로 되돌아가지 않으며, 허용 기간 이내 마지막 정상값만 `STALE`로
35% 감쇠해 사용한다. 설정 여부는 비밀값을 노출하지 않는 `/actuator/info`의 `optionalIntegrations`로 진단한다.

## 이번 스프린트 구현 범위와 안전장치

1. **SEC 13F**
   - Berkshire Hathaway, Bridgewater, Renaissance, Soros의 최근 2개 13F-HR을 공식 SEC 원문에서 직접 수집한다.
   - 매수/매도는 평가액 증감이 아니라 **보고 주식 수 증감**으로 분류한다. 화면의 순매매 금액은 해당 분기 보고 가격을 사용한 추정치임을 명시한다.
   - 13F는 분기 종료 후 최대 45일 지연될 수 있으므로 실시간 주문 신호나 단독 매수 신호로 사용하지 않는다.
2. **정책 NLP**
   - Federal Reserve 공식 도메인만 허용하며, 매파/비둘기 phrase와 원문 excerpt를 함께 저장한다.
   - confidence가 낮거나 180일보다 오래된 분석은 시장 자동 입력으로 전달하지 않고 마지막 유효값을 보존한다.
3. **기업 질 지표**
   - 분기 누적값 중복 합산을 피하기 위해 ROIC·유효세율·accrual은 감사 연간 duration fact를 사용한다.
   - TTM은 SEC 태그 전환을 감지해 최신 유효 시계열을 선택하고 `최근 FY + 현재 YTD - 전년 동기 YTD`로 계산한다.
   - 희석률은 주식분할이 반영된 diluted weighted-average shares의 서로 다른 4개 연도에서 3년 CAGR을 계산한다.
4. **거래량/시계열**
   - OBV/VWAP는 바닥 신호를 대체하지 않는 확인 지표다.
   - horizon 검증은 각 시점에 당시까지 공개된 데이터만 사용하며, 20/63/126 거래일 forward 결과를 독립적으로 산출한다.

## 운영·플랫폼 TODO 상태

| 항목 | 상태 |
|---|---|
| PostgreSQL 18 + MinIO 최종 소유권 | 완료 |
| legacy runtime mount 제거 | 완료; 원본/volume checksum·archive 보존 |
| Telegram transactional outbox | 완료; 30일 terminal retention 포함, provider 자체 exactly-once는 API 제약상 불가 |
| 실제 PostgreSQL 멀티 인스턴스 동시성 검증 | 완료; disposable PostgreSQL 18 테스트 7개 |
| DB·컨테이너 운영 hardening | 완료; statement/lock timeout, Maven Enforcer, app non-root/no-new-privileges, Next healthcheck, scheduler lock connection/Hikari 격리 |
| MinIO orphan GC | 완료; 30일 보존·dry-run 기본·DB 재확인·버전 단위 삭제 |
| 오프호스트 자동 백업 | 완료; Mac 별도 호스트 복제·checksum·retention |
| 격리 복구 리허설 | 완료; disposable PostgreSQL/MinIO/Spring readiness 검증 |
| 자동 유지보수 | 완료; macOS LaunchAgent 매일 03:20, 복구 드릴 7일 주기, 중복 실행 lock |
| 브라우저 RUM | 완료; Web Vitals bounded 수집, query/IP/UA/cookie/session 미저장 |
| trace 수집 분리 | 완료; Spring → OTel Collector → Jaeger, 배포 시 강제 sampled trace 실측 |
| metric 관측 | 완료; Spring Actuator/Micrometer → Prometheus, 15일·2GB 상한 |
| 로그 중앙화 | 완료; Docker JSON → socket 없는 Alloy → Loki, 7일 retention |

MinIO 복구 뒤 동일 SHA-256 본문인데 store-local ETag만 달라진 객체는 원문 장애로 오인하지 않는다.
bounded 본문을 읽어 PostgreSQL pointer의 SHA-256과 일치할 때만 현재 version/ETag를 다시 활성화하며,
크기 또는 checksum 불일치는 계속 fail-closed 처리한다.

## 이번 장기 TODO 완료 상태

기존 다음 제품 우선순위 5개는 모두 Spring 운영 경로에 구현됐다.

1. ✅ 13F CUSIP→ticker/섹터 point-in-time 매핑과 기관 universe 20곳 확대
2. ✅ analyst consensus와 실제 13F 주식 수 변화 divergence
3. ✅ 정책 NLP walk-forward calibration 및 Treasury·USTR 공식 원문
4. ✅ SIC/산업 taxonomy 기반 peer 자동 발견과 survivorship 관리
5. ✅ 한국 OpenDART 중대 이벤트·연결재무 parser
6. ✅ Spring 네이티브 KRX 외국인·기관·개인·연기금 수급 수집과 5D/20D/60D 파생 신호
7. ✅ SIC 미제공 issuer의 peer discovery 큐 독점 방지와 transient 실패 재시도 분리
8. ✅ seed 기반 topdown·고정 가격 execution plan을 현재 산식에서 제거하고 Spring 네이티브 계산으로 교체

OpenDART 실데이터 수집은 외부 비밀값인 공식 API 키가 주입된 환경에서만 활성화한다. 키가 없는 환경은
장애나 가짜 데이터로 대체하지 않고 API/UI에 `collecting`을 명시한다. 소스 품질등급·결측·당일 revision까지
구현됐다. KRX 수급은 공식 Open API인 것처럼 표시하지 않고 실제 제공처를 `NAVER_FINANCE`로 노출하며,
페이지 1~6을 함께 읽어 기존 10일/20D 오표기 가능성을 제거하고 60D 극단 수급 신호도 첫 수집부터 계산한다. 현재 저장소 내부의 장기 TODO는 완료 상태이며, 남은 것은 운영자가 발급받아야 하는 선택적
OpenDART/YouTube 자격증명뿐이다.

## 2026-08-06 과잉·오표기 재감사

- `@asset.x2`의 공개 영상 17개와 Shorts 4개를 다시 조회해 문서 목록 이후 새 영상이 없음을 확인했다.
- 기업 상세의 섹터 순위는 정적 카탈로그가 아니라 현재 market overlay를 사용한다. 날짜 없는 카탈로그
  이익수정값은 `참고값`, 동일 거시·상대강도에서 파생한 style flow는 `프록시`로 분리하며 둘만으로
  현재 주도 전환을 `확인` 처리하지 않는다.
- 과거 `estimateRevision30d`는 실제 EPS 추정치 변화가 아니라 **현재 목표가 상승여력 - 과거 목표가
  상승여력**이었다. 주가 하락만으로도 상향처럼 보일 수 있어, 이를 `targetUpsideChange30d`로 격리했다.
- 현재 7/30/90일 revision은 Yahoo `earningsTrend.epsTrend`의 현재 forward EPS와 과거 스냅샷의
  변화율로만 계산한다. 0 근처 분모와 EPS 부호 전환은 과장된 백분율을 만들지 않고 결측 처리한다.
- EPS revision은 B점수의 투자매력 증감과 바닥 실패위험에만 사용한다. 목표가 상승여력 변화나
  정적 reference revision을 대체 입력으로 사용하지 않는다.
- 핵심 7종 일괄 수집에 한정됐던 analyst live 수집을 표준 섹터·전략 테마 전체 ticker의 demand cache와
  시간별 PostgreSQL history 대상으로 확대했다. 직접 수집 실패 시 마지막 정상값 또는 persisted
  fallback만 사용하고, 없는 EPS revision을 0으로 만들지 않는다.
- 하이라이트·실적바닥·조정판정·thesis·capital-flow·cash-flow-quality·상대멀티플에 남던 legacy
  파생값도 현재 Spring 증거로 재생성했다. 목표가 차이를 컨센서스로 부르거나 구조 프록시를 실제
  순유입으로 부르는 경로를 제거했고, SWR 첫 응답도 직접 EPS 수집 전 중립으로 닫는다.
- 시장 캘린더는 과거 seed의 `daysUntil`을 폐기하고 KST 기준으로 D-day를 재계산한다. 과거 행은
  제거하며 미국 OPEX와 KRX 파생 만기 예정 구간을 변동성 참고 일정으로 추가한다. 만기일은 방향
  신호가 아니고 KRX 휴장 변경은 공식 일정 확인 대상으로 표시한다.
- CBOE SPX·SPY·QQQ 당일 옵션 체인 합성 P/C를 전체시장 공식 10D P/C처럼 표시하던 키 불일치를
  수정했다. 10개 일별 관측이 실제로 쌓인 뒤에만 10D를 만들고, 충분한 이력에서 percentile을
  별도 표시한다. 심리 합성은 최소 2/4 입력과 커버리지를 요구한다.
- 종목별 공매도 잔고·외국인 선물·베이시스는 직접 소스가 없으므로 다른 프록시로 가짜 완성하지
  않는다. ETF 계좌 안내도 변동 가능한 세율·한도를 고정하지 않고 총비용·유동성·추적차이·집중도
  실행 체크리스트로 반영했다.

## 2026-08-06 수집 가용성 의미 보완

- 시장 원천값의 **발표일 신선도**와 collector의 **마지막 실행 성공 여부**를 분리했다. PostgreSQL
  `market.collection_status`에는 FRED·Yahoo·공포탐욕·심리·stablecoin·KRX의 마지막 성공/부분성공/
  실패, 저장 건수와 실패 키만 영속한다. 이 운영 상태는 투자 점수 입력이 아니다.
- 부분 수집은 전체 성공으로 숨기지 않는다. 예를 들어 AAII·P/C가 저장되고 NAAIM만 실패하면
  `SENTIMENT=DEGRADED`, `failureKeys=NAAIM_EXPOSURE`로 화면과 시장 Telegram에 표시한다.
- 마지막 실행 성공이더라도 설정된 수집 주기의 2배+5분을 넘으면 `STALE`로 표시한다. 반대로
  collector가 방금 성공했어도 원천 관측일이 발표주기 허용치를 넘으면 기존 신선도 정책이 신호에서
  제외한다. 어느 한쪽도 다른 쪽을 정상으로 가장하지 않는다.
- OpenDART가 꺼져 있거나 키가 없을 때 `collecting`으로 뭉뚱그리지 않고 `disabled`/`unavailable`로
  구분한다. 이전 값이 있으면 `stale` 참고값으로만 노출하고, 없는 값을 중립 점수로 만들지 않는다.
- YouTube는 기존 narrative source 관측의 `MISSING`, 가중치 0, 결측 사유 노출을 유지한다. API 키가
  없는 상태에서 조회수나 영상량을 뉴스·Wikimedia 값으로 대체 추정하지 않는다.
