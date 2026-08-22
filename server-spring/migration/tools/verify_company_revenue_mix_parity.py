#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.request
from datetime import datetime, timezone


DEFAULT_TICKERS = (
    "NVDA", "AAPL", "MSFT", "GOOGL", "AMZN", "META",
    "AVGO", "NEM", "INTU", "TSM", "ASML", "XOM",
)


def breakdown(payload: dict[str, object], field: str) -> dict[str, object] | None:
    result = payload.get("result")
    if not isinstance(result, dict):
        return None
    spring = result.get("spring")
    if not isinstance(spring, dict):
        return None
    value = spring.get(field)
    return value if isinstance(value, dict) else None


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify direct SEC Inline XBRL segment/geography revenue mix extraction"
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:5856")
    parser.add_argument("--tickers", default=",".join(DEFAULT_TICKERS))
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--delay", type=float, default=0.2)
    args = parser.parse_args()

    tickers = tuple(dict.fromkeys(
        ticker.strip().upper() for ticker in args.tickers.split(",") if ticker.strip()
    ))
    results: list[dict[str, object]] = []
    for index, ticker in enumerate(tickers):
        if index and args.delay > 0:
            time.sleep(args.delay)
        request = urllib.request.Request(
            args.base_url.rstrip("/")
            + f"/internal/v1/migration/company-revenue-mix-parity/{ticker}",
            headers={"Accept": "application/json"},
            method="GET",
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                payload = json.load(response)
            segment = breakdown(payload, "segment")
            geography = breakdown(payload, "geography")
            results.append({
                "ticker": ticker,
                "status": response.status,
                "durationMs": round((time.perf_counter() - started) * 1000, 3),
                "migrationReady": payload.get("migrationReady") is True,
                "directCoveragePassed": payload.get("directCoveragePassed") is True,
                "percentageValidationPassed": payload.get("percentageValidationPassed") is True,
                "legacyCoveragePreserved": payload.get("legacyCoveragePreserved") is True,
                "segmentActualAvailable": payload.get("segmentActualAvailable") is True,
                "geographyActualAvailable": payload.get("geographyActualAvailable") is True,
                "candidateFilingCount": payload.get("candidateFilingCount"),
                "analyzedFilingCount": payload.get("analyzedFilingCount"),
                "dimensionalFactCount": payload.get("dimensionalFactCount"),
                "selectedFilingAccessions": payload.get("selectedFilingAccessions", []),
                "extractionFailures": payload.get("extractionFailures", []),
                "differences": payload.get("differences", []),
                "segment": segment,
                "geography": geography,
            })
        except Exception as error:
            results.append({
                "ticker": ticker,
                "status": getattr(error, "code", 0) or 0,
                "durationMs": round((time.perf_counter() - started) * 1000, 3),
                "migrationReady": False,
                "error": str(error),
            })

    failures = [item for item in results if item.get("migrationReady") is not True]
    durations = sorted(float(item.get("durationMs") or 0) for item in results)
    output = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "readOnly": True,
        "directSource": (
            "latest official SEC 10-Q plus latest 10-K/20-F/40-F primary Inline XBRL; "
            "bounded context and revenue-fact streaming parse"
        ),
        "tickerCount": len(results),
        "migrationReadyCount": len(results) - len(failures),
        "directCoverageReadyCount": sum(
            item.get("directCoveragePassed") is True for item in results
        ),
        "percentageValidationReadyCount": sum(
            item.get("percentageValidationPassed") is True for item in results
        ),
        "legacyCoveragePreservedCount": sum(
            item.get("legacyCoveragePreserved") is True for item in results
        ),
        "segmentActualCount": sum(
            item.get("segmentActualAvailable") is True for item in results
        ),
        "geographyActualCount": sum(
            item.get("geographyActualAvailable") is True for item in results
        ),
        "dimensionalFactCount": sum(
            int(item.get("dimensionalFactCount") or 0) for item in results
        ),
        "durationMs": {
            "total": round(sum(durations), 3),
            "median": round(durations[len(durations) // 2], 3) if durations else 0,
            "max": round(max(durations), 3) if durations else 0,
        },
        "allMigrationReady": not failures,
        "results": results,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
