#!/usr/bin/env python3
"""Daily read-only audit of MacroSquare logs, traces, metrics and persisted state.

The audit deliberately never edits application data, source code or containers.  It
stores evidence and sends a bounded Telegram report; remediation remains an explicit
reviewed operation.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import os
import pathlib
import re
import statistics
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_REPORT_DIR = ROOT / ".ops-audit" / "reports"
UTC = dt.timezone.utc
OPTIONAL_COLLECTION_GAPS = {
    "SENTIMENT": frozenset({"NAAIM_EXPOSURE"}),
}
CLIENT_DISCONNECT_DESCRIPTIONS = frozenset({
    "broken pipe",
    "connection reset by peer",
})
COLLECTION_STALE_AFTER_SECONDS = {
    "YAHOO": 30 * 60,
    "KRX": 90 * 60,
    "FEAR_GREED": 3 * 3600,
    "FRED": 12 * 3600,
    "SENTIMENT": 12 * 3600,
    "STABLECOIN": 12 * 3600,
}
CANDIDATE_SCAN_STALLED_AFTER_SECONDS = 20 * 60


def now() -> dt.datetime:
    return dt.datetime.now(UTC)


def run(command: list[str], timeout: int = 120, check: bool = True) -> str:
    result = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    if check and result.returncode != 0:
        message = result.stderr.strip() or result.stdout.strip() or "command failed"
        raise RuntimeError(f"{command[0]} exited {result.returncode}: {message[:500]}")
    return result.stdout


def http_json(url: str, params: dict[str, str] | None = None, timeout: int = 20) -> Any:
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def prometheus(query: str) -> list[dict[str, Any]]:
    payload = http_json("http://127.0.0.1:5902/api/v1/query", {"query": query})
    if payload.get("status") != "success":
        raise RuntimeError(f"Prometheus query failed: {payload}")
    return payload.get("data", {}).get("result", [])


def scalar(result: list[dict[str, Any]], fallback: float = 0.0) -> float:
    if not result:
        return fallback
    try:
        value = float(result[0]["value"][1])
        return value if math.isfinite(value) else fallback
    except (KeyError, IndexError, TypeError, ValueError):
        return fallback


def loki_count(expression: str, hours: int) -> int:
    range_selector = f"[{hours}h]"
    query = f"sum(count_over_time({expression} {range_selector})) or vector(0)"
    payload = http_json("http://127.0.0.1:5903/loki/api/v1/query", {"query": query})
    return max(0, int(round(scalar(payload.get("data", {}).get("result", [])))))


def loki_lines(contains: str, hours: int, limit: int = 2000) -> list[str]:
    query = '{stack="macrosquare-host"} |= ' + json.dumps(contains) + ' !~ "caller=(metrics.go|engine.go)"'
    payload = http_json(
        "http://127.0.0.1:5903/loki/api/v1/query_range",
        {"query": query, "since": f"{hours}h", "limit": str(limit), "direction": "forward"},
    )
    lines: list[tuple[int, str]] = []
    for stream in payload.get("data", {}).get("result", []):
        for timestamp, line in stream.get("values", []):
            lines.append((int(timestamp), line))
    return [line for _, line in sorted(lines)]


def psql_json(sql: str) -> Any:
    encoded = "select coalesce(json_agg(a), '[]'::json) from (" + sql + ") a"
    raw = run([
        "docker", "compose", "exec", "-T", "postgres", "psql",
        "-U", "macrosquare", "-d", "macrosquare", "-Atc", encoded,
    ])
    return json.loads(raw.strip() or "[]")


def read_env() -> dict[str, str]:
    values: dict[str, str] = {}
    path = ROOT / ".env"
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def scheduler_durations(
    lines: list[str],
    observed_at: dt.datetime | None = None,
    stalled_after_seconds: int = CANDIDATE_SCAN_STALLED_AFTER_SECONDS,
    process_started_at: dt.datetime | None = None,
) -> dict[str, Any]:
    """Pair candidate-scan lifecycle logs without treating live work as failure.

    Candidate refreshes can legitimately run for several minutes.  The previous
    audit marked every unmatched ``started`` line as CRITICAL, including a scan
    that had started only seconds before the audit.  A start is now considered
    stalled only after the bounded 20 minute execution window.  Explicit
    ``failed`` events remain immediate failures, while lock-contention ``skipped``
    events are terminal but healthy.
    """
    observed_at = observed_at or now()
    if observed_at.tzinfo is None:
        observed_at = observed_at.replace(tzinfo=UTC)
    if process_started_at is not None and process_started_at.tzinfo is None:
        process_started_at = process_started_at.replace(tzinfo=UTC)
    if stalled_after_seconds <= 0:
        raise ValueError("stalled_after_seconds must be positive")
    timestamp_pattern = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)")
    trigger_pattern = re.compile(r"trigger=([^,)]+)")
    active: dict[str, dt.datetime] = {}
    durations: list[dict[str, Any]] = []
    failures: list[dict[str, Any]] = []
    for line in lines:
        timestamp_match = timestamp_pattern.search(line)
        trigger_match = trigger_pattern.search(line)
        if not timestamp_match or not trigger_match:
            continue
        timestamp = dt.datetime.fromisoformat(timestamp_match.group(1).replace("Z", "+00:00"))
        trigger = trigger_match.group(1)
        if " scan started " in line:
            active[trigger] = timestamp
        elif " scan completed " in line and trigger in active:
            started = active.pop(trigger)
            durations.append({
                "trigger": trigger,
                "startedAt": started.isoformat(),
                "completedAt": timestamp.isoformat(),
                "durationSeconds": round((timestamp - started).total_seconds(), 3),
            })
        elif " scan failed " in line:
            failures.append({"trigger": trigger, "failedAt": timestamp.isoformat()})
            active.pop(trigger, None)
        elif " scan skipped " in line:
            active.pop(trigger, None)

    active_starts: list[str] = []
    stalled_starts: list[str] = []
    interrupted_starts: list[str] = []
    for started in active.values():
        # Loki retains the previous container's lifecycle logs across a rolling
        # deployment. An unmatched start from a process that no longer exists
        # was interrupted by cutover; it is not a task stalled in the current
        # healthy JVM. The new process schedules its own bounded recalculation.
        if process_started_at is not None and started < process_started_at:
            interrupted_starts.append(started.isoformat())
            continue
        target = (observed_at - started).total_seconds()
        (stalled_starts if target > stalled_after_seconds else active_starts).append(started.isoformat())
    return {
        "completed": durations,
        "activeStarts": active_starts,
        "stalledStarts": stalled_starts,
        # Kept for report consumers; unlike the old implementation this contains
        # only genuinely stale unmatched starts rather than currently active work.
        "unmatchedStarts": stalled_starts,
        "interruptedByRestart": interrupted_starts,
        "failures": failures,
    }


def provider_heavy_scheduler_overlaps(lines: list[str]) -> list[dict[str, str]]:
    """Detect concurrent full-universe jobs sharing provider and CPU budgets.

    Per-job locks stop duplicate executions of the same task, but they do not
    stop company summary, analyst history and candidate scans from creating a
    cross-job startup stampede. Explicit lifecycle logs make that recurrence
    detectable without treating high CPU alone as proof.
    """
    timestamp_pattern = re.compile(r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)")
    jobs = {
        "company-summary": (
            "Company research summary refresh started",
            ("Company research summaries refreshed", "Company research summary refresh was partial",
             "Company research summary refresh failed", "owned by another instance"),
        ),
        "analyst-history": (
            "Analyst history run started",
            ("Analyst history completed", "Analyst history run failed", "skipped because another instance owns"),
        ),
        "candidate-scan": (
            "investment entry notification scan started",
            ("investment entry notification scan completed", "investment entry notification scan failed",
             "investment entry notification scan skipped"),
        ),
    }
    events: list[tuple[dt.datetime, str]] = []
    for line in lines:
        match = timestamp_pattern.search(line)
        if match:
            events.append((dt.datetime.fromisoformat(match.group(1).replace("Z", "+00:00")), line))
    active: dict[str, dt.datetime] = {}
    overlaps: list[dict[str, str]] = []
    for timestamp, line in sorted(events):
        for job, (started_text, terminal_texts) in jobs.items():
            if started_text in line:
                for active_job, active_since in active.items():
                    if active_job != job:
                        overlaps.append({
                            "startedJob": job,
                            "activeJob": active_job,
                            "startedAt": timestamp.isoformat(),
                            "activeSince": active_since.isoformat(),
                        })
                active[job] = timestamp
                break
            if any(text in line for text in terminal_texts):
                active.pop(job, None)
                break
    return overlaps


def is_client_disconnect_span(tags: dict[str, Any]) -> bool:
    """Return true only for a successful response abandoned by its caller.

    A client closing a socket after the application produced a 2xx response is
    operational evidence worth retaining, but it is not a server-side calculation
    or collection failure.  Exact description matching prevents real IO failures
    from being hidden behind a broad IOException suppression.
    """
    try:
        status = int(tags.get("status", 0))
    except (TypeError, ValueError):
        return False
    description = str(tags.get("otel.status_description", "")).strip().lower()
    return 200 <= status < 300 and description in CLIENT_DISCONNECT_DESCRIPTIONS


def trace_summary(hours: int) -> dict[str, Any]:
    payload = http_json(
        "http://127.0.0.1:16687/api/traces",
        {"service": "macrosquare-server-spring", "limit": "500", "lookback": f"{hours}h"},
        timeout=30,
    )
    traces = payload.get("data") or []
    durations: list[float] = []
    error_spans = 0
    client_disconnect_spans = 0
    for trace in traces:
        for span in trace.get("spans", []):
            duration_ms = float(span.get("duration", 0)) / 1000.0
            if duration_ms >= 0:
                durations.append(duration_ms)
            tags = {tag.get("key"): tag.get("value") for tag in span.get("tags", [])}
            if tags.get("otel.status_code") == "ERROR" or tags.get("error") is True:
                if is_client_disconnect_span(tags):
                    client_disconnect_spans += 1
                else:
                    error_spans += 1
    durations.sort()
    p95 = durations[min(len(durations) - 1, math.ceil(len(durations) * 0.95) - 1)] if durations else 0.0
    return {
        "sampledTraces": len(traces),
        "spans": len(durations),
        "errorSpans": error_spans,
        "clientDisconnectSpans": client_disconnect_spans,
        "p95SpanMs": round(p95, 3),
        "maxSpanMs": round(max(durations, default=0.0), 3),
    }


def collection_failure_keys(value: Any) -> frozenset[str]:
    if value is None:
        return frozenset()
    if isinstance(value, list):
        return frozenset(str(item).strip() for item in value if str(item).strip())
    return frozenset(part for part in re.split(r"[,;\s]+", str(value).strip()) if part)


def is_optional_collection_gap(collection: dict[str, Any]) -> bool:
    """Recognize a bounded optional-source gap without masking new failures."""
    source = str(collection.get("source", ""))
    allowed = OPTIONAL_COLLECTION_GAPS.get(source)
    failures = collection_failure_keys(collection.get("failure_keys"))
    return (
        collection.get("status") == "DEGRADED"
        and allowed is not None
        and bool(failures)
        and failures.issubset(allowed)
        and int(collection.get("collected_count") or 0) > 0
        and int(collection.get("persisted_count") or 0) > 0
        and int(collection.get("persisted_count") or 0)
            == int(collection.get("collected_count") or 0)
    )


def is_collection_stale(collection: dict[str, Any]) -> bool:
    """Apply the same source-specific freshness contract as the live monitor."""
    source = str(collection.get("source", ""))
    threshold = COLLECTION_STALE_AFTER_SECONDS.get(source, 12 * 3600)
    return int(collection.get("age_seconds") or 0) > threshold


def add_finding(findings: list[dict[str, str]], severity: str, code: str, message: str) -> None:
    findings.append({"severity": severity, "code": code, "message": message})


def severity(findings: list[dict[str, str]]) -> str:
    levels = {"OK": 0, "INFO": 0, "WARNING": 1, "CRITICAL": 2}
    return max((item["severity"] for item in findings), key=lambda value: levels[value], default="OK")


def http_metric_queries(hours: int) -> dict[str, str]:
    """Keep user/API failures separate from deployment readiness probes.

    Actuator health legitimately returns 503 while a replacement container is
    starting. Counting those probes as user-facing API errors made every healthy
    rolling deployment look like a CRITICAL incident for the rest of the audit
    window. The probe count remains visible evidence and runtime health is checked
    independently; only non-Actuator traffic drives the application 5xx rate.
    """
    if hours < 1:
        raise ValueError("hours must be positive")
    return {
        "requests": (
            f'sum(increase(http_server_requests_seconds_count{{uri!~"/actuator.*"}}[{hours}h])) '
            "or vector(0)"
        ),
        "errors": (
            f'sum(increase(http_server_requests_seconds_count{{status=~"5..",uri!~"/actuator.*"}}[{hours}h])) '
            "or vector(0)"
        ),
        "healthProbeErrors": (
            f'sum(increase(http_server_requests_seconds_count{{status=~"5..",uri=~"/actuator/health.*"}}[{hours}h])) '
            "or vector(0)"
        ),
    }


def telegram_text(report: dict[str, Any]) -> str:
    telemetry = report["telemetry"]
    database = report["database"]
    findings = report["findings"]
    icon = {"OK": "✅", "WARNING": "⚠️", "CRITICAL": "🚨"}[report["severity"]]
    lines = [
        f"{icon} MacroSquare 일일 운영 전수검사: {report['severity']}",
        f"기간: 최근 {report['lookbackHours']}시간 · {report['generatedAt']}",
        "",
        f"HTTP: 앱 {round(telemetry['httpRequests'])}건 · 앱 5xx {round(telemetry['http5xx'])}건 "
        f"({telemetry['http5xxRatePct']:.3f}%) · readiness 5xx {round(telemetry['healthProbe5xx'])}건",
        f"로그: 앱 ERROR {telemetry['errorLogs']} · 앱 WARN {telemetry['warnLogs']} "
        f"· 인프라 ERROR {telemetry['infrastructureErrorLogs']} · degraded {telemetry['degradedOperations']:.0f}",
        f"트레이스: {telemetry['traces']['sampledTraces']}개 표본 · 오류 span {telemetry['traces']['errorSpans']} "
        f"· p95 {telemetry['traces']['p95SpanMs']:.0f}ms",
        f"회사: {database['company']['total']}개 · 선별 E2E {database['companySelection']['status']} "
        f"· API smoke {report['smoke']['passed']}/43",
        f"컨테이너: {report['runtime']['health']} · restart {report['runtime']['restarts']} · OOM {report['runtime']['oomKilled']}",
    ]
    if findings:
        lines += ["", "주요 탐지:"]
        for finding in findings[:8]:
            lines.append(f"- [{finding['severity']}] {finding['message']}")
    else:
        lines += ["", "탐지된 운영 이상 없음"]
    lines += ["", "자동 코드 수정·배포는 수행하지 않았습니다."]
    return "\n".join(lines)[:4000]


def send_telegram(text: str, env: dict[str, str]) -> bool:
    token = env.get("TELEGRAM_BOT_TOKEN", "")
    chat_id = env.get("TELEGRAM_CHAT_ID", "")
    if not token or not chat_id:
        return False
    body = urllib.parse.urlencode({"chat_id": chat_id, "text": text}).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendMessage",
        data=body,
        method="POST",
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        result = json.loads(response.read().decode("utf-8"))
    return bool(result.get("ok"))


def write_report(report: dict[str, Any], report_dir: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path]:
    report_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(report_dir, 0o700)
    stamp = report["generatedAt"].replace(":", "").replace("-", "")
    json_path = report_dir / f"ops-audit-{stamp}.json"
    text_path = report_dir / f"ops-audit-{stamp}.txt"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    text_path.write_text(telegram_text(report) + "\n", encoding="utf-8")
    os.chmod(json_path, 0o600)
    os.chmod(text_path, 0o600)
    cutoff = now() - dt.timedelta(days=30)
    for path in report_dir.glob("ops-audit-*"):
        if dt.datetime.fromtimestamp(path.stat().st_mtime, UTC) < cutoff:
            path.unlink(missing_ok=True)
    return json_path, text_path


def audit(hours: int) -> dict[str, Any]:
    generated_at = now()
    findings: list[dict[str, str]] = []

    health_started = now()
    health = http_json("http://127.0.0.1:5846/actuator/health", timeout=5)
    health_ms = (now() - health_started).total_seconds() * 1000
    state = json.loads(run(["docker", "inspect", "macrosquare-server", "--format", "{{json .State}}"]))
    memory_events = {}
    for line in run(["docker", "exec", "macrosquare-server", "cat", "/sys/fs/cgroup/memory.events"]).splitlines():
        key, value = line.split(maxsplit=1)
        memory_events[key] = int(value)
    runtime = {
        "health": state.get("Health", {}).get("Status", state.get("Status", "unknown")),
        "restarts": int(run(["docker", "inspect", "macrosquare-server", "--format", "{{.RestartCount}}"]).strip()),
        "oomKilled": bool(state.get("OOMKilled")),
        "healthLatencyMs": round(health_ms, 3),
        "memoryEvents": memory_events,
    }
    process_started_at = dt.datetime.fromisoformat(str(state["StartedAt"]).replace("Z", "+00:00"))
    if health.get("status") != "UP" or runtime["health"] != "healthy":
        add_finding(findings, "CRITICAL", "runtime-health", "서버 health가 UP/healthy가 아닙니다.")
    if runtime["restarts"] > 0 or runtime["oomKilled"] or memory_events.get("oom_kill", 0) > 0:
        add_finding(findings, "CRITICAL", "runtime-restart-oom", "재시작 또는 OOM 흔적이 있습니다.")
    if health_ms > 2000:
        add_finding(findings, "WARNING", "health-latency", f"health 응답이 {health_ms:.0f}ms로 느립니다.")

    http_queries = http_metric_queries(hours)
    request_count = scalar(prometheus(http_queries["requests"]))
    server_errors = scalar(prometheus(http_queries["errors"]))
    health_probe_errors = scalar(prometheus(http_queries["healthProbeErrors"]))
    error_rate = server_errors / request_count * 100 if request_count > 0 else 0.0
    degraded = scalar(prometheus(f"sum(increase(macrosquare_degraded_operations_total[{hours}h])) or vector(0)"))
    slow_endpoints = prometheus(
        f'topk(10,max_over_time(http_server_requests_seconds_max{{uri!~"/actuator.*"}}[{hours}h]))')
    slow = sorted(({
        "uri": item.get("metric", {}).get("uri", "unknown"),
        "status": item.get("metric", {}).get("status", ""),
        "seconds": round(float(item.get("value", [0, 0])[1]), 3),
    } for item in slow_endpoints), key=lambda item: item["seconds"], reverse=True)
    # Split application evidence from infrastructure noise. PDFBox emits one WARN
    # per unmapped glyph and Loki logs benign query-cancellation EOFs; retaining
    # those in raw Loki while excluding them from actionable counts prevents alert
    # fatigue without hiding the underlying evidence.
    error_expression = ('{stack="macrosquare-host"} |= "macrosquare-server-spring" '
                        '|~ " ERROR " !~ "query="')
    warn_expression = ('{stack="macrosquare-host"} |= "macrosquare-server-spring" '
                       '|~ " WARN " !~ "PDSimpleFont.*No Unicode mapping" !~ "query="')
    infrastructure_error_expression = ('{stack="macrosquare-host"} '
                                       '|~ "(level=error|\\\"level\\\":\\\"error\\\")" '
                                       '!~ "scheduler_processor.go" !~ "query="')
    suppressed_warn_expression = ('{stack="macrosquare-host"} |= "macrosquare-server-spring" '
                                  '|~ " WARN " |~ "PDSimpleFont.*No Unicode mapping"')
    error_logs = loki_count(error_expression, hours)
    warn_logs = loki_count(warn_expression, hours)
    infrastructure_error_logs = loki_count(infrastructure_error_expression, hours)
    suppressed_warn_logs = loki_count(suppressed_warn_expression, hours)
    candidate_lines = loki_lines("investment entry notification scan", hours)
    schedulers = scheduler_durations(candidate_lines, process_started_at=process_started_at)
    provider_overlaps = provider_heavy_scheduler_overlaps(
        loki_lines("Company research", hours)
        + loki_lines("Analyst history", hours)
        + candidate_lines
    )
    traces = trace_summary(hours)
    telemetry = {
        "httpRequests": request_count,
        "http5xx": server_errors,
        "http5xxRatePct": round(error_rate, 5),
        "healthProbe5xx": health_probe_errors,
        "degradedOperations": degraded,
        "errorLogs": error_logs,
        "warnLogs": warn_logs,
        "infrastructureErrorLogs": infrastructure_error_logs,
        "suppressedKnownWarnLogs": suppressed_warn_logs,
        "slowEndpoints": slow,
        "candidateScheduler": schedulers,
        "providerHeavySchedulerOverlaps": provider_overlaps,
        "traces": traces,
    }
    if error_rate >= 1.0:
        add_finding(findings, "CRITICAL", "http-5xx-rate", f"HTTP 5xx 비율이 {error_rate:.3f}%입니다.")
    elif error_rate >= 0.1:
        add_finding(findings, "WARNING", "http-5xx-rate", f"HTTP 5xx 비율이 {error_rate:.3f}%입니다.")
    if error_logs > 20:
        add_finding(findings, "CRITICAL", "error-logs", f"ERROR 로그가 {error_logs}건입니다.")
    elif error_logs > 0:
        add_finding(findings, "WARNING", "error-logs", f"ERROR 로그가 {error_logs}건입니다.")
    if infrastructure_error_logs > 5:
        add_finding(findings, "WARNING", "infrastructure-error-logs",
                    f"관측 인프라 ERROR 로그가 {infrastructure_error_logs}건입니다.")
    if degraded >= 10:
        add_finding(findings, "WARNING", "degraded-operations", f"fallback/degraded 동작이 {degraded:.0f}건입니다.")
    if slow and slow[0]["seconds"] >= 30:
        add_finding(findings, "CRITICAL", "endpoint-latency", f"최대 API 지연이 {slow[0]['seconds']:.1f}초입니다.")
    elif slow and slow[0]["seconds"] >= 5:
        add_finding(findings, "WARNING", "endpoint-latency", f"최대 API 지연이 {slow[0]['seconds']:.1f}초입니다.")
    for completed in schedulers["completed"]:
        if completed["durationSeconds"] > 300:
            add_finding(findings, "WARNING", "candidate-scan-duration",
                        f"후보 스캔이 {completed['durationSeconds']:.0f}초 걸렸습니다.")
    if schedulers["failures"]:
        add_finding(findings, "CRITICAL", "candidate-scan-failed", "후보 스캔 실패 로그가 있습니다.")
    if schedulers["stalledStarts"]:
        add_finding(findings, "CRITICAL", "candidate-scan-incomplete", "20분 넘게 완료되지 않은 후보 스캔이 있습니다.")
    if provider_overlaps:
        add_finding(findings, "WARNING", "provider-heavy-scheduler-overlap",
                    f"기업 전수 provider 작업 중첩이 {len(provider_overlaps)}건 탐지됐습니다.")
    if traces["errorSpans"] > 0:
        add_finding(findings, "WARNING", "trace-errors", f"오류 span이 {traces['errorSpans']}건 탐지됐습니다.")

    collections = psql_json("""
        select source,status,completed_at,collected_count,persisted_count,failure_keys,failure_type,
               extract(epoch from (clock_timestamp()-completed_at))::bigint as age_seconds
        from market.collection_status order by source
    """)
    company = psql_json("""
        select count(*)::int total,
               count(*) filter(where
                   total_score not between 0 and 100
                   or growth_score not between 0 and 100
                   or quality_score not between 0 and 100
                   or valuation_score not between 0 and 100
                   or balance_sheet_score not between 0 and 100
                   or buy_score not between 0 and 100
                   or appeal_score not between 0 and 100
                   or crowding_score not between 0 and 100
                   or price_bottom_score not between 0 and 100
                   or volume_confirmation_score not between 0 and 100
                   or failure_risk_score not between 0 and 100
                   or confirmed_bottom_score not between 0 and 100
               )::int invalid_scores,
               count(*) filter(where fundamentals_status <> 'CURRENT'
                   and (total_score is not null or buy_score is not null))::int stale_scored,
               count(*) filter(where calculation_version=6)::int current_calculation_count,
               count(*) filter(where total_score is not null)::int comparable_score_count,
               count(*) filter(where price_bottom_score is not null)::int price_signal_count,
               count(*) filter(where
                   num_nonnulls(total_score,growth_score,quality_score,valuation_score,
                       balance_sheet_score,buy_score,appeal_score,crowding_score) not in (0,8)
                   or ((total_score is null) <> (buy_label is null))
                   or (total_score is not null and (
                       fundamentals_status<>'CURRENT' or not valuation_eligible
                   )))::int incomplete_score_bundles,
               count(*) filter(where execution_action in ('BUY','STRONG BUY') and (
                   fundamentals_status<>'CURRENT' or not valuation_eligible
                   or total_score is null or growth_score is null or quality_score is null
                   or valuation_score is null or balance_sheet_score is null or buy_score is null
                   or price_bottom_score is null or volume_confirmation_score is null
                   or failure_risk_score is null or confirmed_bottom_score is null
                   or confirmed_bottom_state is null
               ))::int buy_without_evidence,
               count(*) filter(where num_nonnulls(
                   price_bottom_score,volume_confirmation_score,failure_risk_score,
                   confirmed_bottom_score,confirmed_bottom_state
               ) not in (0,5))::int incomplete_price_signals,
               count(*) filter(where fundamentals_status in ('UNAVAILABLE','UNKNOWN','PENDING'))::int unavailable_count,
               count(*) filter(where ticker in ('EA','CTRA','MMC'))::int retired_or_alias_count,
               count(*) filter(where ticker='MRSH')::int canonical_mrsh_count,
               count(*) filter(where fundamentals_as_of > current_date
                   or latest_periodic_report_date > current_date
                   or latest_periodic_filing_date > current_date
                   or updated_at > clock_timestamp() + interval '5 minutes')::int future_dated,
               count(*) filter(where fundamentals_status='CURRENT')::int current_count,
               count(*) filter(where fundamentals_status='LAGGING')::int lagging_count,
               count(*) filter(where fundamentals_status='INCOMPLETE')::int incomplete_count,
               extract(epoch from (clock_timestamp()-min(updated_at)))::bigint summary_age_seconds
        from company.research_summary
    """)[0]
    market_integrity = psql_json("""
        select count(*)::int total,
               count(*) filter(where observed_on > current_date
                   or collected_at > clock_timestamp() + interval '5 minutes')::int future_dated,
               count(*) filter(where value in (
                   'NaN'::double precision,
                   'Infinity'::double precision,
                   '-Infinity'::double precision
               ))::int non_finite,
               (count(*) - count(distinct (source, series_key, observed_on)))::int duplicates
        from market.observation
    """)[0]
    analyst_integrity = psql_json("""
        with latest as (
            select distinct on (ticker) ticker,analyst_score,upside_pct
            from company.analyst_snapshot order by ticker,observed_on desc
        )
        select count(*)::int total,
               count(*) filter(where observed_on > current_date
                   or collected_at > clock_timestamp() + interval '5 minutes')::int future_dated,
               count(*) filter(where analyst_score not between -2 and 2
                   or analyst_score in (
                       'NaN'::double precision,
                       'Infinity'::double precision,
                       '-Infinity'::double precision
                   )
                   or upside_pct < -100 or upside_pct > 1000
                   or upside_pct in (
                       'NaN'::double precision,
                       'Infinity'::double precision,
                       '-Infinity'::double precision
                   ))::int invalid_values,
               (count(*) - count(distinct (ticker, observed_on)))::int duplicates,
               (select count(*) from company.analyst_series_state)::int series_count,
               (select count(*) from company.analyst_series_state where
                   updated_at > clock_timestamp()+interval '5 minutes'
                   or updated_at < clock_timestamp()-interval '2 hours')::int stale_series,
               (select count(*) from latest where
                   analyst_score is null and upside_pct is null)::int empty_latest
        from company.analyst_snapshot
    """)[0]
    institutional = psql_json("""
        with filing_groups as (
            select m.manager_id, f.report_period, count(*)::int position_count,
                   percentile_cont(0.9) within group(
                       order by h.value_usd / nullif(h.shares, 0)
                   ) as implied_price_p90,
                   count(*) filter(where h.value_usd / nullif(h.shares, 0) < 1)::int below_one
            from institutional.manager m
            join institutional.filing f on f.manager_cik = m.cik
            join institutional.holding h on h.accession_number = f.accession_number
            group by m.manager_id, f.report_period
        )
        select (select count(*) from institutional.manager)::int managers,
               (select count(*) from institutional.filing)::int filings,
               (select count(*) from institutional.holding)::int holdings,
               (select count(*) from institutional.holding
                   where value_usd <= 0 or shares <= 0
                      or value_usd in (
                          'NaN'::double precision,
                          'Infinity'::double precision,
                          '-Infinity'::double precision
                      )
                      or shares in (
                          'NaN'::double precision,
                          'Infinity'::double precision,
                          '-Infinity'::double precision
                      ))::int invalid_holdings,
               (select count(*) from institutional.filing
                   where filed_on > current_date or report_period > current_date
                      or report_period > filed_on)::int invalid_filing_dates,
               (select count(*) from filing_groups
                   where position_count >= 5 and implied_price_p90 < 1
                      and below_one * 10 >= position_count * 9)::int suspicious_unit_groups
    """)[0]
    notification = psql_json("""
        select count(*) filter(where status='PENDING')::int pending,
               count(*) filter(where status='RETRY')::int retry,
               count(*) filter(where status='DEAD')::int dead,
               count(*) filter(where status='IN_FLIGHT')::int in_flight,
               count(*) filter(where
                   (status='IN_FLIGHT' and leased_until<=clock_timestamp())
                   or (status='PENDING' and created_at<clock_timestamp()-interval '10 minutes')
               )::int stuck
        from notification.outbox
    """)[0]
    candidate_drift = psql_json("""
        select count(*)::int drift_count
        from notification.candidate_snapshot c
        left join company.research_summary r on c.kind='COMPANY' and r.ticker=c.symbol
        where c.kind='COMPANY' and (
            r.ticker is null or r.fundamentals_status<>'CURRENT'
            or r.total_score is distinct from c.total_score
            or r.buy_score is distinct from c.buy_score
            or r.execution_action is distinct from c.action
            or r.confirmed_bottom_state is distinct from c.bottom_state
            or r.confirmed_bottom_score is distinct from c.bottom_score)
    """)[0]["drift_count"]
    object_storage = psql_json("""
        select (select count(*) from storage.object_pointer)::int pointers,
               (select count(*) from storage.object_artifact)::int artifacts,
               (select count(*) from storage.object_pointer p left join storage.object_artifact a on a.id=p.artifact_id where a.id is null)::int dangling
    """)[0]
    company_selection = json.loads(run([
        sys.executable, "scripts/audit-company-selection-e2e.py", "--no-fail",
    ], timeout=180))
    database = {
        "collections": collections,
        "company": company,
        "marketIntegrity": market_integrity,
        "analystIntegrity": analyst_integrity,
        "institutional": institutional,
        "notification": notification,
        "candidateDrift": candidate_drift,
        "objectStorage": object_storage,
        "companySelection": company_selection,
    }
    for collection in collections:
        if (collection["status"] != "SUCCESS" or collection["failure_keys"]
                or collection["failure_type"]
                or collection["persisted_count"] != collection["collected_count"]):
            if is_optional_collection_gap(collection):
                add_finding(findings, "WARNING", "collection-optional-gap",
                            f"{collection['source']} 선택 소스 공백: {collection['failure_keys']}"
                            " (의사결정에서는 제외됨).")
            else:
                add_finding(findings, "CRITICAL", "collection-failure",
                            f"{collection['source']} 수집 상태가 {collection['status']}입니다."
                            f" persisted={collection['persisted_count']}/{collection['collected_count']}"
                            f" failureType={collection['failure_type']}"
                            f" failureKeys={collection['failure_keys']}")
        elif is_collection_stale(collection):
            add_finding(findings, "WARNING", "collection-stale",
                        f"{collection['source']} 수집이 원천별 허용 주기를 넘겼습니다.")
    if (company["total"] != 277 or company["invalid_scores"] > 0
            or company["current_calculation_count"] != 277
            or company["comparable_score_count"] < 220
            or company["price_signal_count"] != 277
            or company["stale_scored"] > 0 or company["future_dated"] > 0
            or company["incomplete_score_bundles"] > 0
            or company["buy_without_evidence"] > 0
            or company["incomplete_price_signals"] > 0
            or company["unavailable_count"] > 0
            or company["retired_or_alias_count"] > 0
            or company["canonical_mrsh_count"] != 1):
        add_finding(findings, "CRITICAL", "company-summary-integrity",
                    f"기업 요약 total={company['total']}, invalid={company['invalid_scores']}, "
                    f"currentV6={company['current_calculation_count']}, "
                    f"comparableScores={company['comparable_score_count']}, "
                    f"priceSignals={company['price_signal_count']}, "
                    f"partial={company['incomplete_score_bundles']}, "
                    f"buyWithoutEvidence={company['buy_without_evidence']}, "
                    f"partialSignals={company['incomplete_price_signals']}, "
                    f"unavailable={company['unavailable_count']}, "
                    f"staleScored={company['stale_scored']}, future={company['future_dated']}입니다.")
    if company["summary_age_seconds"] > 2 * 3600:
        add_finding(findings, "WARNING", "company-summary-stale", "기업 요약이 2시간 이상 갱신되지 않았습니다.")
    if company_selection.get("status") != "OK" or company_selection.get("problemCount", 1) != 0:
        add_finding(
            findings, "CRITICAL", "company-selection-e2e",
            f"기업 선별 API/DB/섹터·테마 E2E가 {company_selection.get('status', 'UNKNOWN')}이며 "
            f"문제 {company_selection.get('problemCount', 'unknown')}건입니다.",
        )
    if any(market_integrity[key] > 0 for key in ("future_dated", "non_finite", "duplicates")):
        add_finding(findings, "CRITICAL", "market-observation-integrity",
                    f"시장 관측 future={market_integrity['future_dated']}, "
                    f"nonFinite={market_integrity['non_finite']}, "
                    f"duplicates={market_integrity['duplicates']}입니다.")
    if (analyst_integrity["series_count"] != 277
            or analyst_integrity["empty_latest"] > 0
            or any(analyst_integrity[key] > 0 for key in (
                "future_dated", "invalid_values", "duplicates", "stale_series"))):
        add_finding(findings, "CRITICAL", "analyst-history-integrity",
                    f"컨센서스 이력 future={analyst_integrity['future_dated']}, "
                    f"invalid={analyst_integrity['invalid_values']}, "
                    f"duplicates={analyst_integrity['duplicates']}, "
                    f"series={analyst_integrity['series_count']}, "
                    f"staleSeries={analyst_integrity['stale_series']}, "
                    f"emptyLatest={analyst_integrity['empty_latest']}입니다.")
    if any(institutional[key] > 0 for key in (
            "invalid_holdings", "invalid_filing_dates", "suspicious_unit_groups")):
        add_finding(findings, "CRITICAL", "institutional-data-integrity",
                    f"13F invalidHoldings={institutional['invalid_holdings']}, "
                    f"invalidDates={institutional['invalid_filing_dates']}, "
                    f"unitAnomalies={institutional['suspicious_unit_groups']}입니다.")
    if notification["dead"] > 0 or notification["retry"] > 0 or notification["stuck"] > 0:
        add_finding(findings, "CRITICAL", "notification-outbox",
                    f"텔레그램 outbox retry={notification['retry']}, dead={notification['dead']}, "
                    f"stuck={notification['stuck']}입니다.")
    if candidate_drift > 0:
        add_finding(findings, "CRITICAL", "candidate-state-drift",
                    f"알림 후보와 최신 기업 점수 불일치가 {candidate_drift}건입니다.")
    if object_storage["dangling"] > 0:
        add_finding(findings, "CRITICAL", "object-storage-dangling",
                    f"오브젝트 포인터 dangling={object_storage['dangling']}입니다.")

    smoke_raw = run([
        "python3", "server-spring/migration/tools/smoke-production-api.py",
        "--base-url", "http://127.0.0.1:5846",
    ], timeout=180)
    smoke = json.loads(smoke_raw)
    if smoke.get("passed") != 43:
        add_finding(findings, "CRITICAL", "api-smoke", f"API smoke가 {smoke.get('passed', 0)}/43입니다.")
    routes: dict[str, int] = {}
    for route in ["/", "/research/sectors", "/research/companies?page=2", "/company/NVDA", "/research/crypto"]:
        try:
            request = urllib.request.Request("http://127.0.0.1:5847" + route, method="GET")
            with urllib.request.urlopen(request, timeout=30) as response:
                routes[route] = response.status
        except urllib.error.HTTPError as error:
            routes[route] = error.code
        if routes[route] != 200:
            add_finding(findings, "CRITICAL", "frontend-route", f"프론트 {route}가 HTTP {routes[route]}입니다.")

    return {
        "schemaVersion": 1,
        "generatedAt": generated_at.replace(microsecond=0).isoformat(),
        "periodStartedAt": (generated_at - dt.timedelta(hours=hours)).replace(microsecond=0).isoformat(),
        "lookbackHours": hours,
        "severity": severity(findings),
        "runtime": runtime,
        "telemetry": telemetry,
        "database": database,
        "smoke": smoke,
        "frontendRoutes": routes,
        "knownLimitations": [
            "DART_API_KEY 미설정 시 OpenDART 수집은 비활성입니다.",
            "YouTube API 키 미설정 시 YOUTUBE_30D는 점수에서 제외됩니다.",
            "자동 코드 수정·운영 배포는 의도적으로 수행하지 않습니다.",
        ],
        "findings": findings,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lookback-hours", type=int, default=24)
    parser.add_argument("--report-dir", type=pathlib.Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--no-notify", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 1 <= args.lookback_hours <= 168:
        raise SystemExit("lookback hours must be between 1 and 168")
    report = audit(args.lookback_hours)
    json_path, _ = write_report(report, args.report_dir)
    delivered = False
    if not args.no_notify:
        delivered = send_telegram(telegram_text(report), read_env())
    print(json.dumps({
        "severity": report["severity"],
        "findings": len(report["findings"]),
        "report": str(json_path),
        "telegramDelivered": delivered,
    }, ensure_ascii=False))
    return 2 if report["severity"] == "CRITICAL" else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # noqa: BLE001 - operational boundary must persist a clear failure
        failure = {
            "severity": "CRITICAL",
            "auditFailure": type(error).__name__,
            "message": str(error)[:500],
        }
        print(json.dumps(failure, ensure_ascii=False), file=sys.stderr)
        try:
            send_telegram(
                "🚨 MacroSquare 일일 운영 전수검사 자체가 실패했습니다.\n"
                f"오류: {failure['auditFailure']}\n"
                f"내용: {failure['message']}\n"
                "자동 코드 수정·재배포는 수행하지 않았습니다.",
                read_env(),
            )
        except Exception:  # noqa: BLE001 - stderr remains the last-resort channel
            pass
        raise
