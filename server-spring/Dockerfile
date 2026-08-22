FROM maven:3.9.11-eclipse-temurin-21 AS builder

WORKDIR /workspace

COPY pom.xml ./
COPY domain/pom.xml domain/pom.xml
COPY application/pom.xml application/pom.xml
COPY adapters/pom.xml adapters/pom.xml
COPY bootstrap/pom.xml bootstrap/pom.xml
COPY architecture-tests/pom.xml architecture-tests/pom.xml

RUN mvn -B -ntp -pl bootstrap -am dependency:go-offline

COPY domain/src domain/src
COPY application/src application/src
COPY adapters/src adapters/src
COPY bootstrap/src bootstrap/src
COPY architecture-tests/src architecture-tests/src

RUN mvn -B -ntp clean verify

FROM eclipse-temurin:21-jre-alpine AS runner

WORKDIR /app
RUN addgroup -S macrosquare && adduser -S macrosquare -G macrosquare
RUN mkdir -p /app/spring-data \
    && chown -R macrosquare:macrosquare /app/spring-data

COPY --from=builder /workspace/bootstrap/target/macrosquare-server-spring.jar /app/app.jar

USER macrosquare
EXPOSE 5846

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:${PORT:-5846}/actuator/health/readiness >/dev/null 2>&1 || exit 1

# Keep the effective heap near the previous ~576 MiB while the production
# container has 1.25 GiB of cgroup headroom for metaspace, thread stacks,
# TLS/HTTP buffers and observability. A smaller stack is sufficient for the
# non-recursive Spring workload and prevents 70+ platform/carrier threads from
# consuming the remaining native-memory margin.
ENTRYPOINT ["java", "-XX:InitialRAMPercentage=7.0", "-XX:MaxRAMPercentage=45.0", "-Xss512k", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
