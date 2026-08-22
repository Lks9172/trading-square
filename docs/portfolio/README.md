# MacroSquare 포트폴리오 문서 세트

이 디렉터리는 세션 `019fe11e-396d-7373-91eb-d21a348c69cd`의 작업 흐름과 해당 MacroSquare 저장소를 바탕으로 만든 포트폴리오 패키지다. 공개용 본문, 기술 다이어그램, 사례 연구, 수치 근거, 면접용 발표 자료를 분리해 재사용할 수 있게 구성했다.

## 문서 구성

| 문서 | 용도 |
|---|---|
| [`../../PORTFOLIO.md`](../../PORTFOLIO.md) | 채용 담당자·면접관에게 먼저 보여줄 공개용 메인 문서 |
| [`ARCHITECTURE-DIAGRAMS.md`](ARCHITECTURE-DIAGRAMS.md) | 시스템·데이터·DB·동시성·배포 흐름 설명 |
| [`ENGINEERING-CASE-STUDIES.md`](ENGINEERING-CASE-STUDIES.md) | 문제–판단–구현–검증 형식의 상세 사례 |
| [`EVIDENCE-AND-METRICS.md`](EVIDENCE-AND-METRICS.md) | 수치 산출법, 코드 근거, 세션 근거, 공개 전 확인사항 |
| [`INTERVIEW-GUIDE.md`](INTERVIEW-GUIDE.md) | 30초/1분/3분 발표, 예상 질문, 이력서 bullet |
| [`diagrams/`](diagrams/) | 다른 문서나 슬라이드에서 재사용할 Mermaid 원본 |
| [`assets/`](assets/) | UI 스크린샷 |

## 추천 사용 순서

1. 공개 저장소의 루트에 `PORTFOLIO.md`를 유지한다.
2. README 상단에서 포트폴리오 문서로 연결한다.
3. 이력서에는 `INTERVIEW-GUIDE.md`의 3–5개 bullet만 사용한다.
4. 기술 면접에서는 `ARCHITECTURE-DIAGRAMS.md`의 런타임·outbox·스토리지·동시성 다이어그램을 사용한다.
5. 수치가 바뀌면 `EVIDENCE-AND-METRICS.md`를 먼저 갱신하고 공개 문서의 숫자를 동기화한다.

## 공개 전 반드시 수정할 항목

- 본인의 실제 역할, 팀 규모, 기여 범위, 프로젝트 기간
- 홈서버 주소, 사용자명, 내부 경로와 credential 관련 표현
- 운영 데이터·회사 분석 결과 중 공개하면 안 되는 내용
- 세션 스냅샷 이후 변경된 테스트 수, 기업 universe, Flyway 버전
- UI 스크린샷에 개인 자산·토큰·서버 주소가 보이지 않는지 확인

## 문서 원칙

- **사실:** 코드·테스트·문서 또는 세션 완료 결과로 확인되는 내용
- **측정값:** 기준일과 측정 범위를 함께 표시
- **설계 의도:** 왜 그 선택을 했는지와 기각한 대안을 함께 설명
- **한계:** 자동매매·수익 확률·exactly-once·고가용성을 과장하지 않음
- **보안:** secret, 사설 주소, 개인 데이터는 공개 문서에서 제거
