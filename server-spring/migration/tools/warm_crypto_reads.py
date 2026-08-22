#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.request
from datetime import datetime, timezone


CRYPTO_PATHS = (
    "/api/research/crypto",
    "/api/research/crypto/BTC",
    "/api/research/crypto/ETH",
    "/api/research/crypto/SOL",
    "/api/research/crypto/XRP",
    "/api/research/crypto/BNB",
)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Warm the read-only Spring shadow Crypto projections after deployment"
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:5856")
    parser.add_argument("--timeout", type=float, default=90)
    args = parser.parse_args()

    results = []
    for path in CRYPTO_PATHS:
        request = urllib.request.Request(
            args.base_url.rstrip("/") + path,
            headers={"Accept": "application/json"},
            method="GET",
        )
        started = time.perf_counter()
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            payload = response.read()
            results.append(
                {
                    "path": path,
                    "status": response.status,
                    "durationMs": round((time.perf_counter() - started) * 1000, 3),
                    "responseBytes": len(payload),
                }
            )

    print(
        json.dumps(
            {
                "capturedAt": datetime.now(timezone.utc).isoformat(),
                "baseUrl": args.base_url,
                "readOnly": True,
                "results": results,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
