# ADR-015: 현재 기업 알림 근거를 영속 요약에서 재사용한다

- 상태: **Accepted**
- 결정일: **2026-08-17**
- 관련: [ADR-014](ADR-014-stagger-provider-heavy-startup-work.md),
  [PDR-011](../../docs/PDR-011-startup-snapshot-and-delayed-candidate-recalculation.md)

## 맥락

기업 요약 갱신은 277개 기업의 현재 fundamentals와 가격·거래량·반전 근거를 계산한다. 매시간 알림
스캔이 같은 277개 가격 히스토리를 다시 계산하면서 약 2분간 CPU burst와 불필요한 객체 할당을
발생시켰다. 점수만 영속 요약에서 읽고 반전 근거는 다시 계산했기 때문에 두 작업의 공통 advisory
slot은 중첩만 막았고 중복 계산 자체는 제거하지 못했다.

## 결정

1. `company.research_summary`가 알림에 필요한 현재 가격 근거도 소유한다.
   - 확신형 바닥 신호일
   - 반전 상태 `OFF/EARLY/ON/STRONG`과 0~100 점수
   - 제한된 설명 목록
2. V21은 위 필드를 nullable additive column과 DB constraint로 추가한다. 기존 행은 현재 기업 요약
   갱신 전까지 알림용 근거로 간주하지 않는다.
3. 알림 bounded context의 anti-corruption adapter는 **2시간 이내이고**, 기업 점수·가격 신호·반전
   bundle이 모두 완전한 요약만 재사용한다.
4. 누락·stale·미래시각·부분 bundle이면 기존 직접 재검증 경로로 내려가며, 그마저 실패하면 점수 0,
   바닥 미충족, 반전 OFF로 fail-closed한다.
5. 알림 정책과 임계값은 변경하지 않는다. 저장된 점수는 확률이 아니며 회사 domain이 계산한 현재
   근거의 read model이다.
6. 전수 요약 갱신은 `evaluateCurrent` 경로를 사용한다. 이 경로는 상세 화면에만 필요한 5년
   walk-forward 검증과 legacy parity 비교를 생략하되, 현재 가격·거래량·바닥·반전·가격구조에는
   상세 화면과 동일한 domain policy를 적용한다. 기업 상세 조회의 `evaluate` 경로는 기존
   walk-forward와 parity 진단을 그대로 유지한다.

## 경계

- Company application service가 가격/반전 근거를 계산하고 transport-neutral snapshot을 만든다.
- Company PostgreSQL adapter만 V21 column을 읽고 쓴다.
- Notification adapter는 company snapshot을 `InvestmentCandidate`로 번역한다.
- Notification domain은 JDBC, ORM, JSON, Yahoo 타입을 참조하지 않는다.

## 검토한 대안

- **알림 주기를 늘림**: 데이터 적시성을 낮추므로 기각했다.
- **가격 계산 결과를 장시간 메모리 캐시**: 재시작 시 사라지고 freshness provenance가 약해 기각했다.
- **현재 파일 projection을 무조건 신뢰**: 파일 시각과 DB 계산 시각의 원자성이 없으므로 기각했다.
- **매시간 전수 재계산 유지**: 정확도 증분 없이 발열과 공급자 부하만 반복하므로 기각했다.
- **요약마다 5년 walk-forward 재계산**: 어떤 영속 요약 필드나 실행 액션도 과거 성과 통계를
  소비하지 않으므로 기각했다. 검증 통계는 상세 조회에서 요청 시 계산한다.

## 결과

- 정상 상태의 매시간 알림 스캔은 277개 차트 재계산을 생략한다.
- 기업 요약 갱신 주기와 알림 판정 주기는 그대로 유지된다.
- 30분 요약 갱신은 277개 기업의 현재 신호는 계속 갱신하지만, 각 종목의 5년 히스토리를 매번
  반복 순회하지 않는다. 따라서 정보량과 현재 신호 산식은 유지하면서 CPU 사용만 제거한다.
- V21 직후 첫 요약 갱신 전에는 안전한 직접 재검증 비용이 한 번 발생할 수 있다.
- PostgreSQL row 크기가 소폭 증가한다.

## 운영·관측·롤백

- 기업 요약은 동시성 4로 제한하고 provider-heavy 공통 advisory slot을 유지한다.
- 알림 스캔 duration과 `process_cpu_usage`를 배포 전 동일 시간대와 비교한다.
- V21은 additive migration이므로 애플리케이션 롤백 시 column은 보존한다. 이전 이미지는 추가 column을
  무시한다.
- MinIO/Alloy 메모리 상한은 각각 768MiB/384MiB로 조정하고, 호스트 Intel P-state는
  `balance_power`로 영속 설정한다.

## 재검토 조건

- 기업 요약이 2시간 안에 완료되지 않는 상태가 반복됨
- 알림 스캔에서 직접 재검증 fallback 비율이 지속적으로 높음
- 가격 신호가 일봉이 아닌 더 짧은 주기의 의사결정 근거로 변경됨
- 알림 지연 또는 누락 회귀가 관측됨
