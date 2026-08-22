# Node to Spring Migration Runbook

> **완료 상태 — 2026-07-21**
> Java 21 + Spring Boot 4.1 백엔드가 운영 포트 `5846`을 인계받았고 Node 런타임 의존은 제거되었습니다.
> 아래 내용은 단계별 이관·parity 검증의 역사적 감사 기록입니다. 현재 운영 상태와 롤백 절차는
> [`CUTOVER-2026-07-21.md`](CUTOVER-2026-07-21.md)를 기준으로 합니다.

## 불변 조건

1. 프런트엔드가 사용하는 `/api/**` 계약과 정보량을 줄이지 않는다.
2. Controller, DTO, ORM, 캐시, HTTP 클라이언트 타입을 Domain에 넣지 않는다.
3. Spring 서버가 shadow 모드인 동안 production 스케줄러와 Telegram 전송을 활성화하지 않는다. 검증용
   scheduler/write는 기본 비활성 feature flag, production 원본과 분리된 전용 volume, 즉시 롤백 경로를
   모두 갖춘 경우에만 허용한다.
4. 동일한 원시 입력으로 Node/Spring 결과를 비교한 뒤에만 endpoint를 전환한다.
5. Node 이미지는 안정화 종료 시점까지 즉시 롤백 경로로 유지한다.

## 바운디드 컨텍스트 이관 순서

1. Company Fundamentals / Company Score
2. Bottom & Reversal Signals
3. Sector Rotation / Narrative / Bottleneck
4. Macro Regime / Derived Indicators / Snapshot
5. Crypto Research
6. Portfolio / Execution Plan
7. Alerting / Telegram
8. Backtesting

## 현재 체크포인트

- 1단계 Company Score/Buy Score 순수 도메인 정책 이관 완료
- 2단계 Bottom Pattern/확신형 바닥/반전 확인 순수 도메인 정책 이관 완료
- 3단계 Bottleneck/섹터 순환/11개 Narrative Heat 순수 도메인 정책 이관 완료
- Node characterization test와 Java golden-master test가 같은 점수·상태·정렬·문구를 검증
- Node snapshot 읽기 전용 outbound adapter와 transport-neutral normalization model 이관 완료
- 내부 `GET /internal/v1/migration/research-parity`에서 live snapshot 기반 dual-run 가능
- 현재 dual-run 범위는 11개 Narrative 전체 출력과 Rotation regime/confidence/regimeScores
- 수집기, 스냅샷 조립, research 계산 데이터, 회사 enrichment, 영속 어댑터는 아직 Node가 소유
- 홈서버 live snapshot 검증: Narrative `11/11`, Rotation `regime/confidence/regimeScores` 완전 일치
- 검증 시 Node/Spring 컨테이너 restart count `0`, Spring shadow readiness `UP`
- 공개 Narrative catalog/overview/detail 응답을 동일 snapshot 기준 전체 객체로 비교해 완전 일치
- 공개 Narrative catalog LAN 벤치마크: Node/Spring 모두 300건 오류 0, p95 `31.543/31.487ms`
- 공개 전략 테마/표준 섹터 목록 API의 Spring route·DTO·application port 이관 완료
- 목록 계산 원천은 임시 read-only Node Anti-Corruption Adapter이며 Spring 로컬 수집/쓰기/Telegram 부수효과 없음
- 홈서버 전체 객체 비교: themes `8,103B`, sectors `27,641B` 모두 완전 일치
- 3회 반복 LAN p95 중앙값: themes Node/Spring `44.622/23.796ms`, sectors `63.553/52.269ms`
- 공개 전략 테마/표준 섹터 상세 API의 Spring route·DTO·application port 이관 완료
- 테마 6개, 표준 섹터 11개, 정렬·fallback·404를 포함한 live 계약 `33/33` 객체 및 바이트 완전 일치
- 상세 응답의 조건부 `avgVolumeConfirmationScore`, nullable trend, 숫자 표현과 성공/실패 회사 shape 보존
- 성공 상세만 키별 5분 캐시하고 동일 키 동시 miss는 single-flight 처리; 404와 upstream 장애는 미캐시
- 대표 상세 3회 반복 LAN p95 중앙값: theme Node/Spring `41.871/26.897ms`, sector `31.938/27.433ms`
- Crypto 목록/상세 공개 route와 전체 명시적 application projection 이관 완료
- BTC/ETH/SOL/XRP/BNB, 소문자 symbol, 404를 포함한 live 계약 `8/8` 상태·객체·바이트 완전 일치
- 기존 Research 목록/상세/Narrative 대표 회귀 `5/5`, 내부 Narrative `11/11`, Rotation 완전 일치
- 동일 불변 projection의 직렬화 byte를 재사용하되 freshness는 outbound 5분 캐시가 계속 소유
- Node route-cache 만료 시 collector/derived cycle이 약 30초 걸리는 cold miss를 로그 trace로 확인
- 최초 성공값 이후에는 fresh 5분·최대 stale 30분 정책으로 즉시 반환하고 key별 단일 virtual-thread 갱신
- 최초 cold miss와 stale 30분 상한 초과만 동기 refresh하며, 재배포 검증에서 read-only warm-up 수행
- Crypto 공개 read 단계 당시 Java 25 `58` tests 통과: domain `19`, application `9`, adapters `24`, bootstrap `2`, architecture `4`
- 홈서버 loopback p95 중앙값: Crypto 목록 Node/Spring `59.426/12.364ms`, BTC 상세 `23.941/10.740ms`
- 브라우저 gzip LAN p95 중앙값: 목록 Node/Spring `184.252/45.798ms`, 상세 `38.418/23.958ms`
- gzip wire bytes: 목록 `134,959B -> 26,241B`, 상세 `25,775B -> 7,034B`; 압축 해제 후 원문 완전 일치
- identity LAN의 새 연결 기반 tail은 Actuator 처리시간과 loopback 측정으로 네트워크 전송 지연과 분리
- Crypto 계산·collector·영속화·scheduler·Telegram 소유권은 아직 Node에 있으므로 트래픽 전환은 보류
- 기업 검색 `/api/company-search`와 최대 20개 요약 `/api/company-summaries` 공개 계약 이관 완료
- 검색 limit의 JavaScript parseInt fallback, 대소문자·점 ticker 검색, 요약 순서·중복 제거·unknown 누락을 보존
- company application projection에는 HTTP/Jackson/SEC/캐시 타입이 없고 Node GET은 outbound ACL 안에서만 사용
- 성공값 fresh 5분·stale 상한 30분·key별 single-flight를 적용하고 검색 256/요약 128 key로 메모리 상한 설정
- 회사 공개 계약 대표·경계값 `8/8`과 기존 Research/Crypto 대표 회귀 `4/4` 상태·객체·바이트 완전 일치
- 홈서버 3회 loopback 중앙값 p50: 검색 Node/Spring `12.689/6.672ms`, 요약 `13.722/6.485ms`
- 동시성 1 p95: 검색 Node/Spring `2.993/1.949ms`, 요약 `3.203/1.676ms`; 각 200건 오류 0
- 요약 gzip wire bytes `1,082B -> 504B`, 압축 해제 전 정보량과 identity 응답 계약 유지
- 기업 상세 `GET /api/company/{ticker}` 공개 계약 이관 완료
- profile부터 peers까지 25개 최상위 필드를 fail-closed 검증하고 중첩 optional/null/배열/숫자/삽입 순서를
  transport-neutral immutable projection으로 손실 없이 보존
- NVDA/NEM/JPM/BRK.B/소문자/unknown 404 홈서버 계약 `6/6` 상태·객체·바이트 완전 일치
- 상세 성공값은 fresh 5분·stale 상한 30분·ticker별 single-flight로 제공하고 128개 key로 상한 설정;
  404·불완전 응답·장애는 캐시하지 않음
- 동일 상세 projection의 직렬화 byte 재사용 및 gzip으로 NVDA wire bytes `30,664B -> 8,446B` (`-72.46%`)
- 홈서버 100건·동시성 10·3회 중앙값 p95: 상세 Node/Spring `30.017/16.539ms`, 오류 0
- Java 25 전체 `104` tests 통과: domain `22`, application `19`, adapters `57`, bootstrap `2`, architecture `4`
- 회사 상세 공개 projection 완료 시점에는 회사 수집/계산·analyst history/source-cache 영속화 소유권이
  모두 Node였으므로 production route 전환을 보류했다.
- SEC Company Facts 직접 read adapter와 taxonomy-neutral semantic evidence 모델 이관 완료
- TTM/YoY/마진/ROE/운전자본/EV 배수 fundamentals 정규화 정책을 순수 Spring domain으로 이관 완료
- 내부 `GET /internal/v1/migration/company-research-parity/{ticker}`에서 legacy serving 결과와
  Spring 직접 정규화·Company Score·Buy Score를 필드 단위 dual-run 가능
- 로컬 live 검증 NVDA/NEM/JPM/MSFT/ISRG/BRK.B/INTU/TSM/ASML/XOM `10/10`에서 fundamentals,
  Score, Buy Score, 이유 문구까지 완전 일치
- 홈서버 직접 SEC cold 검증은 대표 `10/10`, 확장 `30/30`, 동시 요청 `8/8`에서 fundamentals,
  Score, Buy Score, 이유 문구까지 완전 일치
- SEC 대형 JSON은 전체 tree를 만들지 않고 필요한 XBRL tag만 streaming parse하며, 정규화가 관측하는
  최신 10-Q/10-K point만 캐시한다. 서로 다른 CIK의 외부 fetch/parse는 기본 최대 2개로 제한한다.
- 40개 CIK 처리 후 tree 대비 streaming 컨테이너 메모리 `783.4 -> 203.6MiB` (`-74.0%`),
  heap committed `682.7 -> 105.6MB`, GC pause 합계 `1.355 -> 0.082s`로 감소
- SEC `company_tickers.json` 직접 read adapter와 framework-free company identity port 이관 완료
- ticker trim/대문자/점→하이픈, 10자리 CIK, missing-only 수동 alias 규칙을 Node와 동일하게 보존하고
  성공 directory 한 벌만 fresh 24시간·stale 상한 7일·global single-flight로 캐시
- 로컬 실데이터 48종목에서 직접 SEC identity/fundamentals/Score/Buy Score `48/48` 완전 일치
- 2026-07-01 XOM successor registrant 전환은 registry CIK `0002115436`과 재무 연속성 CIK
  `0000034088`을 분리한다. 새 CIK에 core XBRL flow가 생기면 후보 순서에 따라 자동 전환한다.
- Yahoo chart metadata 현재가 직접 read adapter 이관 완료. Node와 같은 `query1→query2`,
  `range=5d&interval=1d`, ticker 점→하이픈, `MMC→MRSH`, UTC 거래일 계약을 보존
- parity 응답에서 quote를 fundamentals와 분리해 symbol/price/date 차이를 직접 관측하고, 로컬·홈서버
  48종목 identity/quote/fundamentals/Score/Buy Score를 각각 `48/48` 완전 일치 확인
- Yahoo 성공 quote는 ticker 128개 상한, fresh 1분·stale 상한 15분, ticker별 single-flight로 보관하고
  외부 fetch 동시성을 최대 8건으로 제한한다. 실패·malformed 응답은 이전 성공값을 덮어쓰지 않는다.
- Node가 영속화한 analyst consensus/history source-cache를 읽는 read-only adapter와 framework-free
  evidence port 이관 완료. 기존 회사 read projection은 더 이상 expectations 계산 입력으로 사용하지 않는다.
- consensus fresh 1시간·stale 상한 7일, megacap 7종목 한정, 정확한 UTC 30일 전과 가장 가까운 snapshot
  선택, upside 2자리·analyst score revision 3자리 반올림 계약을 Node와 동일하게 보존한다.
- parity 응답에서 expectations를 분리해 estimate upside/revision/analyst score revision 차이를 직접
  관측하고, 로컬·홈서버 48종목 identity/quote/expectations/fundamentals/Score/Buy Score `48/48` 일치 확인
- Yahoo current analyst consensus 직접 수집과 전역 single-flight/fallback 이관 완료
- analyst history의 UTC 당일 전체 교체·정렬·365 point 보존 정책, record use case와 persistence port를
  domain/application 경계 안에서 이관 완료
- Spring 전용 history file adapter는 temp file fsync + atomic move, striped lock, malformed fail-closed를
  적용하고 Node source-cache와 분리된 named volume에만 쓴다.
- 첫 shadow 실행은 파일이 없을 때만 read-only legacy history를 seed로 사용하고 이후 재시작은 기존 shadow를
  재사용한다. 홈서버 실측 `seeded=7 -> seeded=0`, shadow 값 `7/7`, legacy 281개 해시 `281/281` 불변
- shadow scheduler는 기본 비활성이며 활성 시 시작 30초 후 한 번, KST 평일 매시 15분/주말 4시간마다
  실행한다. overlap guard를 적용하고 ticker별 실패가 전체 batch를 중단하지 않는다.
- analyst history read를 `legacy`/`dual-compare`/`shadow-preferred` application 전략으로 분리했다.
  transport·파일·Jackson 타입은 application/domain에 들어오지 않으며 지정 ticker 집합도 조립 계층에서 주입한다.
- `dual-compare`는 legacy를 선택하면서 shadow 전체 point를 비교하고, `shadow-preferred`는 지정 7종목의
  shadow를 선택하면서 legacy와 비교한다. shadow 누락·손상은 HTTP 500 대신 `LEGACY_FALLBACK`으로 복구하고,
  지정 외 41종목은 shadow를 조회하지 않는다. `legacy`는 shadow port 자체를 호출하지 않는 즉시 롤백 모드다.
- Java 25 전체 `166` tests 통과: domain `33`, application `36`, adapters `87`, bootstrap `6`, architecture `4`
- 홈서버에서 먼저 `dual-compare` 8/8을 확인한 뒤 `shadow-preferred`로 전환했다. 최종 48종목의
  identity/quote/analyst/history/expectations/fundamentals/Score/Buy Score가 모두 `48/48`이며 history source는
  Spring shadow 7개/legacy 41개다. 공개 API도 `8/8` 상태·바이트 일치했다.
- 두 번의 Spring-only 재생성에서도 Node/프런트 container ID는 불변이고 재시작 0이다. shadow startup은
  모두 `seeded=0`, value 해시는 `7/7` 불변이며 legacy bind mount는 `RW=false`다.
- 기존 company-research parity 계산이 legacy 회사 projection에서 직접 받는 계산 입력은 없다. Spring shadow
  history는 내부 parity expectations의 7종목 입력이지만 공개 API와 production history source-of-truth는
  계속 Node다.
- Yahoo daily chart 가격·거래량 direct adapter와 380 calendar day→최근 260 거래일 정규화 정책 이관 완료.
  `query1→query2`, ticker 점→하이픈, `MMC→MRSH`, UTC 거래일, nullable close/volume 제거 계약을 보존한다.
- 가격 context와 marker, price reset/pattern/absorption/volume confirmation, price-bottom/failure-risk/structure,
  확신형 바닥, 반전 확인을 framework-free domain policy로 이관했다. application/domain에는 Yahoo·HTTP·JSON·
  캐시 타입이 없다.
- `GET /internal/v1/migration/company-price-signal-parity/{ticker}`에서 legacy serving 결과와 Spring direct
  history/정책을 병렬 비교한다. 가격 계산을 격리하기 위해 crowding score, 30일 estimate revision,
  guidance-lowered 세 비가격 scalar만 동일 legacy projection에서 양쪽에 주입하며, 이 세 enrichment는
  아직 이관 대상으로 남는다.
- 로컬 및 홈서버 48종목에서 260개 chart, marker, 전체 가격 점수/상태, 확신형 바닥, 반전 확인이 각각
  `48/48` 완전 일치했다. 기존 company research도 `48/48`, 공개 API도 `8/8` 상태·바이트 일치했다.
- Java 25 전체 `176` tests 통과: domain `35`, application `38`, adapters `93`, bootstrap `6`, architecture `4`.
- Spring-only 재배포 뒤 readiness `UP`, restart 0, 로그 WARN/ERROR/Exception 0이며 Node/프런트 container ID와
  restart count는 불변이다. legacy source-cache bind mount도 계속 `RW=false`다.
- SEC submissions direct read adapter와 framework-free filing/submissions evidence 모델을 이관했다.
  `filings.recent`의 필요한 6개 column(`items` 포함)과 설정된 100행만 streaming parse해 전체 tree 생성을 피한다.
- `GET /internal/v1/migration/company-submissions-parity/{ticker}`에서 profile 5개 필드와 최근 filing 10개의
  accession/date/form/document/description/earnings flag/archive URL을 legacy serving 결과와 비교한다.
- earnings-related 호환 정책은 정확한 `8-K`와 description의 `Item 2.02|earnings|results of operations`를
  순수 domain policy로 분리했다. 기존 submissions parity에는 이 호환 정책을 그대로 유지한다.
- Registry CIK와 submissions continuity 후보를 분리하고 XOM은 새 registry CIK `0002115436`을 노출하면서
  현재 serving submissions CIK `0000034088`을 우선한다. 후보 실패 시 다음 CIK로 안전하게 fallback한다.
- 성공 submissions evidence는 CIK 128개, fresh 4시간·stale 상한 24시간, CIK별 single-flight,
  외부 fetch 최대 2개로 제한한다. malformed/HTTP 실패는 성공값을 덮지 않고 내부 상세 없는 502로 닫는다.
- 로컬·홈서버 48종목 profile/filing metadata가 각각 `48/48` 완전 일치했다. 기존 price signal 및 company
  research도 각각 `48/48`, 공개 API는 `8/8` 상태·바이트 일치했다.
- UTC 자정 직후 Node daily analyst point와 Spring `:15` schedule 사이의 일시적 1-point 차이를 실측했고,
  startup shadow seed로 같은 `2026-07-20` point를 기록한 후 core `7/7`, 전체 `48/48`을 재확인했다.
- Java 25 전체 `192` tests 통과: domain `37`, application `41`, adapters `101`, bootstrap `9`, architecture `4`.
- Spring-only 재배포 후 readiness `UP`, 로그 WARN/ERROR/Exception 0이며 Node/프런트 container ID와 restart
  count는 불변이다. legacy source-cache bind mount도 계속 `RW=false`다.
- SEC accession index와 filing 본문을 직접 읽는 bounded adapter, framework-free document/detail/IR material
  모델과 `GET /internal/v1/migration/company-filing-detail-parity/{ticker}`를 추가했다.
- 기존 Node `irMaterials` metadata는 Spring direct 결과 안에 그대로 존재하는지 비교하고, 의도적으로 새로
  발견한 attachment는 별도의 `directDiscoveryImprovement`로 구분한다. 따라서 개선 때문에 exact list가
  달라지는 것을 parity 실패로 오인하지 않는다.
- `items`의 Item 2.02 earnings 8-K를 먼저 선택한 뒤 일반 8-K/외국 발행사 6-K를 보충해 최대 3개 accession만
  검사한다. 정식 `{accession}-index.htm`(`.html` fallback)의 document table과 상대/root 링크를 처리한다.
- EX-99.x와 investor presentation/slide/deck/supplement만 추적하며 XBRL taxonomy presentation linkbase와
  JPG/PNG 등 슬라이드 자산은 제외한다. 초기 filing-detail 슬라이스는 HTML/TXT를 최대 5MiB·3만 자
  평문·320자 요약으로 제한하고 PDF metadata만 보존했다.
- SEC archive origin과 해당 accession directory 밖의 링크는 요청 전에 거부한다. index 2MiB, detail cache
  128개, text cache 512개, fresh 6시간·stale 24시간, 외부 동시 요청 1개와 요청 간 150ms pacing을 적용한다.
- 로컬 대표 12종목에서 migration readiness와 기존 IR metadata를 각각 `12/12` 보존했고, direct document
  `40건` 중 `35건`을 본문 요약했다. AAPL legacy 10-Q summary 한 건은 현재 문서 재파싱 결과와 달랐지만
  metadata와 direct index coverage는 일치해 별도 summary drift로 관측한다.
- Java 25 전체 `205` tests 통과: domain `41`, application `43`, adapters `106`, bootstrap `11`, architecture `4`.
- PDF 본문을 framework-free `CompanyFilingDocumentContent`와 application content port 뒤로 이관했다.
  PDFBox 3.0.8, SEC HTTP, byte stream은 outbound adapter에만 존재하고 domain/application에는 노출하지 않는다.
- 공식 SEC archive HTTPS 및 `/Archives/edgar/data/` 경로만 허용한다. 문서 5MiB, `%PDF-` signature,
  extract permission, 최대 120페이지(설정 hard cap 200), 정규화 3만 자를 검증하고 페이지 단위로 조기 종료한다.
  성공 content만 512개·fresh 6시간·stale 24시간으로 보관하며 cache miss 전체를 직렬화해 SEC pacing을 지킨다.
- `GET /internal/v1/migration/company-filing-document-probe?url=...`는 전체 본문 대신 최대 500자 preview,
  요약, 총/처리 페이지 수, 문자 수와 truncation만 반환한다. 외부 URL/가짜 PDF/추출 불가 문서는
  내부 상세 없이 502로 fail closed한다.
- ADP Investor Day 2025 공식 SEC PDF `96페이지`를 로컬·홈서버에서 직접 검증했다. 3만 자 상한 때문에
  `92페이지/29,598자`에서 정상 중단했고 cold/warm은 `1,727.100/146.647ms`였다.
- 현재 대표 12종목의 최근 최대 3개 accession에는 PDF가 없었으므로 `0/0` coverage를 성공으로 보되,
  별도 공식 PDF probe와 생성형 2페이지 adapter test로 실제 파싱 경로를 독립 검증한다. 대표 12종목의
  migration-ready/metadata/direct improvement는 `12/12`, attachment/summary는 `40/35`를 유지한다.
- Java 25 전체 `213` tests 통과: domain `42`, application `46`, adapters `110`, bootstrap `11`, architecture `4`.
- 홈서버 기존 submissions/price/company research 회귀는 `48/48`, 공개 API는 `8/8` 상태·바이트 일치다.
  Spring-only 재배포 뒤 readiness/health 정상, 로그 WARN/ERROR/Exception 0, Node/프런트 container ID와
  restart count는 불변이다.
- revenue/margin/CAPEX/free-cash-flow guidance value, metric, summary, analysis 모델과 parser를 순수 domain에
  추가했다. application/domain에는 SEC/PDFBox/HTTP/Jackson/캐시 타입이 없고, 기존 bounded document port로
  읽은 본문만 전달한다.
- parser는 metric 위치를 기준으로 clause/action/value window를 분리한다. USD/US$/EUR, 금액·퍼센트 범위,
  상하한, `plus or minus` 퍼센트·basis point와 low/mid/high band를 지원하고 action이 metric 앞뒤 어느 쪽에
  있어도 `raised/lowered/affirmed/mentioned`를 구분한다. 명시적 방향이 섞이면 stance는 `mixed`다.
- historical result, deferred revenue, reconciliation/definition, forecast comparison, 표 형식, FCF project
  descriptor와 safe-harbor topic enumeration을 fail-closed로 제외한다. META prior CAPEX range 증가는
  `raised`, XOM의 full-year guidance 일치는 `affirmed`로 확인했고 ASML 법률문구 margin 오탐을 제거했다.
- 대표 12종목의 eligible/analyzed material은 `41/41`, relevant `23`, structured material/metric은 `17/24`다.
  local/home extraction coverage와 migration-ready는 각각 `12/12`; NVDA Q1 FY27 revenue
  `$89.18B-$92.82B`, margin `74.4%-75.5%`를 exact assertion으로 검증했다.
- Java 25 전체 `224` tests 통과: domain `53`, application `46`, adapters `110`, bootstrap `11`, architecture `4`.
  최종 홈서버 price signal은 `48/48`, 공개 API는 `8/8` 상태·바이트 일치다. submissions `45/48`은 Node의
  7월 17일 캐시와 Spring이 읽은 7월 20일 SEC 424B2/FWP 간 시간차(JPM/GS/MS), company research `47/48`은
  AAPL Yahoo/history refresh 경계이며 total score `73`, buy score `68`은 동일해 guidance 회귀로 보지 않는다.
- Spring-only 재배포 뒤 readiness/liveness/health 정상, restart와 로그 WARN/ERROR/Exception은 0이고
  Node/프런트 container ID는 불변이다. rollback image 두 벌을 보존했다.
- SEC Inline XBRL 실제 revenue mix 모델과 additive reconciliation 정책을 domain에 이관했다. 최신 10-Q와
  최신 10-K/20-F/40-F에서 reportable segment/product/end-market/geography fact를 직접 읽고, 동일
  source/unit/period consolidated revenue의 80~120% 범위 subset만 선택해 비중 합계를 정확히 100.0%로 만든다.
- parser는 US GAAP/IFRS revenue concept, TSM customer-market/geography axes, ASML 24.9MiB annual report,
  XOM의 aggregate revenue qualifier가 붙은 2축 geography 공시를 지원한다. 임의 cross-tab과 elimination/
  reconciliation은 계속 fail closed한다. TSM 최신 20-F가 6-K 뒤 126번째인 점을 반영해 submissions 보존은
  200행, Inline XBRL hard cap은 32MiB로 제한한다.
- 로컬·홈서버 대표 12종목 모두 migration/direct coverage/percentage/legacy coverage `12/12`이고,
  actual segment `12/12`, geography `11/12`, dimensional fact `829개`다. INTU geography는 legacy와 SEC 모두
  미공시다. TSM·ASML·XOM actual 비중 합계도 각각 정확히 100.0%로 검증했다.
- Java 25 전체 `242` tests 통과: domain `60`, application `49`, adapters `118`, bootstrap `11`, architecture `4`.
  가격 신호 `48/48`, 공개 API `8/8` 상태·바이트 일치다. company research `47/48`의 MSFT는 Yahoo/history
  refresh 경계지만 total/buy score `73/74`가 동일하고, submissions `45/48`의 JPM/GS/MS는 기존 SEC 시간차다.
- Spring-only 재배포 뒤 readiness/liveness/health 정상, restart와 로그 WARN/ERROR/Exception은 0이며
  Node/프런트 container ID는 불변이다. 이전 guidance 이미지는 revenue-mix 전용 rollback 태그로 보존했다.
- 내부 `GET /internal/v1/migration/company-detail-revenue-mix-shadow/{ticker}`에서 actual revenue mix를 기존
  회사 상세의 `segmentGeoMixNote/segmentMix/geoMix` 세 필드에만 branch-by-abstraction 방식으로 조합한다.
  축별 선택은 `direct-sec-actual → legacy-fallback → unavailable`이고 출처는 public 계약 밖에 둔다.
- direct actual이 전혀 없으면 serving projection 객체를 그대로 사용해 숫자 표현과 직렬화 byte를 바꾸지
  않는다. actual이 한 축만 있으면 다른 축의 legacy 항목을 값·null·순서 그대로 보존하며, legacy도 없으면
  대표값을 발명하지 않고 빈 배열과 `unavailable`을 노출한다.
- company detail read와 direct parity가 서로 다른 legacy refresh snapshot을 본 경우를
  `servingSnapshotMatched` 가드로 감지한다. 불일치 시 contract가 같아보여도 `shadowServeReady=false`로
  fail closed하며, fallback/unavailable source는 serving/shadow 값 불변식까지 생성자에서 검증한다.
- 실데이터 fallback probe에서 WMT의 `Walmart US/International/Sam's Club US`가 이름의 지역 단어 때문에
  geography로 오분류되는 문제를 발견했다. 순수 지역명 전체 일치만 label 기반 재분류에 허용해 WMT는
  segment, `United States/Non-U.S.`는 geography로 분리했고 domain 회귀 테스트를 추가했다.
- 로컬·홈서버 대표 12종목은 contract-compatible/serving-snapshot-matched/shadow-serve-ready
  `12/12`, actual segment `12/12`, geography `11/12`다. INTU geography는 양쪽 모두 없어
  `unavailable`, UNH geography는 실데이터에서
  `legacy-fallback`으로 보존됐다. 공개 company 상세 8종목은 Node/Spring byte-exact `8/8`이다.
- Java 25 전체 `251` tests 통과: domain `61`, application `56`, adapters `119`, bootstrap `11`,
  architecture `4`. 홈서버 direct revenue mix `12/12`, company research `48/48`, price signal `48/48`도
  유지했다.
- Spring-only 재배포 뒤 readiness/liveness/health 정상, restart와 로그 WARN/ERROR/Exception은 0이며
  Node/프런트 container ID와 restart count는 불변이다. 직전 image는
  `rollback-company-detail-revenue-mix-shadow-pre`로 보존했다.
- 공개 회사 계산, production 바닥·반전 신호와 알림, 대표 fallback 소유권 및 실제 shadow projection의
  공개 route 전환 전에는 production company orchestration 전환을 보류한다.
- 신규 구축 규격을 Java 21 LTS + Spring Boot 4.1로 조정했다. Maven `java.version`/
  `maven.compiler.release`, builder, runtime image와 Actuator metadata를 모두 21로 맞췄고 컴파일된
  class major version은 `65`다. domain/application/adapters의 DDD·Clean 경계와 Spring Boot 버전은 변경하지
  않았다.
- Temurin 21 builder에서 Java 21 `--release 21` 전체 `251` tests를 통과했다: domain `61`,
  application `56`, adapters `119`, bootstrap `11`, architecture `4`. 홈서버 Java 21 runtime에서
  company detail shadow/direct revenue mix `12/12`, fallback `2/2`, company research·price signal `48/48`,
  공개 company byte-exact `8/8`을 유지했다.
- Java 25/21을 같은 홈서버에 read-only로 동시 기동하고 loopback 5회·500건·동시성 10으로
  교차 측정했다. Java 21은 회사 상세/revenue-mix shadow 처리량이 `+2.01%/+9.37%`,
  p50이 `-8.65%/-10.18%`였다. image는 `253,459,076B -> 235,553,993B`(`-7.06%`), 단독
  warm RSS 중앙값은 `376.9MiB -> 301.0MiB`(`-20.14%`)로 감소했다.
- Spring-only `--no-deps --force-recreate`로 배포했고 Node/프런트 container ID와 restart count는
  불변이다. readiness/liveness는 UP, Spring restart는 0, 로그 WARN/ERROR/Exception은 0이며
  Java 25 이미지는 `rollback-java25-spring-boot-4.1-pre-java21`로 보존했다.
- 공개 `GET /api/snapshot`, `/api/history/coverage`, `/api/history/{source}/{key}`, `/api/history-series`를
  Spring controller와 application port로 이관했다. Node는 계산·수집·history 영속성의 source of truth로
  남고 Spring은 read-only anti-corruption boundary와 공개 응답 계약만 소유한다. POST snapshot/refresh와
  scheduler/Telegram은 전환하지 않았다.
- 전체 legacy document는 Jackson tree를 application에 전달하지 않고 immutable structured value로 복사한다.
  snapshot 7개 최상위 shape, coverage metadata, history identity/count/point와 series identity를 adapter에서
  fail-closed 검증한다. history token과 series key 개수·길이를 제한하고, 성공 projection만 fresh 5분/stale
  상한 30분, history 512개/series 256개로 보관하며 key별 single-flight refresh를 사용한다.
- 실제 Node/Spring 비교는 snapshot, coverage, 존재/미존재 history, 정상/invalid range/repeated key/default
  series의 상태·content-type·byte를 `8/8` 완전 일치시켰다. snapshot gzip은 Spring 전송을 해제한 뒤 Node
  identity body와 byte-exact이고, 기존 company/research/crypto/narrative 공개 read도 `5/5` 유지했다.
- Temurin Java 21 builder에서 전체 `262` tests를 통과했다: domain `61`, application `60`, adapters `126`,
  bootstrap `11`, architecture `4`. 홈서버 Spring-only 재배포 후 readiness/liveness와 Node/프런트는 200,
  restart와 로그 WARN/ERROR/Exception은 0이며 직전 이미지는 `rollback-market-read-pre`로 보존했다.
- 홈서버 warm loopback에서 snapshot 200건·동시성 10은 Spring p50/p95 `9.294/23.022ms`, Node
  `36.721/55.410ms`; history-series 500건은 Spring `6.896/13.993ms`, Node `34.480/44.219ms`였다.
  오류와 응답 byte 차이는 0이다. 이는 Node 계산 소유권 전환 전의 bounded read/payload cache 효과이며
  최종 런타임 계산 성능 판정에는 사용하지 않는다.

### Read-only parity 안전장치

- legacy JSON/Jackson 타입은 outbound adapter 밖으로 전달하지 않는다.
- application/domain에는 Spring, HTTP client, 캐시 구현 타입이 없다.
- 성공한 snapshot만 5분 메모리 캐시에 저장하며 불완전한 응답은 즉시 거부한다.
- upstream 장애는 내부 상세를 노출하지 않고 HTTP 502로 변환한다.
- snapshot read는 endpoint 호출 시에만 수행하며 자체 startup/scheduler/write/Telegram side effect가 없다.
  analyst-history shadow scheduler는 이 경로와 분리된 opt-in inbound adapter다.
- Snapshot/history 공개 GET ACL은 완전한 structured projection만 캐시하고 사용자 제어 history/series key를
  길이·개수 상한으로 제한한다. Spring 자체는 파일 쓰기나 collector를 실행하지 않지만, cache miss 시 호출하는
  Node GET은 기존 Node 정책에 따라 snapshot/history source를 refresh할 수 있다.
- 실측 기준 Spring 캐시 적중 호출은 `0.01s`; legacy snapshot stale 시 관측된 `26.87s`는
  Node refresh를 포함하므로 Spring 계산 성능으로 간주하지 않는다.
- Spring Narrative GET은 Node 구현의 `recordNarrativeState` write side effect를 의도적으로 재현하지 않는다.
  응답 정보량은 유지하고 history 영속화 소유권은 최종 scheduler/영속성 이관 단계까지 Node에 둔다.
- Research catalog adapter는 Node의 GET projection만 호출하고 성공한 정규화 결과만 5분 캐시한다.
  상세 cache key는 정규화된 ID·정렬 조합이며 동일 key의 동시 miss만 합쳐 서로 다른 상세 요청은
  독립적으로 진행한다. 404와 장애는 캐시하지 않고, 스키마가 불완전하면 502로 fail closed하며
  raw JSON은 adapter 밖으로 나가지 않는다.
- Spring은 해당 GET에 로컬 수집·쓰기·스케줄·알림을 추가하지 않는다. 다만 계산 소유권 이관 전까지
  upstream Node GET이 기존 route-cache 만료 시 수행하는 refresh는 그대로 유지한다.
- Crypto adapter는 legacy JSON을 전체 transport-neutral projection으로 fail-closed 정규화한다.
  성공 목록·상세만 5분 캐시하며 상세 동일-key miss만 single-flight로 합친다. 404·스키마 오류·502는
  캐시하지 않고 application/domain에는 Jackson, HTTP, Spring DTO, 캐시 구현을 노출하지 않는다.
- inbound serialized-payload cache는 application projection의 객체 identity가 같은 동안에만 byte 배열을
  재사용하므로 데이터 TTL을 연장하지 않는다. 새 projection이 로드되면 즉시 새 payload로 교체된다.
- fresh TTL이 지난 Crypto projection은 30분 상한 안에서만 stale-while-revalidate로 제공한다. 목록과
  각 symbol은 단일 in-flight 갱신을 공유하고, 실패한 갱신은 이전 성공값을 덮어쓰지 않는다. 상한 이후에는
  stale을 무기한 숨기지 않고 동기 fetch/502 정책으로 복귀한다.
- gzip은 HTTP content negotiation에만 적용되며 JSON 필드와 계산량을 줄이지 않는다. identity 응답은
  Node와 byte-exact이고 gzip 응답도 해제 후 동일 바이트임을 검증한다.
- Company directory ACL은 성공한 검색/요약 projection만 캐시한다. 검색어·ticker 목록을 application에서
  정규화해 cache key를 안정화하고, 검색 256개·요약 조합 128개를 넘으면 가장 오래된 성공값을 제거한다.
  fresh 이후 stale 상한 안에서는 요청을 막지 않고 key별 한 번만 갱신하며 실패값은 기존 성공값을 덮지 않는다.
  빈 검색과 빈 ticker 목록은 upstream 호출 없이 legacy와 같은 `{\"items\":[]}`를 반환한다.

- Company detail ACL은 25개 최상위 필드의 존재와 필수 object/array 타입을 검증하고 모든 중첩 값을
  application의 framework-free structured value로 복사한다. 상세 성공값과 serialized payload는 각각
  128개 ticker로 제한하며, payload는 같은 immutable projection identity에서만 재사용하므로 freshness를
  연장하지 않는다. unknown 404와 malformed/502는 캐시하지 않는다. Spring은 로컬 쓰기를 추가하지 않지만
  upstream Node 상세 GET은 기존 구현상 route-cache refresh와 analyst snapshot/source-cache write를 수행할
  수 있으며 이 소유권은 collector/영속성 이관 단계까지 Node에 남긴다.
- Company fundamentals parallel-run의 SEC adapter는 JSON/XBRL tag와 HTTP 타입을 adapter 안에 가두고,
  domain에는 의미 기반 fact point/list만 전달한다. 성공 evidence는 CIK 128개 상한, fresh 4시간,
  stale 상한 24시간, key별 single-flight로 보관하며 malformed/HTTP 실패는 성공 캐시를 덮지 않는다.
  전체 JSON tree 대신 token streaming을 사용하고 동시 대형 파싱은 기본 2건으로 제한한다.
  endpoint 요청이 없으면 SEC 네트워크 호출이 없고 write/scheduler/Telegram 부수효과도 없다.
- SEC ticker directory adapter도 JSON/HTTP/캐시 타입을 outbound adapter 안에 가두며, application에는
  ticker·registry CIK·ordered fundamentals CIK 후보만 전달한다. malformed/HTTP 실패는 성공 directory를
  덮지 않고, successor CIK의 core 재무 fact가 비어 있을 때만 명시된 predecessor 후보를 사용한다.
- Yahoo quote adapter도 JSON/HTTP/재시도/캐시 타입을 outbound adapter 안에 가두며, application에는
  framework-free symbol/price/date만 전달한다. 최초 성공값이 없는 장애는 안전한 502로 닫고, usable stale이
  있으면 즉시 반환하면서 ticker별 단 하나의 virtual-thread refresh만 실행한다. endpoint 호출이 없으면
  Yahoo 호출이 없고 write/scheduler/Telegram 부수효과도 없다.
- Yahoo price-history adapter도 같은 경계를 지키며 성공값만 ticker 512개, fresh 15분·stale 상한 2시간으로
  제한한다. ticker별 single-flight와 전체 fetch 동시성 8을 적용하고 실패 refresh는 성공값을 덮지 않는다.
  price parity endpoint 자체는 요청 기반 read-only이고 Spring writer/scheduler/Telegram을 호출하지 않는다.
  다만 비교 대상인 upstream Node 회사 GET은 기존 정책에 따라 자체 source-cache를 갱신할 수 있다.
- SEC submissions adapter는 Jackson streaming parser와 HTTP/캐시 구현을 adapter 안에 가두고 application에는
  CIK 기반 evidence port만 제공한다. domain은 filing metadata와 earnings 분류만 소유한다. 성공값은 CIK
  128개·fresh 4시간·stale 24시간으로 제한하고 CIK별 single-flight와 전체 fetch 동시성 2를 적용한다.
  registry/submissions CIK 연속성 선택은 application에서 수행하며 공개 route나 writer를 변경하지 않는다.
- SEC filing-detail adapter는 raw HTML/PDF parsing, URL resolve/검증, byte/page/character limit, HTTP pacing과 캐시를 모두
  outbound adapter 안에 가둔다. domain은 문서 후보 분류와 요약 정책만, application은 CIK/accession 선택과
  legacy baseline/direct improvement/PDF coverage 비교만 소유한다. PDF는 문서마다 독립 `PDDocument`를
  try-with-resources로 닫고, 전체 본문을 inbound 응답에 노출하지 않는다. 요청 기반 read-only이며
  scheduler/write/Telegram을 호출하지 않는다.
- Legacy analyst evidence adapter는 허용된 ticker 형식과 고정 파일명만 사용하고 consensus/history JSON을
  streaming parse한다. source-cache는 Docker bind mount `RW=false`이며, malformed·누락·stale 상한 초과는
  내부 경로를 노출하지 않는 502로 fail closed한다. application에는 파일/Jackson 타입이 없고 domain이
  exact UTC 30일 snapshot 선택과 반올림을 소유한다. 별도 shadow writer는 application save port 뒤에서만
  전용 volume을 쓰며, 첫 seed 이후 legacy를 읽지 않는다. Node production collector/history writer 소유권은
  유지한다.
- Analyst history resolver는 source 상태를 `NOT_EXPECTED/NOT_READ/AVAILABLE/MISSING/UNAVAILABLE`로,
  선택 결과를 `LEGACY/SHADOW/LEGACY_FALLBACK`으로 제한해 내부 parity 응답에 노출한다. 파일 경로나 예외
  메시지는 노출하지 않는다. shadow mismatch는 전체 parity를 실패시켜 관측하되 선택 데이터가 존재하면
  계산은 계속하고, compose 환경 변수 한 줄로 `dual-compare` 또는 `legacy`에 복귀할 수 있다.

### 컷오버 후 해제한 호환 동작

Node 섹터 순환 코드가 에너지 내러티브 보정 시 구성된 `energy-supply`가 아니라
`oil-supply`를 조회하던 결함은 shadow parity 동안만 보존했다. Spring 단독 컷오버 후에는
도메인 `NarrativeTheme.ENERGY_SUPPLY` 식별자를 사용하도록 수정했다. 아래 migration golden은
Node 당시 동작을 재현하는 `LEGACY_REFERENCE_ONLY`이며 운영 산식의 근거가 아니다.

## API 전환 순서

1. health/search/list
2. company read models (`company-search`, `company-summaries`, `company/{ticker}` 완료)
3. sector/theme/crypto read models
4. snapshot/history (`GET` 4종 완료, POST refresh와 persistence 미이관)
5. plan/tranche/trade-log writes
6. schedulers and Telegram side effects

## 호환성 게이트

- 정수 점수 및 상태 enum: 완전 일치
- 실수 지표: 명시된 허용 오차 이내
- JSON 필드명/타입/nullable 여부: 완전 일치
- KST/UTC 날짜 경계: 고정 Clock 테스트 통과
- 10년 백테스트 핵심 결과: 합의된 허용 오차 이내
- Telegram 중복 전송: 0건
- 홈서버 롤백: 5분 이내

## Post-cutover 제품 확장 — 2026-07-21

- Flyway V4로 `institutional.security_identity`, `policy.confidence_calibration`,
  `research.peer_directory/peer_taxonomy`, `disclosure.dart_*` 정규화 테이블을 추가했다.
- 13F manager universe를 20곳으로 확대하고 CUSIP identity·analyst-vs-money divergence를 연결했다.
- Fed 과거 성명, Treasury, USTR 공식 원문과 FOMC 결정 walk-forward calibration을 추가했다.
- SEC SIC 동적 peer/as-of survivorship와 OpenDART 중대공시·연결재무 수집을 추가했다.
- 원본 XML/HTML/ZIP/JSON은 MinIO, query projection은 PostgreSQL이라는 기존 저장 경계를 유지한다.
- 공개 API 계약은 41개로 확장했으며 실 PostgreSQL opt-in 7개를 포함한 Java 391 tests,
  프런트 lint/build, 배포 smoke 41/41을 완료 조건으로 사용한다.

### Narrative 외부 소스 신뢰성 확장

- Flyway V5 `research.narrative_source_observation`에 테마·소스·UTC 일자별 immutable revision을 저장한다.
- Google News RSS와 Wikimedia Pageviews는 키 없이 수집하고, YouTube Data API는 키가 있을 때만 활성화한다.
  키 부재·결측·실패는 중립점수로 대체하지 않고 가중치 0으로 명시한다.
- 원문 XML/JSON은 크기 상한을 적용해 MinIO에 content-addressed key로 보관한다. 동일 원문 재시도는 기존
  active object를 재사용해 불필요한 MinIO version 생성을 막는다.
- domain의 `NarrativeSourcePolicy`가 소스 등급, freshness, 장기 결측, 최근 정상값의 35% 감쇠를 결정한다.
  네이티브 관측이 생긴 뒤에는 전체 장애여도 legacy 신호로 자동 회귀하지 않는다.
- API/UI에 품질점수·커버리지·상태·revision·연속 결측·유효 가중치·원본 링크와 45일 관측 이력을
  노출한다. 목록 응답에는 집계만 싣고 상세 이력은 최대 180건으로 제한해 응답 크기를 통제한다.
- Java 계약은 총 391 tests(실 PostgreSQL opt-in 7개 포함), 운영 공개 API smoke는 41/41을 유지한다.
- Jackson 3/Spring 7 deprecated API와 테스트 raw generic을 제거하고 Mockito를 정적 Java agent로
  구성했다. `/actuator/info`의 선택 연동 진단은 credential 존재 여부만 노출하고 실제 값은 숨긴다.
- Flyway V6는 terminal notification outbox의 30일 retention과 narrative/SIC/OpenDART 조회 인덱스를
  추가했다. Hikari connection init은 PostgreSQL statement/lock/idle-transaction timeout을 강제한다.
- Flyway V7은 `market.observation`의 source constraint에 `KRX`를 추가한다. 구체 제공처는
  `provider_code=NAVER_FINANCE:*`로 분리해 공식 KRX Open API와 혼동하지 않는다. Spring 어댑터는
  EUC-KR 원문 6페이지를 읽어 실제 60영업일 투자자별 수급을 멱등 저장한다.
- 장시간 외부 수집 동안 유지되는 session advisory lock은 별도 unpooled connection source로 격리하고
  fair semaphore로 동시 4개만 허용해 Hikari pool starvation과 leak detection 오탐을 방지한다.
- Maven Enforcer가 Java 21 bytecode·Maven 3.9+ 계약을 고정하고, Spring/Next 컨테이너는 capability를
  모두 제거한 non-root/no-new-privileges 프로세스로 실행한다. Next 자체 healthcheck도 cutover 조건이다.
- 홈서버 실수집에서 11개 테마 모두 `HEALTHY`, 최신 관측 33개 중 Google News/Wikimedia 22개
  `AVAILABLE`, 키가 없는 YouTube 11개 `MISSING`, 최신 `FAILED` 0개를 확인했다. 잘못된
  `Consumer_staples` 문서명은 공식 Pageviews API로 교차 확인한 `Fast-moving_consumer_goods`로 교체했고,
  같은 날 실패 revision 1 뒤 성공 revision 2가 append됐다. MinIO 원문 포인터 dangling은 0개다.
