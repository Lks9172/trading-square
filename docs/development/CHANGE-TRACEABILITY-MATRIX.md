# 변경·검증·관측 추적 매트릭스

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-16**

한 기능이 “코드에 있다”는 것과 “운영에서 다시 깨지지 않는다”는 것은 다르다. 아래 표는 금융 결과에서
원천·정책·영속성·검증·관측·사용자 노출까지 역추적하는 기준이다.

| 계약 | 정책/소유 코드 | 영속 가드 | 자동 검증 | 운영 탐지 | 사용자 노출 |
|---|---|---|---|---|---|
| Company/B Score | `CompanyScoringPolicy`, `CompanyBuyScoringPolicy` | V15 원자 score bundle, version 6 | domain 경계값, summary repository | incomplete/stale/noncurrent score | 기업 상세·목록 |
| 최종 액션 | `CompanyInvestmentDecisionPolicy` v8 | BUY evidence check | action gate/golden | BUY_WITHOUT_EVIDENCE | 액션·근거·무효화 조건 |
| 바닥/반전 | bottom/volume/structure/reversal policies + Yahoo split-event basis normalizer + price projection composite refresh | V15 원자 signal bundle | 신규 split 정규화/이중보정 방지/event 불일치 fail-closed/pending-current 문구 배타성/no-lookahead/threshold | incomplete/discontinuity | 차트·Telegram |
| EPS revision | analyst normalization/policy | ticker+observed date unique | 0/부호전환/target 분리 | empty/future/duplicate | catalyst 근거 |
| 섹터 EPS revision breadth | research breadth policy + company read-only ACL | V17 nullable finite revision 열 | 3일·5종목·50% coverage | revision non-null/observed range/coverage | source date·상향/하향·자료 없음 |
| 섹터 ETF flow·가격 breadth | research flow/breadth policy + official/company ACL | V18 `(sector_key,date)` snapshot | 21일 flow·10종목/70% breadth·stale/부분실패 | V18 constraint·scheduler report·row age | 공식 날짜·금액·비율·추적 universe 한계 |
| 섹터 현재 주도 | total-return momentum V2 | market series identity | walk-forward/domain/API | 17 series·정렬·불연속 | 섹터 순환 UI |
| 섹터 거시 composite | continuous macro regime + rotation policy | V19/V20 immutable run+11 items+신호일/가격 anchor+성숙 outcome | label invariance·중복 alias·capture/outcome no-lookahead | methodology/version·11×run·pending horizon·date order | 관찰 우선순위·비확률·초기 표본부족 고지 |
| 다음/다다음 섹터 | rotation composer/checklist | projection version/as-of | component/gate tests | source freshness | horizon+확인 조건 |
| 미국 유동성 방향·전환 | `CoreDerivedIndicatorPolicy` 수요일 시점 순유동성 impulse/acceleration/turn·현재 3축 + `CoreAssetSignalPolicy` | WALCL·WDTGAL·RRP 관측일/단위 보존, WTREGEN 평균 미사용, 3축 oldest component date, 미래 보간 금지 | 단위 변환·as-of gap·4주 구간 전환·후행 분기 flow 0선/현재축 제외·분기 source-age laundering 차단·전달 coverage·stale notification 제외 | 원천 freshness·coverage<2/3·TGA/후행 분기 거래 동반(대표 분기일 표시)·RRP 저잔액 | 메인 미국 순유동성 패널(3축 개수·축별 상태·4주 기여 포함)·자산 신호·Telegram·주간 리포트 |
| 표준 섹터 percentile | 표준 11개 전용 domain universe | SPY+11개 원자적 total-return 단면 | 테마 불변성·부분배치 차단 테스트 | 12/12·동일 최신일 | 전략 테마와 분리 |
| 수집 성공 의미 | `MarketCollectionReport` | V16 SUCCESS check | partial/zero failure | HARD/STALE_COLLECTION | 신선도/DEGRADED |
| 일시적 Yahoo FX 공백 | Yahoo adapter retry + integrity freshness projection | 실제 observation collected_at, DEGRADED status 보존 | retry/fresh 30m/stale 경계 | failureSources ERROR + HARD 승격 | 반복 경보 억제, 의사결정 불가만 경보 |
| 기업 배치 완전성 | summary refresh use case | ticker/current version | 277 universe fixture | oldest/exact count | stale 경고 |
| 원문 artifact | storage port | immutable object+pointer+checksum | adapter/integration | dangling pointer | source link |
| 후보 알림 | candidate policy | state+outbox transaction | transition/render/retry | drift/dead/stuck | Telegram |
| 실행 계획 | execution aggregate | advisory+row lock+version | concurrent PATCH | conflict/latency | plan/tranche |
| 문서 계약 | document verifier | 해당 없음 | CI+cutover invariant | 배포 preflight | 문서 허브 |

## 변경 체크 순서

1. 표에서 owning policy와 bounded context를 먼저 찾는다.
2. UI/adapter가 아니라 Domain 또는 application contract를 수정한다.
3. 정상 경로 외에 결측·stale·부분실패·중복·동시성·단위 오류를 재현한다.
4. DB에 잘못된 상태가 저장될 수 있으면 constraint/migration을 추가한다.
5. 기존 오염 가능성을 읽기 전용 query로 조사한다.
6. 1분 integrity/host monitor 또는 일일 audit에 탐지 근거를 추가한다.
7. 이 표, 관련 금융/개발 문서, ADR/PDR를 갱신한다.
8. CI, 홈서버 smoke, 배포 후 로그·메트릭·DB 실측을 남긴다.

## 누락 판정

다음 중 하나라도 없으면 장애 수정은 임시 대응으로 본다.

- 재현 가능한 테스트 또는 DB 경계 차단
- stale/partial/future/단위 의미의 명시
- 운영에서 재발을 찾을 fingerprint/metric/query
- 현재 데이터 오염 여부 확인
- rollback 가능한 배포 증거
