# ADR-013: 수집 유니버스 membership을 현재 섹터 순환 평가와 분리한다

- 상태: **Accepted**
- 결정일: **2026-08-17**
- 관련 문서: [스케줄러·동시성·멱등성](../../docs/development/SCHEDULERS-CONCURRENCY-IDEMPOTENCY.md),
  [데이터 계약·계보](../../docs/development/DATA-CONTRACTS-AND-LINEAGE.md),
  [PDR-010](../../docs/PDR-010-current-sector-rotation-unavailable-state.md)
- 대체/피대체: 없음

## 맥락

기업 요약·analyst history·earnings 수집 대상은 sector/theme catalog의 정적 membership만 필요하다. 하지만
두 수집 adapter가 `QueryResearchCatalogUseCase`를 호출해 현재 섹터 순환 overlay까지 평가했다. 서버 시작
직후 total-return momentum이 아직 0/11이면 순환 정책이 올바르게 fail-closed 했고, 그 예외가 무관한 기업
수집까지 매시간 실패시켰다. 그 결과 기업 가격 신호와 analyst series가 stale해지고 무결성 경보가 연쇄했다.

## 결정

1. 배치 membership 소비자는 `LoadResearchCatalogPort`의 raw `loadSectors/loadThemes`만 사용한다.
2. `QueryResearchCatalogUseCase`는 현재 거시·상대강도 overlay가 필요한 사용자 조회와 기업 sector assessment에만
   사용한다.
3. current rotation 입력 부족은 일반 `IllegalStateException`이 아니라
   `CurrentSectorRotationUnavailableException`으로 표현하고 HTTP 503으로 변환한다.
4. raw membership에도 단일 `CurrentResearchUniverseTickerRegistry`를 적용해 retired ticker를 제외하고 alias를
   현재 ticker로 정규화한다.
5. 순환 입력 부족을 neutral 점수로 보정하거나 captured 순환값을 현재값처럼 재사용하지 않는다.

## 경계와 결과

- 기업/earnings 배치는 섹터 점수 산식과 독립적으로 계속 실행된다.
- 섹터 순환 API는 point-in-time 증거가 부족하면 명시적으로 unavailable이며 잘못된 후보를 만들지 않는다.
- controller/HTTP 상태는 adapter가 소유하고 membership과 금융 임계값은 application/domain 경계를 넘지 않는다.
- DB schema와 저장 데이터 변경은 없다.

## 검토한 대안

- 순환 coverage 0/11을 중립값으로 대체: stale structural seed를 현재 리더로 승격할 수 있어 기각.
- 배치에서 `IllegalStateException`을 catch하고 빈 universe 사용: 성공처럼 보이는 0건 수집을 만들 수 있어 기각.
- query service 전체를 raw catalog로 fallback: 사용자에게 captured 순환값을 현재값처럼 노출할 수 있어 기각.

## 검증·관측·롤백

- analyst/earnings adapter raw-catalog 및 EA·CTRA/MMC lifecycle 회귀 테스트
- 순환 coverage 부족 예외와 HTTP 503 계약 테스트
- cutover invariant에서 membership adapter의 dynamic query 의존 재유입 차단
- 실시간 ERROR fingerprint와 일일 DB stale/coverage 감사 유지
- 롤백은 adapter 의존성을 이전 query use case로 되돌리는 코드 변경뿐이나, 동일 연쇄 장애를 재도입하므로
  허용하지 않는다.
