package io.macrosquare.integrity.application.service;

import io.macrosquare.integrity.application.model.DataIntegrityCheckResult;
import io.macrosquare.integrity.application.port.in.CheckDataIntegrityUseCase;
import io.macrosquare.integrity.application.port.out.LoadDataIntegrityEvidencePort;
import io.macrosquare.integrity.application.port.out.PublishDataIntegrityIncidentPort;
import io.macrosquare.integrity.domain.DataIntegrityPolicy;
import io.macrosquare.integrity.domain.DataIntegrityReport;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Objects;

public final class CheckDataIntegrityService implements CheckDataIntegrityUseCase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy. M. d. a h:mm:ss");

    private final LoadDataIntegrityEvidencePort evidence;
    private final PublishDataIntegrityIncidentPort incidents;
    private final DataIntegrityPolicy policy;
    private final Clock clock;

    public CheckDataIntegrityService(
            LoadDataIntegrityEvidencePort evidence,
            PublishDataIntegrityIncidentPort incidents,
            DataIntegrityPolicy policy,
            Clock clock
    ) {
        this.evidence = Objects.requireNonNull(evidence);
        this.incidents = Objects.requireNonNull(incidents);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public DataIntegrityCheckResult check(String trigger) {
        var report = policy.evaluate(evidence.load());
        var now = clock.instant();
        var transition = incidents.transition(
                report, alertText(report, trigger), recoveryText(trigger), now);
        return new DataIntegrityCheckResult(report, transition);
    }

    private String alertText(DataIntegrityReport report, String trigger) {
        var lines = new ArrayList<String>();
        lines.add("🚨 MacroSquare 데이터 무결성 재발 감지");
        lines.add("시각: " + TIME.format(clock.instant().atZone(SEOUL)));
        lines.add("점검: " + safe(trigger));
        lines.add("탐지 항목: " + report.violations().size() + "개");
        report.violations().stream().limit(12).forEach(value -> lines.add(
                "- " + value.code() + ": " + value.description()
                        + " (현재 " + display(value.actual()) + " / 기대 " + value.expected() + ')'));
        if (!report.hardCollectionSources().isEmpty()) {
            lines.add("실패 원천: " + String.join(", ", report.hardCollectionSources()));
        }
        lines.add("");
        lines.add("관련 신규 매수 점수·액션은 DB/도메인 가드가 차단합니다.");
        return String.join("\n", lines);
    }

    private String recoveryText(String trigger) {
        return "✅ MacroSquare 데이터 무결성 경보 해소\n"
                + "시각: " + TIME.format(clock.instant().atZone(SEOUL)) + "\n"
                + "점검: " + safe(trigger) + "\n"
                + "이전 재발 불변식이 모두 정상 범위로 복구됐습니다.";
    }

    private static String display(long value) {
        return value == Long.MAX_VALUE ? "없음" : Long.toString(value);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "scheduled";
        var sanitized = value.replaceAll("[^a-zA-Z0-9가-힣._-]", "_");
        return sanitized.substring(0, Math.min(80, sanitized.length()));
    }
}
