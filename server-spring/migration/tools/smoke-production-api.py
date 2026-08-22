#!/usr/bin/env python3
"""Read-only production API smoke test plus safe snapshot refresh requests."""

from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.parse
import urllib.request


def run(base_url: str) -> list[dict[str, object]]:
    results: list[dict[str, object]] = []

    def call(
        method: str,
        path: str,
        body: object | None = None,
        expect_json: bool = True,
        record_result: bool = True,
    ) -> object:
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            base_url.rstrip("/") + path,
            data=data,
            method=method,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
                status = response.status
                content_type = response.headers.get("content-type", "")
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
            content_type = error.headers.get("content-type", "")
        elapsed_ms = (time.perf_counter() - started) * 1_000
        if status < 200 or status >= 300:
            raise RuntimeError(f"{method} {path}: HTTP {status}: {raw[:300]!r}")
        parsed: object = raw
        if expect_json:
            try:
                parsed = json.loads(raw)
            except Exception as error:
                raise RuntimeError(f"{method} {path}: invalid JSON: {raw[:200]!r}") from error
        if record_result:
            results.append(
                {
                    "method": method,
                    "path": path,
                    "status": status,
                    "elapsedMs": round(elapsed_ms, 2),
                    "bytes": len(raw),
                    "contentType": content_type,
                }
            )
        return parsed

    call("GET", "/api/health")
    call("GET", "/actuator/health/readiness")
    actuator_info = call("GET", "/actuator/info", record_result=False)
    assert isinstance(actuator_info, dict)
    optional_integrations = actuator_info.get("optionalIntegrations")
    assert isinstance(optional_integrations, dict)
    assert set(optional_integrations) == {"youtube", "openDart"}
    assert all(
        isinstance(value, dict)
        and all(key in value for key in (
            "collectorEnabled", "credentialConfigured", "status", "requiredEnvironmentVariable"
        ))
        for value in optional_integrations.values()
    )
    assert "apiKey" not in json.dumps(optional_integrations)
    snapshot = call("GET", "/api/snapshot")
    assert isinstance(snapshot, dict) and all(
        key in snapshot for key in ("raw", "derived", "signals", "allocation")
    )
    smart_money_freshness = snapshot.get("meta", {}).get("smartMoneyFreshness", {})
    assert all(
        key in smart_money_freshness
        for key in ("observedOn", "ageDays", "maximumAgeDays", "eligibleForRegime", "scoreApplied")
    )
    if not smart_money_freshness["eligibleForRegime"]:
        assert snapshot.get("regime", {}).get("components", {}).get("smartMoney") == 0
    call("POST", "/api/snapshot", {})
    call("POST", "/api/refresh", {})

    coverage = call("GET", "/api/history/coverage")
    assert isinstance(coverage, dict) and len(coverage) >= 50
    call("GET", "/api/history/fred/dgs10")
    call("GET", "/api/history-series?keys=fred%3ADGS10&range=1Y&interval=1W")
    smart_money = call("GET", "/api/smart-money")
    assert isinstance(smart_money, dict) and all(key in smart_money for key in ("insider", "freshness"))
    assert all(
        key in smart_money["freshness"]
        for key in ("observedOn", "ageDays", "maximumAgeDays", "eligibleForDecisions", "status")
    )
    institutional = call("GET", "/api/institutional-flows")
    policy = call("GET", "/api/policy-intelligence")
    assert isinstance(institutional, dict) and all(
        key in institutional for key in ("managers", "consensus", "divergences", "mappedPositionCount")
    )
    assert isinstance(policy, dict) and all(key in policy for key in ("documents", "calibration"))

    company = call("GET", "/api/company/NVDA")
    assert isinstance(company, dict) and company.get("profile", {}).get("ticker") == "NVDA"
    call("GET", "/api/company-summaries?tickers=NVDA%2CMSFT")
    call("GET", "/api/company-search?q=NVDA&limit=8")

    themes = call("GET", "/api/research/themes")
    sectors = call("GET", "/api/research/sectors")
    assert isinstance(themes, dict) and isinstance(sectors, dict)
    theme_id = themes["themes"][0]["id"]
    sector_id = sectors["sectors"][0]["id"]
    call(
        "GET",
        "/api/research/themes/"
        + urllib.parse.quote(theme_id)
        + "?sort=buy&companySort=priority",
    )
    call("GET", "/api/research/sectors/" + urllib.parse.quote(sector_id))
    legacy_sector_backtest = call("GET", "/api/research/sectors/backtest?years=5")
    assert legacy_sector_backtest.get("methodology", {}).get("compatibility") == "LEGACY_REFERENCE_ONLY"
    current_sector_backtest = call("GET", "/api/research/sectors/backtest/current?years=7")
    current_methodology = current_sector_backtest.get("methodology", {})
    assert current_methodology.get("dataBasis") == "ADJUSTED_CLOSE_TOTAL_RETURN"
    assert current_methodology.get("liveRelativeStrengthLayerMatched") is True
    assert current_methodology.get("fullRotationForecastValidated") is False
    assert current_methodology.get("methodologyOrigin") == "PREDEFINED_INSTITUTIONAL_MOMENTUM_PROXY"
    assert 74 <= current_sector_backtest.get("rebalanceCount", 0) <= 85
    assert current_sector_backtest.get("comparisonBaseline", {}).get("compatibility") == "COMPARISON_ONLY_NOT_LIVE"
    assert current_sector_backtest.get("comparisonBaseline", {}).get("assessment", {}).get("status") in {
        "IMPROVED", "MIXED"
    }
    assert 0 <= current_sector_backtest.get("averageMonthlyTurnoverPct", -1) <= 100
    assert all(
        current_sector_backtest.get("summary", {}).get(key, {}).get("sampleCount", 0) >= minimum
        for key, minimum in (("oneMonth", 74), ("threeMonth", 72), ("sixMonth", 69))
    )
    assert all(
        0 <= current_sector_backtest.get("summary", {}).get(key, {}).get("top1HitRate95LowerPct", -1)
        <= current_sector_backtest.get("summary", {}).get(key, {}).get("top1HitRate95UpperPct", 101)
        <= 100
        for key in ("oneMonth", "threeMonth", "sixMonth")
    )
    peers = call("GET", "/api/research/peers/NVDA?limit=20")
    assert isinstance(peers, dict) and "peers" in peers

    dart = call("GET", "/api/dart/disclosures/005930")
    assert isinstance(dart, dict) and all(key in dart for key in ("disclosures", "financials"))
    dart_as_of = dart.get("asOf")
    assert dart_as_of is None or 2000 <= int(str(dart_as_of)[:4]) <= 2100

    bottlenecks = call("GET", "/api/bottleneck/themes")
    assert isinstance(bottlenecks, dict)
    call("GET", "/api/bottleneck/themes/" + urllib.parse.quote(bottlenecks["themes"][0]["id"]))
    narratives = call("GET", "/api/narrative/themes")
    assert isinstance(narratives, dict)
    narrative = call(
        "GET", "/api/narrative/themes/" + urllib.parse.quote(narratives["themes"][0]["id"])
    )
    narrative_overview = call("GET", "/api/narrative/overview")
    assert isinstance(narrative, dict) and all(
        key in narrative
        for key in (
            "sourceStatus",
            "sourceQualityScore",
            "sourceCoveragePct",
            "legacyFallbackUsed",
            "sourceDiagnostics",
            "sourceObservationCount",
            "sourceRevisionCount",
            "sourceMissingCount",
            "sourceFailureCount",
            "sourceLastRefreshAt",
            "sourceHistory",
            "sourceHistoryTruncated",
            "sourceMethodology",
        )
    )
    assert all(
        isinstance(narrative.get(key), int) and narrative[key] >= 0
        for key in (
            "sourceObservationCount",
            "sourceRevisionCount",
            "sourceMissingCount",
            "sourceFailureCount",
        )
    )
    assert isinstance(narrative.get("sourceHistory"), list)
    assert isinstance(narrative.get("sourceHistoryTruncated"), bool)
    assert all(
        all(key in item for key in (
            "sourceKey", "observationDate", "observedAt", "revision", "quality", "status"
        ))
        for item in narrative["sourceHistory"]
    )
    assert len(narrative.get("sourceDiagnostics", [])) == 3
    assert all(
        all(key in item for key in ("quality", "status", "revision", "missingStreak", "effectiveWeight"))
        for item in narrative["sourceDiagnostics"]
    )
    assert isinstance(narrative_overview, dict) and len(narrative_overview.get("themes", [])) >= 11
    assert all(
        "sourceStatus" in item
        and "sourceRevisionCount" in item
        and item.get("sourceHistory") == []
        for item in narrative_overview["themes"]
    )

    crypto = call("GET", "/api/research/crypto")
    assert isinstance(crypto, dict) and len(crypto.get("assets", [])) >= 5
    crypto_freshness = crypto.get("freshness", {})
    assert all(key in crypto_freshness for key in (
        "marketObservedOn", "supportingEvidenceObservedOn", "marketAgeDays",
        "supportingEvidenceAgeDays", "maximumMarketAgeDays",
        "maximumSupportingEvidenceAgeDays", "eligibleForDecisions", "status"
    ))
    assert all(
        isinstance(item.get("freshness"), dict)
        and "eligibleForDecisions" in item["freshness"]
        for item in crypto.get("items", [])
    )
    if not crypto_freshness["eligibleForDecisions"]:
        assert crypto.get("marketRegime", {}).get("action") == "관찰 대기"
        assert crypto.get("marketRegime", {}).get("targetTotalExposurePct") == 0
        assert all(item.get("buyScore", {}).get("action") == "HOLD" for item in crypto["items"])
        assert all(item.get("positionSizing", {}).get("targetPositionPct") == 0 for item in crypto["items"])
    crypto_btc = call("GET", "/api/research/crypto/BTC")
    assert isinstance(crypto_btc, dict) and isinstance(crypto_btc.get("freshness"), dict)
    if not crypto_btc["freshness"]["eligibleForDecisions"]:
        assert crypto_btc.get("buyScore", {}).get("action") == "HOLD"
        assert crypto_btc.get("positionSizing", {}).get("targetPositionPct") == 0
        crypto_bridge = crypto_btc.get("executionBridge")
        if crypto_bridge is not None:
            assert crypto_bridge.get("action") == "HOLD"
    companies = call("GET", "/api/research/companies?sort=buy&page=1&pageSize=20")
    assert (
        isinstance(companies, dict)
        and len(companies.get("items", [])) == 20
        and companies.get("total", 0) >= 200
    )
    call("GET", "/api/research/highlights")
    call("GET", "/api/earnings")
    call("GET", "/api/correlation?lookback=60&keys=NASDAQ%2CGOLD")

    call("GET", "/api/execution-plan/tranche")
    purchasing_power = call(
        "GET",
        "/api/execution-plan/purchasing-power"
        "?principalKrw=100000000&years=30&inflationPct=3"
        "&cashYieldPct=2.5&productiveAssetReturnPct=7",
    )
    assert purchasing_power["projection"]["cashLike"]["annualRealReturnPct"] < 0
    assert purchasing_power["projection"]["productiveAsset"]["realFutureValueKrw"] > 100_000_000
    call("GET", "/api/plan")
    call("GET", "/api/trade-log?limit=20")
    domestic_reports = call("GET", "/api/domestic-reports")
    domestic_freshness = domestic_reports.get("data", {}).get("freshness", {})
    assert all(key in domestic_freshness for key in (
        "observedOn", "ageDays", "maximumAgeDays", "status",
        "usedForInvestmentScores", "eligibleForDecisions"
    ))
    assert domestic_freshness["usedForInvestmentScores"] is False
    assert domestic_freshness["eligibleForDecisions"] is False
    call("GET", "/api/weekly-report?format=json")
    call("GET", "/api/weekly-report?format=text", expect_json=False)
    call("GET", "/api/backtest/summary")
    call("GET", "/api/backtest/portfolio?years=5")
    call("GET", "/api/backtest/user-plan?years=5")
    return results


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:5846")
    args = parser.parse_args()
    results = run(args.base_url)
    timings = sorted(float(item["elapsedMs"]) for item in results)
    print(
        json.dumps(
            {
                "passed": len(results),
                "p50Ms": timings[len(timings) // 2],
                "maxMs": max(timings),
                "results": results,
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
