#!/usr/bin/env python3
from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import time
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path


@dataclass(frozen=True)
class Sample:
    status: int
    duration_ms: float
    bytes: int
    error: str | None


def percentile(values: list[float], percentile_value: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    rank = max(0, min(len(ordered) - 1, math.ceil(percentile_value * len(ordered)) - 1))
    return round(ordered[rank], 3)


def request_once(
    url: str,
    method: str,
    body: bytes | None,
    timeout: float,
    accept_encoding: str | None,
) -> Sample:
    headers = {"Accept": "application/json"}
    if accept_encoding is not None:
        headers["Accept-Encoding"] = accept_encoding
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=body, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read()
            return Sample(response.status, (time.perf_counter() - started) * 1000, len(payload), None)
    except Exception as error:
        status = getattr(error, "code", 0) or 0
        return Sample(status, (time.perf_counter() - started) * 1000, 0, str(error))


def main() -> None:
    parser = argparse.ArgumentParser(description="Dependency-free HTTP latency benchmark")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--path", required=True)
    parser.add_argument("--requests", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=10)
    parser.add_argument("--method", default="GET")
    parser.add_argument("--body-file", type=Path)
    parser.add_argument("--timeout", type=float, default=30)
    parser.add_argument("--accept-encoding", choices=("gzip",))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    body = args.body_file.read_bytes() if args.body_file else None
    url = args.base_url.rstrip("/") + "/" + args.path.lstrip("/")
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(
                request_once,
                url,
                args.method.upper(),
                body,
                args.timeout,
                args.accept_encoding,
            )
            for _ in range(args.requests)
        ]
        samples = [future.result() for future in futures]
    elapsed = time.perf_counter() - started

    durations = [sample.duration_ms for sample in samples]
    errors = [sample for sample in samples if sample.error is not None or sample.status >= 400]
    result = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "url": url,
        "method": args.method.upper(),
        "acceptEncoding": args.accept_encoding,
        "requests": args.requests,
        "concurrency": args.concurrency,
        "elapsedSeconds": round(elapsed, 3),
        "throughputPerSecond": round(args.requests / elapsed, 3) if elapsed else 0,
        "latencyMs": {
            "min": round(min(durations), 3) if durations else 0,
            "p50": percentile(durations, 0.50),
            "p95": percentile(durations, 0.95),
            "p99": percentile(durations, 0.99),
            "max": round(max(durations), 3) if durations else 0,
        },
        "errorCount": len(errors),
        "responseBytes": sorted({sample.bytes for sample in samples}),
        "statusCounts": {
            str(status): sum(1 for sample in samples if sample.status == status)
            for status in sorted({sample.status for sample in samples})
        },
        "errors": [asdict(sample) for sample in errors[:10]],
    }
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    print(rendered, end="")


if __name__ == "__main__":
    main()
