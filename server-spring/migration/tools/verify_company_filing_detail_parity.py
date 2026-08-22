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


def main() -> None:
    parser = argparse.ArgumentParser(
        description=(
            "Verify legacy IR metadata preservation and bounded direct SEC "
            "Exhibit 99.x/investor-material discovery, PDF parsing, and "
            "structured guidance extraction"
        )
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
            + f"/internal/v1/migration/company-filing-detail-parity/{ticker}",
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
                    "migrationReady": payload.get("migrationReady") is True,
                    "legacyMetadataPreserved": payload.get("legacyMetadataPreserved") is True,
                    "legacySummariesMatched": payload.get("legacySummariesMatched") is True,
                    "exactLegacyMatch": payload.get("exactLegacyMatch") is True,
                    "directCoveragePassed": payload.get("directCoveragePassed") is True,
                    "directDiscoveryImprovement": payload.get("directDiscoveryImprovement") is True,
                    "pdfExtractionCoveragePassed": payload.get("pdfExtractionCoveragePassed") is True,
                    "guidanceExtractionCoveragePassed": (
                        payload.get("guidanceExtractionCoveragePassed") is True
                    ),
                    "scannedFilingCount": payload.get("scannedFilingCount"),
                    "candidateFilingCount": payload.get("candidateFilingCount"),
                    "inspectedIndexCount": payload.get("inspectedIndexCount"),
                    "legacyMaterialCount": payload.get("legacyMaterialCount"),
                    "springMaterialCount": payload.get("springMaterialCount"),
                    "directAttachmentCount": payload.get("directAttachmentCount"),
                    "summarizedDirectAttachmentCount": payload.get("summarizedDirectAttachmentCount"),
                    "pdfMaterialCount": payload.get("pdfMaterialCount"),
                    "parsedPdfMaterialCount": payload.get("parsedPdfMaterialCount"),
                    "summarizedPdfMaterialCount": payload.get("summarizedPdfMaterialCount"),
                    "guidanceEligibleMaterialCount": payload.get("guidanceEligibleMaterialCount"),
                    "guidanceAnalyzedMaterialCount": payload.get("guidanceAnalyzedMaterialCount"),
                    "guidanceRelevantMaterialCount": payload.get("guidanceRelevantMaterialCount"),
                    "structuredGuidanceMaterialCount": payload.get("structuredGuidanceMaterialCount"),
                    "structuredGuidanceMetricCount": payload.get("structuredGuidanceMetricCount"),
                    "guidance": payload.get("guidance", []),
                    "indexFailures": payload.get("indexFailures", []),
                    "summaryFailures": payload.get("summaryFailures", []),
                    "differences": payload.get("differences", []),
                })
        except Exception as error:
            results.append({
                "ticker": ticker,
                "status": getattr(error, "code", 0) or 0,
                "durationMs": round((time.perf_counter() - started) * 1000, 3),
                "migrationReady": False,
                "error": str(error),
            })

    failures = [result for result in results if result.get("migrationReady") is not True]
    output = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "readOnly": True,
        "directSource": (
            "SEC submissions recent 100 rows plus up to 3 official accession indexes; "
            "bounded HTML/TXT and PDF text extraction with prospective "
            "revenue/margin/CAPEX/FCF guidance parsing"
        ),
        "tickerCount": len(results),
        "migrationReadyCount": len(results) - len(failures),
        "metadataPreservedCount": sum(
            result.get("legacyMetadataPreserved") is True for result in results
        ),
        "summaryMatchedCount": sum(
            result.get("legacySummariesMatched") is True for result in results
        ),
        "improvedTickerCount": sum(
            result.get("directDiscoveryImprovement") is True for result in results
        ),
        "directAttachmentCount": sum(
            int(result.get("directAttachmentCount") or 0) for result in results
        ),
        "summarizedDirectAttachmentCount": sum(
            int(result.get("summarizedDirectAttachmentCount") or 0) for result in results
        ),
        "pdfMaterialCount": sum(
            int(result.get("pdfMaterialCount") or 0) for result in results
        ),
        "parsedPdfMaterialCount": sum(
            int(result.get("parsedPdfMaterialCount") or 0) for result in results
        ),
        "summarizedPdfMaterialCount": sum(
            int(result.get("summarizedPdfMaterialCount") or 0) for result in results
        ),
        "guidanceCoverageReadyCount": sum(
            result.get("guidanceExtractionCoveragePassed") is True for result in results
        ),
        "guidanceEligibleMaterialCount": sum(
            int(result.get("guidanceEligibleMaterialCount") or 0) for result in results
        ),
        "guidanceAnalyzedMaterialCount": sum(
            int(result.get("guidanceAnalyzedMaterialCount") or 0) for result in results
        ),
        "guidanceRelevantMaterialCount": sum(
            int(result.get("guidanceRelevantMaterialCount") or 0) for result in results
        ),
        "structuredGuidanceMaterialCount": sum(
            int(result.get("structuredGuidanceMaterialCount") or 0) for result in results
        ),
        "structuredGuidanceMetricCount": sum(
            int(result.get("structuredGuidanceMetricCount") or 0) for result in results
        ),
        "allGuidanceExtractionCoveragePassed": all(
            result.get("guidanceExtractionCoveragePassed") is True for result in results
        ),
        "allMigrationReady": not failures,
        "results": results,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    main()
