#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone


READ_CASES = (
    ("snapshot", "/api/snapshot"),
    ("history-coverage", "/api/history/coverage"),
    ("history-nasdaq", "/api/history/yahoo/NASDAQ"),
    ("history-missing", "/api/history/yahoo/DOES_NOT_EXIST"),
    (
        "series-normal",
        "/api/history-series?keys=yahoo%3ANASDAQ%2Csignal%3AREGIME&range=1M&interval=1W",
    ),
    (
        "series-invalid-range",
        "/api/history-series?keys=yahoo%3ANASDAQ&range=BAD&interval=BAD",
    ),
    (
        "series-repeated-keys",
        "/api/history-series?keys=yahoo%3ANASDAQ&keys=signal%3AREGIME&range=1M&interval=1D",
    ),
    ("series-defaults", "/api/history-series"),
)


def fetch(base_url: str, path: str, timeout: float) -> tuple[int, str, bytes]:
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        headers={"Accept": "application/json", "Accept-Encoding": "identity"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.headers.get_content_type(), response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers.get_content_type(), error.read()


def digest(body: bytes) -> str:
    return hashlib.sha256(body).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify byte-exact Node/Spring parity for public snapshot/history GET routes"
    )
    parser.add_argument("--node-base-url", default="http://127.0.0.1:5846")
    parser.add_argument("--spring-base-url", default="http://127.0.0.1:5856")
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()

    results: list[dict[str, object]] = []
    for name, path in READ_CASES:
        started = time.perf_counter()
        try:
            # A newly deployed Spring instance is cold. Calling it first makes
            # its validated projection and the direct comparison use the same
            # current Node cache generation.
            spring_status, spring_type, spring_body = fetch(
                args.spring_base_url, path, args.timeout
            )
            node_status, node_type, node_body = fetch(args.node_base_url, path, args.timeout)
            exact = (
                spring_status == node_status
                and spring_type == node_type
                and spring_body == node_body
            )
            results.append({
                "name": name,
                "path": path,
                "passed": exact,
                "status": {"node": node_status, "spring": spring_status},
                "contentType": {"node": node_type, "spring": spring_type},
                "bytes": {"node": len(node_body), "spring": len(spring_body)},
                "sha256": {"node": digest(node_body), "spring": digest(spring_body)},
                "durationMs": round((time.perf_counter() - started) * 1000, 3),
            })
        except Exception as error:
            results.append({
                "name": name,
                "path": path,
                "passed": False,
                "error": str(error),
                "durationMs": round((time.perf_counter() - started) * 1000, 3),
            })

    failures = [result for result in results if result.get("passed") is not True]
    output = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "nodeBaseUrl": args.node_base_url,
        "springBaseUrl": args.spring_base_url,
        "readOnly": True,
        "comparison": "status-content-type-byte-exact",
        "caseCount": len(results),
        "passedCount": len(results) - len(failures),
        "allPassed": not failures,
        "results": results,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
