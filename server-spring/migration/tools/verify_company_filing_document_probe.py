#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone


DEFAULT_SEC_PDF = (
    "https://www.sec.gov/Archives/edgar/data/8670/"
    "000000867025000023/adp2025investorday-ex99.pdf"
)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify bounded Spring PDF text extraction against an official SEC archive PDF"
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:5856")
    parser.add_argument("--url", default=DEFAULT_SEC_PDF)
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--minimum-characters", type=int, default=1_000)
    args = parser.parse_args()

    endpoint = (
        args.base_url.rstrip("/")
        + "/internal/v1/migration/company-filing-document-probe?"
        + urllib.parse.urlencode({"url": args.url})
    )
    request = urllib.request.Request(endpoint, headers={"Accept": "application/json"}, method="GET")
    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=args.timeout) as response:
        payload = json.load(response)
        status = response.status

    passed = (
        status == 200
        and payload.get("format") == "pdf"
        and payload.get("hasText") is True
        and isinstance(payload.get("totalPages"), int)
        and payload["totalPages"] > 0
        and isinstance(payload.get("processedPages"), int)
        and 0 < payload["processedPages"] <= payload["totalPages"]
        and int(payload.get("textCharacters") or 0) >= args.minimum_characters
        and bool(payload.get("preview"))
    )
    output = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "sourceUrl": args.url,
        "officialSecArchiveOnly": True,
        "durationMs": round((time.perf_counter() - started) * 1000, 3),
        "passed": passed,
        "result": payload,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
