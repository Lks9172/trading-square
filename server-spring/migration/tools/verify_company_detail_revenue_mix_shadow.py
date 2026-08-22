#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.request
from datetime import datetime, timezone
from decimal import Decimal


DEFAULT_TICKERS = (
    "NVDA", "AAPL", "MSFT", "GOOGL", "AMZN", "META",
    "AVGO", "NEM", "INTU", "TSM", "ASML", "XOM",
)
SOURCES = {"direct-sec-actual", "legacy-fallback", "unavailable"}


def percent_sum(entries: object) -> Decimal:
    if not isinstance(entries, list):
        return Decimal("0")
    return sum(
        (Decimal(str(entry.get("percentOfTotal")))
         for entry in entries
         if isinstance(entry, dict) and entry.get("percentOfTotal") is not None),
        Decimal("0"),
    )


def validate_axis(payload: dict[str, object], axis: str, source: str) -> list[str]:
    errors: list[str] = []
    result = payload.get("result")
    if not isinstance(result, dict):
        return ["result"]
    serving = result.get("serving")
    shadow = result.get("shadow")
    if not isinstance(serving, dict) or not isinstance(shadow, dict):
        return ["result.serving/shadow"]
    serving_entries = serving.get(axis)
    shadow_entries = shadow.get(axis)
    if not isinstance(serving_entries, list) or not isinstance(shadow_entries, list):
        return [f"result.{axis}.shape"]

    if source == "direct-sec-actual":
        if len(shadow_entries) < 2:
            errors.append(f"{axis}.actual-entry-count")
        if percent_sum(shadow_entries) != Decimal("100.0"):
            errors.append(f"{axis}.actual-percent-sum")
        for index, entry in enumerate(shadow_entries):
            if not isinstance(entry, dict):
                errors.append(f"{axis}[{index}].shape")
                continue
            if not entry.get("label") or entry.get("value") is None or not entry.get("unit"):
                errors.append(f"{axis}[{index}].actual-value")
    elif source == "legacy-fallback":
        if shadow_entries != serving_entries:
            errors.append(f"{axis}.fallback-mutated")
    elif source == "unavailable":
        if shadow_entries:
            errors.append(f"{axis}.unavailable-not-empty")
    else:
        errors.append(f"{axis}.source")
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify actual-first, legacy-fallback company detail revenue-mix shadow composition"
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
            + f"/internal/v1/migration/company-detail-revenue-mix-shadow/{ticker}",
            headers={"Accept": "application/json"},
            method="GET",
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                payload = json.load(response)
            segment_source = str(payload.get("segmentSource") or "")
            geography_source = str(payload.get("geographySource") or "")
            errors: list[str] = []
            if payload.get("publicEndpointMode") != "legacy-unchanged":
                errors.append("publicEndpointMode")
            if payload.get("contractCompatible") is not True:
                errors.append("contractCompatible")
            if payload.get("servingSnapshotMatched") is not True:
                errors.append("servingSnapshotMatched")
            if payload.get("shadowServeReady") is not True:
                errors.append("shadowServeReady")
            if segment_source not in SOURCES or geography_source not in SOURCES:
                errors.append("source")
            if "direct-sec-actual" not in (segment_source, geography_source):
                errors.append("directActualMissing")
            errors.extend(validate_axis(payload, "segment", segment_source))
            errors.extend(validate_axis(payload, "geography", geography_source))
            results.append({
                "ticker": ticker,
                "status": response.status,
                "durationMs": round((time.perf_counter() - started) * 1000, 3),
                "passed": not errors,
                "contractCompatible": payload.get("contractCompatible") is True,
                "servingSnapshotMatched": payload.get("servingSnapshotMatched") is True,
                "shadowServeReady": payload.get("shadowServeReady") is True,
                "directMigrationReady": payload.get("directMigrationReady") is True,
                "fallbackUsed": payload.get("fallbackUsed") is True,
                "segmentSource": segment_source,
                "geographySource": geography_source,
                "candidateFilingCount": payload.get("candidateFilingCount"),
                "analyzedFilingCount": payload.get("analyzedFilingCount"),
                "dimensionalFactCount": payload.get("dimensionalFactCount"),
                "extractionFailures": payload.get("extractionFailures", []),
                "validationErrors": errors,
            })
        except Exception as error:
            results.append({
                "ticker": ticker,
                "status": getattr(error, "code", 0) or 0,
                "durationMs": round((time.perf_counter() - started) * 1000, 3),
                "passed": False,
                "error": str(error),
            })

    failures = [item for item in results if item.get("passed") is not True]
    durations = sorted(float(item.get("durationMs") or 0) for item in results)
    output = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "readOnly": True,
        "publicEndpointMode": "legacy-unchanged",
        "tickerCount": len(results),
        "passedCount": len(results) - len(failures),
        "contractCompatibleCount": sum(
            item.get("contractCompatible") is True for item in results
        ),
        "servingSnapshotMatchedCount": sum(
            item.get("servingSnapshotMatched") is True for item in results
        ),
        "shadowServeReadyCount": sum(
            item.get("shadowServeReady") is True for item in results
        ),
        "directMigrationReadyCount": sum(
            item.get("directMigrationReady") is True for item in results
        ),
        "fallbackUsedCount": sum(
            item.get("fallbackUsed") is True for item in results
        ),
        "segmentActualCount": sum(
            item.get("segmentSource") == "direct-sec-actual" for item in results
        ),
        "geographyActualCount": sum(
            item.get("geographySource") == "direct-sec-actual" for item in results
        ),
        "durationMs": {
            "total": round(sum(durations), 3),
            "median": round(durations[len(durations) // 2], 3) if durations else 0,
            "max": round(max(durations), 3) if durations else 0,
        },
        "allPassed": not failures,
        "results": results,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
