#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time
import urllib.request
from datetime import datetime, timezone


DEFAULT_TICKERS = ("NVDA", "NEM", "JPM", "MSFT", "ISRG", "BRK.B", "INTU", "TSM", "ASML", "XOM")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run the read-only Spring quote/analyst/expectations/fundamentals/Score/Buy Score parallel comparison"
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:5856")
    parser.add_argument("--tickers", default=",".join(DEFAULT_TICKERS))
    parser.add_argument("--timeout", type=float, default=150)
    parser.add_argument("--delay", type=float, default=0.2)
    args = parser.parse_args()

    tickers = tuple(dict.fromkeys(
        ticker.strip().upper() for ticker in args.tickers.split(",") if ticker.strip()
    ))
    results: list[dict[str, object]] = []
    for index, ticker in enumerate(tickers):
        if index and args.delay > 0:
            time.sleep(args.delay)
        path = f"/internal/v1/migration/company-research-parity/{ticker}"
        request = urllib.request.Request(
            args.base_url.rstrip("/") + path,
            headers={"Accept": "application/json"},
            method="GET",
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                payload = json.load(response)
                results.append({
                    "ticker": ticker,
                    "status": response.status,
                    "durationMs": round((time.perf_counter() - started) * 1000, 3),
                    "registryCik": payload.get("registryCik"),
                    "fundamentalsCik": payload.get("cik"),
                    "allMatched": payload.get("allMatched") is True,
                    "identityMatched": payload.get("identityMatched") is True,
                    "quoteMatched": payload.get("quoteMatched") is True,
                    "analystConsensusMatched": payload.get("analystConsensusMatched") is True,
                    "analystHistoryMatched": payload.get("analystHistoryMatched") is True,
                    "expectationsMatched": payload.get("expectationsMatched") is True,
                    "fundamentalsMatched": payload.get("fundamentalsMatched") is True,
                    "scoreMatched": payload.get("scoreMatched") is True,
                    "buyScoreMatched": payload.get("buyScoreMatched") is True,
                    "differences": payload.get("differences", []),
                    "legacyQuote": payload.get("quote", {}).get("legacy"),
                    "springQuote": payload.get("quote", {}).get("spring"),
                    "legacyAnalystConsensus": payload.get("analystConsensus", {}).get("legacy"),
                    "springAnalystConsensus": payload.get("analystConsensus", {}).get("spring"),
                    "analystHistoryMode": payload.get("analystHistory", {}).get("mode"),
                    "analystHistorySelectedSource": payload.get("analystHistory", {}).get("selectedSource"),
                    "analystHistoryLegacyState": payload.get("analystHistory", {}).get("legacyState"),
                    "analystHistoryShadowState": payload.get("analystHistory", {}).get("shadowState"),
                    "analystHistoryComparisonPerformed": payload.get("analystHistory", {}).get("comparisonPerformed"),
                    "analystHistoryDifferences": payload.get("analystHistory", {}).get("differences", []),
                    "analystHistoryLegacyPointCount": payload.get("analystHistory", {}).get("legacyPointCount"),
                    "analystHistoryShadowPointCount": payload.get("analystHistory", {}).get("shadowPointCount"),
                    "legacyExpectations": payload.get("expectations", {}).get("legacy"),
                    "springExpectations": payload.get("expectations", {}).get("spring"),
                    "legacyTotalScore": payload.get("score", {}).get("legacy", {}).get("totalScore"),
                    "springTotalScore": payload.get("score", {}).get("spring", {}).get("totalScore"),
                    "legacyBuyScore": payload.get("buyScore", {}).get("legacy", {}).get("buyScore"),
                    "springBuyScore": payload.get("buyScore", {}).get("spring", {}).get("buyScore"),
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
        "directSource": (
            "SEC company_tickers.json + Yahoo chart quote + Yahoo recommendationTrend/financialData "
            "+ isolated Spring analyst history with read-only legacy fallback + SEC Company Facts"
        ),
        "tickerCount": len(results),
        "matchedCount": len(results) - len(failures),
        "allMatched": not failures,
        "results": results,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
