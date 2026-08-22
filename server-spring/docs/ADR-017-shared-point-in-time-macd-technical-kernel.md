# ADR-017: 기업과 시장은 공통 point-in-time MACD 기술분석 커널을 사용한다

- 상태: **Accepted**
- 결정일: **2026-08-21**
- 관련: [MACD 방법론](../../docs/finance/MACD-TIMING-METHODOLOGY.md),
  [PDR-012](../../docs/PDR-012-macd-timing-signal-disclosure.md)

## 맥락

기업 상세와 시장 지표에 MACD 교차·다이버전스를 추가할 때 각 bounded context가 산식을 복제하면 EMA
시드, 주봉 집계, 피벗 확인일과 임계값이 달라질 수 있다. 외부 TA 라이브러리 객체를 domain에 유입하면
공급자·프레임워크 결합과 직렬화 누수가 생긴다. 다이버전스 피벗을 발생일에 소급 표시하면 라이브에서
알 수 없던 우측 가격이 백테스트에 누출된다.

## 결정

1. `io.macrosquare.technical.domain`을 framework-free 공유 커널로 둔다.
2. 입력은 `LocalDate`와 양의 유한 종가뿐인 `TechnicalClosePoint`다. Yahoo DTO, ORM, HTTP 타입은 받지 않는다.
3. MACD는 EMA 12·26, signal EMA 9 표준 산식을 사용하고 일봉과 별도 주봉 집계를 함께 반환한다.
4. 일반 다이버전스는 좌·우 확인 피벗으로만 확정하며 확인일과 경과 관측 수를 반환한다.
5. company application은 회사 가격 포트를 기술 입력으로 변환하고, market domain은 시장 시계열을 기술
   입력으로 변환한다. 서로의 bounded context 모델을 직접 참조하지 않는다.
6. 기술 결과는 관측 projection일 뿐 Company/B/바닥/반전/섹터 점수를 변경하지 않는다.
7. 기존 50/200 SMA 교차 키는 유지하고 MACD 키를 별도 namespace로 추가한다.

## 검토한 대안

- 회사·시장에 산식 복제: 임계값 drift와 재발 장애 위험 때문에 기각.
- TA 라이브러리 모델을 domain에서 직접 사용: 외부 타입과 버전 결합 때문에 기각.
- 피벗 날짜에 다이버전스 즉시 표시: 미래 데이터 누수 때문에 기각.
- MACD를 즉시 B Score에 반영: point-in-time 장기 검증이 없어 기각.

## 결과

- 같은 종가 입력은 회사와 시장에서 같은 일봉 결과를 낸다.
- API/UI에 현재 관계, 최근 교차 방향·날짜·경과, histogram 상태, 다이버전스 확인일을 구분해 노출한다.
- 주봉 최신 봉이 미완성일 수 있음을 함께 전달한다.
- DB migration과 원시 데이터 복제는 없다. 기존 projection에 nullable 필드와 시장 derived key만 추가한다.

## 운영·관측·롤백

- 부족 이력은 오류나 중립 호재가 아니라 `UNAVAILABLE`이다.
- 회사 API의 `bottomSignal.macdMomentum`, 시장 snapshot의 `*_MACD_*` 키 존재 여부를 smoke test한다.
- 롤백은 UI panel과 projection 연결을 제거하면 되며 기존 점수·DB는 영향을 받지 않는다.

## 재검토 조건

- 거래소 캘린더 기반 완결 주봉 판정 도입
- OHLC 기반 피벗 또는 거래량 다이버전스 도입
- point-in-time 워크포워드 성과 검증 뒤 액션/점수 반영 제안
