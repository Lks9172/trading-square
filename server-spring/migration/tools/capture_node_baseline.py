#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
BASELINE_DIR = REPO_ROOT / "server-spring" / "migration" / "baseline"
RESPONSE_DIR = BASELINE_DIR / "responses"
BASE_URL = os.environ.get("NODE_BASE_URL", "http://192.168.0.200:5846").rstrip("/")

READ_ONLY_ENDPOINTS = {
    "health": "/api/health",
    "snapshot": "/api/snapshot",
    "history-coverage": "/api/history/coverage",
    "company-search": "/api/company-search?q=NVDA&limit=5",
    "company-summaries": "/api/company-summaries?tickers=NVDA,MSFT,NEM",
    "company-nvda": "/api/company/NVDA",
    "research-sectors": "/api/research/sectors",
    "research-crypto": "/api/research/crypto",
}


def top_level_shape(body: bytes) -> dict[str, object]:
    try:
        value = json.loads(body)
    except json.JSONDecodeError:
        return {"json": False}
    if isinstance(value, dict):
        return {"json": True, "type": "object", "keys": sorted(value.keys())}
    if isinstance(value, list):
        return {"json": True, "type": "array", "length": len(value)}
    return {"json": True, "type": type(value).__name__}


def capture(name: str, path: str) -> dict[str, object]:
    url = BASE_URL + path
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    started = time.perf_counter()
    status = 0
    body = b""
    error_message = None
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            status = response.status
            body = response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        body = error.read()
        error_message = str(error)
    except Exception as error:  # baseline capture must record partial failures
        error_message = str(error)

    duration_ms = round((time.perf_counter() - started) * 1000)
    if body:
        (RESPONSE_DIR / f"{name}.json").write_bytes(body)
    return {
        "path": path,
        "status": status,
        "durationMs": duration_ms,
        "bytes": len(body),
        "sha256": hashlib.sha256(body).hexdigest() if body else None,
        "shape": top_level_shape(body) if body else None,
        "error": error_message,
    }


def main() -> None:
    RESPONSE_DIR.mkdir(parents=True, exist_ok=True)
    endpoints = {name: capture(name, path) for name, path in READ_ONLY_ENDPOINTS.items()}
    manifest = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": BASE_URL,
        "readOnly": True,
        "endpoints": endpoints,
    }
    output = BASELINE_DIR / "runtime-manifest.json"
    output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(output)
    for name, result in endpoints.items():
        print(f"{name:20} status={result['status']} durationMs={result['durationMs']} bytes={result['bytes']}")


if __name__ == "__main__":
    main()
