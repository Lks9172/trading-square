#!/usr/bin/env python3
"""One-minute recurrence detector for errors outside persisted business data.

The Spring integrity monitor already owns score, collection-status and database
invariants.  This lightweight host monitor covers the blind spot where the
integrity monitor itself, an HTTP request, or the runtime fails before it can
persist evidence.  Error fingerprints are stored locally so a continuing error
alerts once, becomes re-armed after five quiet minutes, and alerts again on a
later recurrence.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
UTC = dt.timezone.utc
DEFAULT_STATE_FILE = ROOT / ".ops-audit" / "realtime-error-state.json"
DEFAULT_WINDOW_SECONDS = 180
MAX_CATCHUP_SECONDS = 15 * 60
QUIET_REARM_SECONDS = 5 * 60
SEEN_RETENTION_SECONDS = 30 * 60
RUNTIME_STARTUP_GRACE_SECONDS = 3 * 60
DATA_INTEGRITY_ALERT_PREFIX = "Data integrity recurrence detected ("

SPRING_ERROR = re.compile(
    r"^(?P<timestamp>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)\s+"
    r"ERROR\s+\d+\s+---\s+\[macrosquare-server-spring\].*\]\s+"
    r"(?P<logger>[A-Za-z0-9_.$]+)\s+:\s+(?P<message>.+)$"
)
SPRING_RUNTIME_WARNING = re.compile(
    r"^(?P<timestamp>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)\s+"
    r"WARN\s+\d+\s+---\s+\[macrosquare-server-spring\].*\]\s+"
    r"(?P<logger>com\.zaxxer\.hikari\.[A-Za-z0-9_.$]+)\s+:\s+"
    r"(?P<message>.*Thread starvation or clock leap detected.*)$"
)
SECRET = re.compile(r"(?i)(?:bot)?\d{6,}:[A-Za-z0-9_-]{20,}")
UUID = re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}\b")
HEX = re.compile(r"\b[0-9a-fA-F]{16,}\b")
URL = re.compile(r"https?://\S+")
NUMBER = re.compile(r"(?<![A-Za-z])[-+]?\d+(?:\.\d+)?")


@dataclasses.dataclass(frozen=True)
class ErrorEvent:
    event_id: str
    fingerprint: str
    kind: str
    source: str
    sample: str
    observed_at: dt.datetime


def now() -> dt.datetime:
    return dt.datetime.now(UTC)


def run(command: list[str], timeout: int = 15) -> str:
    result = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"{command[0]} exited {result.returncode}")
    return result.stdout


def http_json(url: str, params: dict[str, str] | None = None, timeout: int = 8) -> Any:
    if params:
        url += ("&" if "?" in url else "?") + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


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


def redact(value: str) -> str:
    return SECRET.sub("<redacted-token>", value).replace("\n", " ").strip()


def normalized_message(value: str) -> str:
    normalized = redact(value)
    normalized = URL.sub("<url>", normalized)
    normalized = UUID.sub("<uuid>", normalized)
    normalized = HEX.sub("<hex>", normalized)
    normalized = NUMBER.sub("<n>", normalized)
    return " ".join(normalized.split())[:800]


def fingerprint(kind: str, material: str) -> str:
    return hashlib.sha256(f"{kind}|{material}".encode("utf-8")).hexdigest()


def event(
    kind: str,
    source: str,
    material: str,
    sample: str,
    observed_at: dt.datetime,
    event_material: str | None = None,
) -> ErrorEvent:
    signature = fingerprint(kind, material)
    identity = hashlib.sha256(
        f"{kind}|{event_material or material}|{observed_at.isoformat()}".encode("utf-8")
    ).hexdigest()
    return ErrorEvent(identity, signature, kind, source, redact(sample)[:600], observed_at)


def parse_spring_error(line: str, timestamp_ns: str) -> ErrorEvent | None:
    match = SPRING_ERROR.match(line.rstrip())
    if not match:
        return None
    message = match.group("message").strip()
    # The application already publishes this incident through the transactional
    # Telegram outbox. Suppressing only that exact event avoids duplicate alerts
    # while still catching "integrity check itself failed" from the same logger.
    if message.startswith(DATA_INTEGRITY_ALERT_PREFIX):
        return None
    observed_at = dt.datetime.fromisoformat(match.group("timestamp").replace("Z", "+00:00"))
    logger = match.group("logger")
    material = logger + '|' + normalized_message(message)
    return event(
        "APP_ERROR", logger, material, f"{logger}: {message}", observed_at,
        event_material=timestamp_ns + '|' + line,
    )


def parse_spring_runtime_warning(line: str, timestamp_ns: str) -> ErrorEvent | None:
    match = SPRING_RUNTIME_WARNING.match(line.rstrip())
    if not match:
        return None
    observed_at = dt.datetime.fromisoformat(match.group("timestamp").replace("Z", "+00:00"))
    logger = match.group("logger")
    message = match.group("message").strip()
    return event(
        "RUNTIME_THREAD_STARVATION",
        logger,
        logger + "|thread-starvation-or-clock-leap",
        f"JVM/host scheduler 지연 감지: {message}",
        observed_at,
        event_material=timestamp_ns + "|" + line,
    )


def loki_error_events(started_at: dt.datetime, ended_at: dt.datetime) -> list[ErrorEvent]:
    query = ('{stack="macrosquare-host"} |= "[macrosquare-server-spring]" '
             '|~ " ERROR |Thread starvation or clock leap detected"')
    payload = http_json(
        "http://127.0.0.1:5903/loki/api/v1/query_range",
        {
            "query": query,
            "start": str(int(started_at.timestamp() * 1_000_000_000)),
            "end": str(int(ended_at.timestamp() * 1_000_000_000)),
            "limit": "1000",
            "direction": "forward",
        },
    )
    result: list[ErrorEvent] = []
    for stream in payload.get("data", {}).get("result", []):
        for timestamp_ns, line in stream.get("values", []):
            parsed = parse_spring_error(line, timestamp_ns)
            if parsed is None:
                parsed = parse_spring_runtime_warning(line, timestamp_ns)
            if parsed is not None:
                result.append(parsed)
    return result


def prometheus_5xx_events(observed_at: dt.datetime) -> list[ErrorEvent]:
    query = ('sum by(uri,status)(increase('
             'http_server_requests_seconds_count{status=~"5.."}[2m])) > 0')
    payload = http_json("http://127.0.0.1:5902/api/v1/query", {"query": query})
    result: list[ErrorEvent] = []
    for item in payload.get("data", {}).get("result", []):
        metric = item.get("metric", {})
        uri = str(metric.get("uri", "unknown"))
        status = str(metric.get("status", "5xx"))
        try:
            count = max(1, round(float(item.get("value", [0, 1])[1])))
        except (TypeError, ValueError, IndexError):
            count = 1
        material = f"{uri}|{status}"
        result.append(event(
            "HTTP_5XX", uri, material,
            f"HTTP {status} {uri}: 최근 2분 {count}건", observed_at,
            event_material=f"{material}|{int(observed_at.timestamp() // 60)}",
        ))
    return result


def runtime_health_event(
    state: dict[str, Any],
    observed_at: dt.datetime,
    startup_grace_seconds: int = RUNTIME_STARTUP_GRACE_SECONDS,
) -> ErrorEvent | None:
    """Report unhealthy runtime state without paging during a normal rollout boot.

    Docker reports a newly-created healthy container as ``health=starting`` for
    one or more probe periods. Treating that transition as an incident caused a
    false Telegram page on every approved deployment. A container that is still
    starting after the bounded grace period, or is unhealthy/exited at any time,
    remains alertable.
    """
    if startup_grace_seconds < 0:
        raise ValueError("startup_grace_seconds must not be negative")
    status = str(state.get("Status", "unknown"))
    health_status = str(state.get("Health", {}).get("Status", status))
    if state.get("Paused") is True:
        return event(
            "RUNTIME_PAUSED", "macrosquare-server", "paused",
            "서버 컨테이너가 pause 상태입니다", observed_at,
        )
    if status == "running" and health_status == "healthy":
        return None
    started_at = parse_time(state.get("StartedAt"), observed_at - dt.timedelta(days=1))
    age_seconds = max(0.0, (observed_at - started_at).total_seconds())
    within_normal_startup = (
        status == "running"
        and health_status == "starting"
        and age_seconds < startup_grace_seconds
    )
    if within_normal_startup:
        return None
    return event(
        "RUNTIME_HEALTH", "macrosquare-server", f"{status}|{health_status}",
        f"서버 상태={status} health={health_status}", observed_at,
    )


def runtime_events(
    observed_at: dt.datetime,
    previous: dict[str, Any],
) -> tuple[list[ErrorEvent], dict[str, Any]]:
    state = json.loads(run([
        "docker", "inspect", "macrosquare-server", "--format", "{{json .State}}",
    ]))
    container_id = run([
        "docker", "inspect", "macrosquare-server", "--format", "{{.Id}}",
    ]).strip()
    restart_count = int(run([
        "docker", "inspect", "macrosquare-server", "--format", "{{.RestartCount}}",
    ]).strip())
    memory_events: dict[str, int] = {}
    if state.get("Paused") is not True:
        for line in run([
            "docker", "exec", "macrosquare-server", "cat", "/sys/fs/cgroup/memory.events",
        ]).splitlines():
            key, value = line.split(maxsplit=1)
            memory_events[key] = int(value)
    else:
        # docker exec is rejected for paused containers. Preserve the last
        # counters so the pause itself is alerted instead of being disguised as
        # a generic monitor-source failure.
        memory_events["oom_kill"] = int(previous.get("oomKillCount", 0))
    current = {
        "containerId": container_id,
        "restartCount": restart_count,
        "oomKillCount": memory_events.get("oom_kill", 0),
    }
    result: list[ErrorEvent] = []
    health_event = runtime_health_event(state, observed_at)
    if health_event is not None:
        result.append(health_event)
    if previous.get("containerId") == container_id:
        if restart_count > int(previous.get("restartCount", restart_count)):
            result.append(event(
                "RUNTIME_RESTART", "macrosquare-server", "restart",
                f"서버 재시작 횟수 {previous.get('restartCount')}→{restart_count}", observed_at,
            ))
        previous_oom = int(previous.get("oomKillCount", memory_events.get("oom_kill", 0)))
        if memory_events.get("oom_kill", 0) > previous_oom or state.get("OOMKilled") is True:
            result.append(event(
                "RUNTIME_OOM", "macrosquare-server", "oom-kill",
                f"OOM kill 증가 {previous_oom}→{memory_events.get('oom_kill', 0)}", observed_at,
            ))
    return result, current


def source_failure_event(source: str, error: Exception, observed_at: dt.datetime) -> ErrorEvent:
    error_type = type(error).__name__
    return event(
        "MONITOR_SOURCE_FAILURE", source, f"{source}|{error_type}",
        f"실시간 감시 원천 {source} 조회 실패 ({error_type})", observed_at,
    )


def load_state(path: pathlib.Path) -> dict[str, Any]:
    if not path.is_file():
        return {"schemaVersion": 1, "active": {}, "seen": {}, "runtime": {}}
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"schemaVersion": 1, "active": {}, "seen": {}, "runtime": {}}
    return {
        "schemaVersion": 1,
        "active": dict(loaded.get("active") or {}),
        "seen": dict(loaded.get("seen") or {}),
        "runtime": dict(loaded.get("runtime") or {}),
        "lastRunAt": loaded.get("lastRunAt"),
    }


def parse_time(value: Any, fallback: dt.datetime) -> dt.datetime:
    try:
        parsed = dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=UTC)
    except (TypeError, ValueError):
        return fallback


def evaluate(
    previous: dict[str, Any],
    events: list[ErrorEvent],
    observed_at: dt.datetime,
    quiet_seconds: int = QUIET_REARM_SECONDS,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    if quiet_seconds <= 0:
        raise ValueError("quiet_seconds must be positive")
    cutoff = observed_at - dt.timedelta(seconds=quiet_seconds)
    active = {
        key: dict(value) for key, value in (previous.get("active") or {}).items()
        if parse_time(value.get("lastSeenAt"), observed_at) >= cutoff
    }
    seen_cutoff = (observed_at - dt.timedelta(seconds=SEEN_RETENTION_SECONDS)).timestamp()
    seen = {
        key: float(value) for key, value in (previous.get("seen") or {}).items()
        if float(value) >= seen_cutoff
    }

    for value in sorted(events, key=lambda item: item.observed_at):
        if value.event_id in seen:
            continue
        seen[value.event_id] = value.observed_at.timestamp()
        current = active.get(value.fingerprint)
        if current is None:
            current = {
                "fingerprint": value.fingerprint,
                "kind": value.kind,
                "source": value.source,
                "sample": value.sample,
                "firstSeenAt": value.observed_at.isoformat(),
                "lastAlertAt": None,
                "occurrences": 0,
            }
            active[value.fingerprint] = current
        current["lastSeenAt"] = value.observed_at.isoformat()
        current["sample"] = value.sample
        current["occurrences"] = int(current.get("occurrences", 0)) + 1

    alerts = [
        value for value in active.values()
        if not value.get("lastAlertAt")
    ]
    alerts.sort(key=lambda value: (value.get("firstSeenAt", ""), value.get("fingerprint", "")))
    next_state = {
        "schemaVersion": 1,
        "lastRunAt": observed_at.isoformat(),
        "active": active,
        "seen": dict(sorted(seen.items(), key=lambda item: item[1])[-2000:]),
        "runtime": dict(previous.get("runtime") or {}),
    }
    return next_state, alerts


def mark_alerted(state: dict[str, Any], alerts: list[dict[str, Any]], alerted_at: dt.datetime) -> None:
    for alert in alerts:
        current = state["active"].get(alert["fingerprint"])
        if current is not None:
            current["lastAlertAt"] = alerted_at.isoformat()


def telegram_text(alerts: list[dict[str, Any]], observed_at: dt.datetime) -> str:
    lines = [
        "🚨 MacroSquare 실시간 오류 발생/재발 감지",
        f"시각: {observed_at.astimezone(dt.timezone(dt.timedelta(hours=9))).strftime('%Y. %m. %d. %H:%M:%S KST')}",
        f"신규 오류 지문: {len(alerts)}개",
        "",
    ]
    for value in alerts[:8]:
        lines.append(f"- [{value['kind']}] {value['sample'][:420]}")
    if len(alerts) > 8:
        lines.append(f"- 외 {len(alerts) - 8}개")
    lines += [
        "",
        "같은 오류는 중복 발송하지 않으며 5분간 잠잠해진 뒤 재발하면 다시 알립니다.",
        "점수·수집 DB 무결성 경보는 기존 영속 outbox가 별도로 담당합니다.",
    ]
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


def save_state(path: pathlib.Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    os.chmod(path.parent, 0o700)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def monitor(state_file: pathlib.Path, window_seconds: int, notify: bool) -> dict[str, Any]:
    observed_at = now()
    previous = load_state(state_file)
    last_run = parse_time(previous.get("lastRunAt"), observed_at)
    if "lastRunAt" not in previous:
        started_at = observed_at - dt.timedelta(seconds=window_seconds)
    else:
        started_at = max(
            last_run - dt.timedelta(seconds=window_seconds),
            observed_at - dt.timedelta(seconds=MAX_CATCHUP_SECONDS),
        )

    events: list[ErrorEvent] = []
    for source, loader in (
        ("loki", lambda: loki_error_events(started_at, observed_at)),
        ("prometheus", lambda: prometheus_5xx_events(observed_at)),
    ):
        try:
            events.extend(loader())
        except Exception as error:  # noqa: BLE001 - monitoring source boundary
            events.append(source_failure_event(source, error, observed_at))
    try:
        runtime, runtime_state = runtime_events(observed_at, previous.get("runtime") or {})
        events.extend(runtime)
    except Exception as error:  # noqa: BLE001 - monitoring source boundary
        runtime_state = dict(previous.get("runtime") or {})
        events.append(source_failure_event("docker-runtime", error, observed_at))

    state, alerts = evaluate(previous, events, observed_at)
    state["runtime"] = runtime_state
    delivered = False
    if alerts and notify:
        try:
            delivered = send_telegram(telegram_text(alerts, observed_at), read_env())
        except Exception:  # noqa: BLE001 - do not leak Telegram token in operational output
            delivered = False
        if delivered:
            mark_alerted(state, alerts, observed_at)
    if notify:
        save_state(state_file, state)
    return {
        "observedAt": observed_at.isoformat(),
        "events": len(events),
        "newAlerts": len(alerts),
        "telegramDelivered": delivered,
        "activeFingerprints": len(state["active"]),
        "stateFile": str(state_file),
        "dryRun": not notify,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-file", type=pathlib.Path, default=DEFAULT_STATE_FILE)
    parser.add_argument("--window-seconds", type=int, default=DEFAULT_WINDOW_SECONDS)
    parser.add_argument("--no-notify", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 60 <= args.window_seconds <= MAX_CATCHUP_SECONDS:
        raise SystemExit("window seconds must be between 60 and 900")
    print(json.dumps(
        monitor(args.state_file, args.window_seconds, not args.no_notify),
        ensure_ascii=False,
    ))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # noqa: BLE001 - final host-monitor boundary
        print(json.dumps({
            "severity": "CRITICAL",
            "monitorFailure": type(error).__name__,
        }), file=sys.stderr)
        raise SystemExit(1)
