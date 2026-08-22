#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.request
from datetime import datetime, timezone


DEFAULT_TICKERS = (
    "NVDA", "NEM", "JPM", "MSFT", "ISRG", "BRK.B", "INTU", "TSM", "ASML", "XOM",
    "AAPL", "AMZN", "META", "GOOGL", "AVGO", "AMD", "INTC", "QCOM", "ORCL", "CRM",
    "ADBE", "NFLX", "TSLA", "V", "MA", "BAC", "GS", "MS", "WMT", "COST", "HD", "LOW",
    "UNH", "LLY", "JNJ", "PFE", "PG", "KO", "PEP", "MCD", "CVX", "COP", "SLB", "LIN",
    "GE", "RTX", "CAT", "DE",
)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compare direct Spring Yahoo price history and bottom/reversal output with legacy Node"
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:5856")
    parser.add_argument("--tickers", default=",".join(DEFAULT_TICKERS))
    parser.add_argument("--timeout", type=float, default=180)
    parser.add_argument("--delay", type=float, default=0.1)
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
            + f"/internal/v1/migration/company-price-signal-parity/{ticker}",
            headers={"Accept": "application/json"},
            method="GET",
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                payload = json.load(response)
                spring = payload.get("result", {}).get("spring", {})
                results.append({
                    "ticker": ticker,
                    "status": response.status,
                    "durationMs": round((time.perf_counter() - started) * 1000, 3),
                    "allMatched": payload.get("allMatched") is True,
                    "priceHistoryMatched": payload.get("priceHistoryMatched") is True,
                    "markersMatched": payload.get("markersMatched") is True,
                    "priceSignalMatched": payload.get("priceSignalMatched") is True,
                    "confirmedBottomMatched": payload.get("confirmedBottomMatched") is True,
                    "reversalConfirmationMatched": payload.get("reversalConfirmationMatched") is True,
                    "differences": payload.get("differences", []),
                    "history": spring.get("history"),
                    "priceSignal": spring.get("priceSignal"),
                    "confirmedBottomState": spring.get("confirmedBottom", {}).get("state"),
                    "reversalStatus": spring.get("reversalConfirmation", {}).get("status"),
                })
        except Exception as error:
            results.append({
                "ticker": ticker,
                "status": getattr(error, "code", 0) or 0,
                "durationMs": round((time.perf_counter() - started) * 1000, 3),
                "allMatched": False,
                "error": str(error),
            })

    failures = [result for result in results if result.get("allMatched") is not True]
    output = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "readOnly": True,
        "directSource": "Yahoo chart period1/period2 daily close and volume, 380 calendar days",
        "tickerCount": len(results),
        "matchedCount": len(results) - len(failures),
        "allMatched": not failures,
        "results": results,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
