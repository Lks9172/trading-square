# ADR-018: Telegram은 현재 기업 MACD 근거를 영속 요약과 ACL을 통해 재사용한다

- 상태: **Accepted**
- 결정일: **2026-08-21**
- 관련: [ADR-015](ADR-015-reuse-current-company-notification-evidence.md),
  [ADR-017](ADR-017-shared-point-in-time-macd-technical-kernel.md),
  [PDR-013](../../docs/PDR-013-telegram-macd-timing-disclosure.md)

## 맥락

회사·시장 화면에 추가한 일봉/주봉 MACD와 다이버전스를 startup·신규 편입·신호 강화 Telegram에도
보여줘야 한다. 알림 스캔은 전체 기업을 훑기 때문에 매번 가격 이력을 다시 계산하면 ADR-015가 제거한
CPU burst가 재발한다. 반대로 오래된 notification state의 기술 신호를 현재값으로 반복하면 시점 무결성이
깨진다. Notification domain이 Company 또는 technical domain 타입에 직접 의존해서도 안 된다.

## 결정

1. 기업 요약 갱신이 기존 `evaluateCurrent` 결과에서 compact MACD read model을 함께 만든다.
   - 일봉·주봉 기준일
   - signal-line 위/아래, 최근 교차와 경과 관측 수
   - histogram 상태
   - 확인된 다이버전스 방향·확인일·경과·활성 여부
   - 진행 중 주봉 여부
2. V22는 `company.research_summary.macd_timing`과
   `notification.candidate_snapshot.macd_timing` JSONB를 nullable additive column으로 추가하고 object
   constraint를 둔다.
3. company summary 계산 계약을 version 6으로 올린다. version 5 행은 현재 조회에서 숨기고 전수 요약
   갱신으로 version 6을 원자 교체한다. 점수 산식과 알림 편입 임계값은 바꾸지 않는다.
4. Company application의 `CompanyMacdTimingSnapshot`을 notification outbound ACL이 notification-owned
   `TechnicalTimingEvidence`로 번역한다. Notification domain은 Company/technical/JDBC/JSON 타입을
   참조하지 않는다.
5. 2시간 이내의 점수·바닥·반전·MACD bundle이 완전할 때만 영속 요약을 알림에 재사용한다. 누락되면
   현재 가격 신호 직접 평가로 fallback하고, 실패하면 기존처럼 fail-closed한다.
6. 시장 Telegram은 현재 market snapshot의 `SP500_*_MACD_*`, `NASDAQ_*_MACD_*` 파생 근거만 읽는다.
   MACD line 숫자 변화만으로 알림 fingerprint를 바꾸지 않고, 이미 발생한 startup/market-change
   메시지의 보조 문맥으로 표시한다.

## 검토한 대안

- 알림 스캔마다 전체 기업 차트 재계산: CPU·I/O 회귀 때문에 기각.
- Notification domain이 `MacdMultiTimeframeAnalysis` 직접 참조: bounded context 결합 때문에 기각.
- 문자열 한 줄을 기존 reasons에 삽입: 타입·시점·누락을 검증할 수 없어 기각.
- MACD 변화마다 별도 Telegram 발송: 워크포워드 검증 전 과도한 매매 알림이 되므로 기각.

## 결과와 호환성

- startup, 신규 기업 편입, 기업 신호 강화 메시지는 같은 compact MACD formatter를 사용한다.
- S&P500/NASDAQ 일봉·주봉은 startup과 자산 신호 변경 메시지의 시장 확인 게이트에 함께 표시된다.
- PostgreSQL row와 outbox payload가 소폭 증가하지만 전체 차트 중복 계산은 추가하지 않는다.
- V22 column은 additive다. 이전 서버 이미지는 추가 column을 무시할 수 있고, version 6 cutover 전에는
  기존 client를 유지한다.

## 운영·검증·롤백

- Flyway V22 적용, company current version 6 전수 수렴, MACD JSON object constraint 2개를 배포 게이트로
  검증한다.
- 실제 outbox payload에서 회사 일봉/주봉, S&P500/NASDAQ, 보조지표 경고 문구를 확인한다.
- 롤백은 이전 서버 이미지로 되돌리고 V22 column을 보존한다. 데이터 삭제 migration은 하지 않는다.
- MACD 누락은 매수 중립값이 아니라 `현재 계산 자료 부족`으로 표시하며 알림 자격을 강화하지 않는다.

## 재검토 조건

- Telegram 4096자 제한 초과 또는 후보 12개 메시지 잘림이 관측됨
- 기업 요약 갱신이 2시간 freshness를 반복적으로 초과함
- MACD가 실제 점수·액션·알림 편입 조건으로 승격됨
- 시장 MACD 상태 전환 전용 알림을 요구하고 point-in-time 성과 검증이 완료됨
