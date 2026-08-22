# MacroSquare 아키텍처 다이어그램

> Mermaid를 지원하는 GitHub, Codex, Notion 연동 Markdown viewer에서 바로 렌더링된다. 원본은 [`diagrams/`](diagrams/)에도 분리했다.

## 1. 프로덕션 런타임

```mermaid
flowchart LR
    B["Browser"] -->|"HTTP :5847"| C["Next.js 16 · React 19"]
    C -->|"server-side API proxy"| S["Spring Boot 4.1 · Java 21"]

    S --> PG[("PostgreSQL 18\n정형 상태·시계열·outbox·pointer")]
    S --> MI[("MinIO\nversioned source·projection body")]
    S --> EX["External providers\nSEC · Yahoo · FRED · Fed · DART"]
    S --> TG["Telegram API"]

    S -->|"OTLP trace"| OT["OpenTelemetry Collector"]
    OT --> J["Jaeger"]
    S -->|"Actuator metrics"| PR["Prometheus"]
    S -->|"Docker JSON logs"| AL["Alloy"]
    AL --> LK["Loki"]

    CR["Host cron + flock"] --> MON["1분 recurrence monitor\n일일 observability audit"]
    MON --> S
    MON --> PG

    classDef app fill:#0f3d4c,stroke:#22d3ee,color:#fff;
    classDef data fill:#163a2a,stroke:#34d399,color:#fff;
    classDef obs fill:#3b2f12,stroke:#fbbf24,color:#fff;
    class C,S app;
    class PG,MI data;
    class OT,J,PR,AL,LK,CR,MON obs;
```

### 포트·보안 경계

- 사용자 진입점은 Next.js 하나로 제한한다.
- Spring, PostgreSQL, MinIO, Jaeger, Prometheus, Loki는 loopback 또는 compose private network에 둔다.
- MinIO bucket은 private/versioned이며 애플리케이션 계정에는 Get/List/Put만 부여하고 delete를 제외한다.
- 컨테이너는 가능한 범위에서 `no-new-privileges`, capability drop, read-only filesystem, non-root를 적용한다.

## 2. Clean Architecture와 bounded context

```mermaid
flowchart TB
    BOOT["bootstrap\nBean · config · health · Flyway"] --> AD["adapters\nREST · scheduler · JDBC · MinIO · provider"]
    AD --> APP["application\nuse case · input/output port · orchestration"]
    APP --> DOM["domain\naggregate · value object · pure policy"]

    subgraph BC["Bounded Contexts"]
      MARKET["Market"]
      COMPANY["Company"]
      RESEARCH["Research"]
      CRYPTO["Crypto"]
      INST["Institutional"]
      POLICY["Policy"]
      DISC["Disclosure"]
      EXEC["Execution"]
      NOTI["Notification"]
      INTEG["Integrity"]
      STORAGE["Storage"]
    end

    APP -. "scalar/value snapshot via port" .-> BC
    ARCH["ArchUnit 6 rules"] -->|"프레임워크·I/O 누수 금지"| DOM
    ARCH -->|"transport·infra 누수 금지"| APP
    ARCH -->|"다른 context inner type 참조 금지"| BC
```

### 강제 규칙

1. domain은 Spring, Jackson, JDBC, MinIO, HTTP, 파일, concurrency 패키지에 의존하지 않는다.
2. application은 adapter/bootstrap, controller DTO, SQL, provider 응답 타입을 모른다.
3. Controller는 inbound web adapter에만 둔다.
4. Scheduler는 cluster-exclusive execution port를 반드시 사용한다.
5. 한 context의 inner layer가 다른 context를 shared library처럼 사용하지 않는다.

## 3. 원천 데이터에서 사용자 판단까지

```mermaid
flowchart LR
    SRC["공식·시장 원천"] --> HTTP["Bounded HTTP Adapter"]
    HTTP --> PARSE["Provider DTO / Parser"]
    PARSE --> NORM["정규화 Application Model"]
    NORM --> VALID["Domain Validation / Policy"]
    VALID --> DB["PostgreSQL row"]
    VALID --> OBJ["MinIO artifact + DB pointer"]
    DB --> PROJ["Current Projection"]
    OBJ --> PROJ
    PROJ --> DTO["REST Adapter DTO"]
    DTO --> UI["Next.js UI"]
    PROJ --> ALERT["Candidate Policy + Outbox"]
    ALERT --> TG["Telegram"]

    META["source · observed-on · filed/published-at\ncollected-at · calculation-version · freshness"]
    META -.-> NORM
    META -.-> DB
    META -.-> DTO
```

핵심은 화면 갱신시각이 금융 기준일을 덮어쓰지 않는 것이다. 원천의 `observed_on`, 공시일, 수집시각, 계산 버전과 freshness를 가능한 범위에서 끝까지 보존한다.

## 4. 기업 투자판단 스택

```mermaid
flowchart TD
    M["거시 국면"] --> A["자산별 허용 위험·비중 상한"]
    A --> S["섹터 순환·상대강도·거시 적합"]
    S --> F["기업 건강도\nROIC · FCF · 성장 · 재무안정"]
    S --> V["가격 매력도\nEV/FCF · EV/Sales · peer · history"]
    S --> C["기대 변화·촉매\nEPS revision · guidance · narrative"]
    F --> I["투자 매력도"]
    V --> I
    C --> I
    S --> I

    P["가격·거래량 이력"] --> B["바닥 후보\nCANDIDATE / CONVICTION"]
    B --> R["독립 반전 확인\nEARLY / ON / STRONG"]
    P --> R
    R --> T["일·주봉 MACD / 다이버전스"]

    I --> G["위험·현재성 gate"]
    R --> G
    T --> G
    G --> D["STRONG BUY · BUY · HOLD · REDUCE · SELL"]
    D --> E["분할 진입 · 추가매수 조건 · 가설 폐기"]
```

### 설계 의도

- 많이 떨어진 종목과 실제 반전이 확인된 종목을 구분한다.
- MACD는 다른 점수와 섞지 않고 timing evidence로 유지한다.
- 데이터 품질과 위험 gate는 높은 점수보다 우선한다.
- UI는 서버의 action을 재계산하지 않고 근거와 주의 문구를 번역한다.

## 5. PostgreSQL–MinIO 일관성

```mermaid
sequenceDiagram
    participant UC as "Application Use Case"
    participant OS as "ObjectStorage Port"
    participant MI as "MinIO"
    participant CAT as "Artifact Catalog"
    participant PG as "PostgreSQL"
    participant RD as "Reader"

    UC->>OS: "versioned body 저장"
    OS->>MI: "PUT object"
    MI-->>OS: "version id + ETag"
    OS->>OS: "SHA-256 + size 검증"
    OS->>CAT: "artifact metadata 기록"
    CAT->>PG: "INSERT metadata"
    UC->>PG: "active pointer 교체 transaction"
    PG-->>UC: "COMMIT"

    RD->>PG: "active pointer 조회"
    PG-->>RD: "exact version + checksum + size"
    RD->>MI: "GET exact object version"
    MI-->>RD: "body"
    RD->>RD: "ETag / SHA-256 / size 재검증"
```

### 장애 의미

- object 저장 실패: pointer 변경 없음, 기존 last-valid 유지
- metadata/pointer commit 전 실패: orphan 가능, reader에게 비노출
- pointer가 없는 object: maintenance GC 대상
- pointer가 가리키는 object 없음 또는 checksum 불일치: CRITICAL integrity incident
- XA/2PC 대신 immutable body + transactional pointer로 복잡도를 제한

## 6. 알림 transactional outbox

```mermaid
sequenceDiagram
    participant SC as "Candidate Scan"
    participant PO as "InvestmentCandidatePolicy"
    participant PG as "PostgreSQL"
    participant DP as "Outbox Dispatcher"
    participant TG as "Telegram"

    SC->>PO: "277개 현재 evidence 평가"
    PO-->>SC: "qualified + strengthening"
    SC->>PG: "candidate snapshot + outbox enqueue"
    Note over SC,PG: "하나의 DB transaction"
    PG-->>SC: "COMMIT"

    loop "15초 dispatch"
      DP->>PG: "FOR UPDATE SKIP LOCKED lease"
      PG-->>DP: "PENDING/RETRY message"
      DP->>TG: "send"
      alt "성공"
        TG-->>DP: "accepted"
        DP->>PG: "DELIVERED ack"
      else "일시 실패"
        DP->>PG: "RETRY + exponential backoff"
      else "한도 초과"
        DP->>PG: "DEAD"
      end
    end
```

```mermaid
stateDiagram-v2
    [*] --> PENDING
    PENDING --> IN_FLIGHT
    IN_FLIGHT --> DELIVERED
    IN_FLIGHT --> RETRY
    RETRY --> IN_FLIGHT
    RETRY --> DEAD
    IN_FLIGHT --> PENDING: "lease expired"
```

## 7. Scheduler·동시성·rate limit

```mermaid
flowchart TD
    TR["Spring Scheduler trigger"] --> NL["JVM non-overlap guard"]
    NL --> EP["ExclusiveTaskExecution port"]
    EP --> COORD["별도 unpooled PostgreSQL connection"]
    COORD --> SEM["fair semaphore · max 4"]
    SEM --> LOCK["session advisory lock"]
    LOCK --> SLOT["task / provider-heavy slot"]
    SLOT --> VT["virtual-thread I/O workers"]
    VT --> TH["provider shared throttle"]
    TH --> EXT["SEC · Yahoo · FRED · DART"]

    API["API transaction"] --> HK["Hikari pool · max 8"]
    HK --> PG[("PostgreSQL")]
    COORD --> PG

    FAIL["DB coordination failure"] --> CLOSE["fail-closed · 로컬 우회 실행 금지"]
```

외부 API가 느릴 때 scheduler가 Hikari connection을 장시간 점유하면 사용자 API까지 고갈될 수 있다. 그래서 조정용 session connection과 업무 transaction pool을 물리적으로 분리했다.

## 8. 시작 시 부하 분산

```mermaid
flowchart LR
    T0["기동"] --> T5["5초\n영속 startup snapshot 알림"]
    T5 --> T30["30초\n13F recent durable evidence 확인"]
    T30 --> T40["40초\nPolicy 수집"]
    T40 --> T45["45초\nYahoo/FRED 수집"]
    T45 --> T75["75초\nMarket snapshot"]
    T75 --> T180["3분\nCompany summary 277"]
    T180 --> T900["15분\nAnalyst history"]
    T900 --> T1200["20분\n전체 candidate 재계산"]

    SLOT["company:provider-heavy advisory slot"] -. "중첩 방지" .-> T180
    SLOT -. "중첩 방지" .-> T900
    SLOT -. "중첩 방지" .-> T1200
```

## 9. 배포·자동 롤백

```mermaid
flowchart TD
    CH["로컬 변경"] --> PLAN["content diff 기반 scope 판별"]
    PLAN -->|"docs/scripts"| LIGHT["검증 + 동기화\n앱 restart 없음"]
    PLAN -->|"server/client"| STD["standard tier"]
    PLAN -->|"Flyway/persistence/compose"| REL["release tier"]

    STD --> BUILD["영향 서비스 production build"]
    REL --> BK["최근 backup + PostgreSQL 18 integration"]
    BK --> BUILD
    BUILD --> SYNC["source/config 동기화"]
    SYNC --> CUT["server rolling replace"]
    CUT --> READY{"readiness UP?"}
    READY -->|"yes"| CLIENT["client replace"]
    CLIENT --> VERIFY["43 API smoke + verify-home"]
    VERIFY --> OK{"all gates pass?"}
    OK -->|"yes"| DONE["running image 확인\nrollback 임시 tag 정리"]
    READY -->|"no"| RB["직전 compose/config/image 복구"]
    OK -->|"no"| RB
    RB --> FORCE["force recreate + health 확인"]
```

## 10. 핵심 데이터 모델

```mermaid
erDiagram
    MARKET_OBSERVATION {
      string source PK
      string series_key PK
      date observed_on PK
      decimal value
      timestamp collected_at
    }
    COMPANY_RESEARCH_SUMMARY {
      string ticker PK
      int calculation_version
      int total_score
      int buy_score
      string action
      string bottom_state
      string reversal_status
      json macd_evidence
      timestamp updated_at
    }
    NOTIFICATION_CANDIDATE {
      string candidate_key PK
      string fingerprint
      json evidence
      timestamp updated_at
    }
    NOTIFICATION_OUTBOX {
      long id PK
      string dedupe_key UK
      string status
      int attempt_count
      timestamp available_at
      timestamp lease_until
    }
    OBJECT_ARTIFACT {
      long id PK
      string object_key
      string version_id
      string etag
      string sha256
      long size_bytes
    }
    ACTIVE_OBJECT_POINTER {
      string logical_key PK
      long artifact_id FK
      timestamp activated_at
    }
    SECTOR_ROTATION_RUN {
      long id PK
      string methodology_version
      date signal_date
      date price_anchor_on
    }
    SECTOR_ROTATION_ITEM {
      long run_id FK
      string sector_key
      int rank
      decimal score
      json coverage
    }
    SECTOR_ROTATION_OUTCOME {
      long run_id FK
      int horizon_sessions
      decimal return_value
      decimal benchmark_return
    }

    COMPANY_RESEARCH_SUMMARY ||--o{ NOTIFICATION_CANDIDATE : "evaluated as"
    NOTIFICATION_CANDIDATE ||--o{ NOTIFICATION_OUTBOX : "transition enqueues"
    OBJECT_ARTIFACT ||--o| ACTIVE_OBJECT_POINTER : "activated by"
    SECTOR_ROTATION_RUN ||--|{ SECTOR_ROTATION_ITEM : "contains exactly 11"
    SECTOR_ROTATION_RUN ||--o{ SECTOR_ROTATION_OUTCOME : "matures into"
```

이 ERD는 포트폴리오 설명을 위한 개념 축약이다. 실제 DDL은 Flyway V1–V22와 schema ownership 문서가 기준이다.

## 11. 장애 대응 폐쇄 루프

```mermaid
flowchart LR
    OBS["Metric · Log · Trace · Integrity Query"] --> INC["Incident fingerprint"]
    INC --> ROOT["Root cause와 오염 범위"]
    ROOT --> FIX["코드·설정 수정"]
    FIX --> TEST["재현 test / DB constraint"]
    TEST --> MON["운영 탐지·reminder"]
    MON --> DOC["ADR · PDR · Runbook"]
    DOC --> DEP["scope-aware deploy + rollback unit"]
    DEP --> OBS
```

완료 기준은 “오류 로그가 잠시 사라짐”이 아니라 **원인 수정, 자동 재현, 운영 재탐지, 롤백 가능성**이다.
