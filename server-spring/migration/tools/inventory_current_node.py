#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
SERVER_SRC = REPO_ROOT / "server" / "src"


def line_number(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def collect_routes() -> list[dict[str, object]]:
    routes: list[dict[str, object]] = []
    route_files = {
        SERVER_SRC / "routes" / "api.ts": "/api",
        SERVER_SRC / "routes" / "backtest.ts": "/api/backtest",
    }
    pattern = re.compile(r"router\.(get|post|put|patch|delete)\(\s*['\"]([^'\"]+)['\"]")
    for path, prefix in route_files.items():
        text = path.read_text(encoding="utf-8")
        for match in pattern.finditer(text):
            routes.append(
                {
                    "method": match.group(1).upper(),
                    "path": prefix + match.group(2),
                    "source": str(path.relative_to(REPO_ROOT)),
                    "line": line_number(text, match.start()),
                }
            )
    return routes


def collect_cron_schedules() -> list[dict[str, object]]:
    path = SERVER_SRC / "index.ts"
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(r"cron\.schedule\(\s*['\"]([^'\"]+)['\"]")
    schedules: list[dict[str, object]] = []
    for match in pattern.finditer(text):
        window = text[match.start() : match.start() + 900]
        trigger = re.search(r"['\"]([a-z0-9-]+(?:min|hour|scan|append|report)[a-z0-9-]*)['\"]", window, re.I)
        schedules.append(
            {
                "expression": match.group(1),
                "timezone": "Asia/Seoul" if "timezone: 'Asia/Seoul'" in window else None,
                "triggerHint": trigger.group(1) if trigger else None,
                "source": str(path.relative_to(REPO_ROOT)),
                "line": line_number(text, match.start()),
            }
        )
    return schedules


def collect_collectors() -> list[str]:
    collector_root = SERVER_SRC / "collectors"
    return sorted(
        str(path.relative_to(collector_root)).removesuffix(".ts")
        for path in collector_root.rglob("*.ts")
        if path.name not in {"index.ts", "_common.ts"}
    )


def collect_persistence_references() -> list[dict[str, object]]:
    roots = [SERVER_SRC / "services", SERVER_SRC / "state", SERVER_SRC / "engines" / "signals.ts"]
    pattern = re.compile(r"(?:path\.(?:resolve|join)|(?:FILE|DIR)\s*=).*(?:data|logs|json)", re.I)
    references: list[dict[str, object]] = []
    files: list[Path] = []
    for root in roots:
        files.extend(root.rglob("*.ts") if root.is_dir() else [root])
    for path in sorted(files):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if pattern.search(line):
                references.append(
                    {
                        "source": str(path.relative_to(REPO_ROOT)),
                        "line": number,
                        "expression": line.strip(),
                    }
                )
    return references


def source_metrics() -> dict[str, object]:
    files = list(SERVER_SRC.rglob("*.ts"))
    line_counts = {
        str(path.relative_to(REPO_ROOT)): len(path.read_text(encoding="utf-8", errors="ignore").splitlines())
        for path in files
    }
    return {
        "typescriptFiles": len(files),
        "typescriptLines": sum(line_counts.values()),
        "largestFiles": [
            {"path": path, "lines": lines}
            for path, lines in sorted(line_counts.items(), key=lambda item: item[1], reverse=True)[:20]
        ],
    }


def main() -> None:
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceRevision": "working-tree",
        "metrics": source_metrics(),
        "routes": collect_routes(),
        "cronSchedules": collect_cron_schedules(),
        "collectors": collect_collectors(),
        "persistenceReferences": collect_persistence_references(),
    }
    output = REPO_ROOT / "server-spring" / "migration" / "baseline" / "inventory.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
