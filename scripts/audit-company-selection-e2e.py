#!/usr/bin/env python3
"""Read-only E2E audit for the current company-selection projections.

The check proves that catalog membership remains static metadata while every
displayed/ranked company metric comes from the current Spring V5 PostgreSQL read
model.  It performs no writes and is safe for deployment and daily operations.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import subprocess
import sys
import urllib.parse
import urllib.request
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
UTC = dt.timezone.utc
MAXIMUM_AGE = dt.timedelta(hours=2)
MAXIMUM_FUTURE_SKEW = dt.timedelta(minutes=5)


def normalize_ticker(value: str) -> str:
    return value.strip().upper().replace(".", "-")


def http_json(base_url: str, path: str, timeout: int = 30) -> Any:
    request = urllib.request.Request(base_url.rstrip("/") + path, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def psql_json(sql: str) -> list[dict[str, Any]]:
    encoded = "select coalesce(json_agg(a), '[]'::json) from (" + sql + ") a"
    result = subprocess.run(
        [
            "docker", "compose", "exec", "-T", "postgres", "psql",
            "-U", "macrosquare", "-d", "macrosquare", "-Atc", encoded,
        ],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=60,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout or "psql failed").strip()[:500])
    return json.loads(result.stdout.strip() or "[]")


def parse_instant(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=UTC)


def bottom_state(value: str | None) -> str | None:
    return {
        "CONVICTION": "확신",
        "CANDIDATE": "후보",
        "UNMET": "미충족",
        None: None,
    }.get(value, value)


def metric_view(row: dict[str, Any], observed_at: dt.datetime) -> dict[str, Any]:
    updated_at = parse_instant(str(row["updated_at"]))
    fresh = updated_at <= observed_at + MAXIMUM_FUTURE_SKEW and updated_at + MAXIMUM_AGE >= observed_at
    score_current = fresh and bool(row["score_comparable"])
    price_current = fresh and bool(row["price_bundle_complete"])
    return {
        "totalScore": row["total_score"] if score_current else None,
        "buyScore": row["buy_score"] if score_current else None,
        "confirmedBottomScore": row["confirmed_bottom_score"] if price_current else None,
        "confirmedBottomState": bottom_state(row["confirmed_bottom_state"]) if price_current else None,
        "priceBottomScore": row["price_bottom_score"] if price_current else None,
        "volumeConfirmationScore": row["volume_confirmation_score"] if price_current else None,
        "bottomFailureRiskScore": row["failure_risk_score"] if price_current else None,
    }


def compare_metrics(
    problems: list[dict[str, Any]],
    where: str,
    item: dict[str, Any],
    rows: dict[str, dict[str, Any]],
    observed_at: dt.datetime,
) -> None:
    ticker = normalize_ticker(str(item["ticker"]))
    row = rows.get(ticker)
    if row is None:
        problems.append({"code": "MISSING_DB_SUMMARY", "where": where, "ticker": ticker})
        return
    for field, expected in metric_view(row, observed_at).items():
        # Theme/sector detail names failureRiskScore without the companies-page
        # compatibility prefix. Both map to the same current DB field.
        actual_field = "failureRiskScore" if field == "bottomFailureRiskScore" and where != "companies" else field
        actual = item.get(actual_field)
        if isinstance(actual, float) and actual.is_integer():
            actual = int(actual)
        if actual != expected:
            problems.append({
                "code": "CURRENT_METRIC_DRIFT", "where": where, "ticker": ticker,
                "field": actual_field, "actual": actual, "expected": expected,
            })


def buy_order(items: list[dict[str, Any]]) -> list[str]:
    def key(item: dict[str, Any]) -> tuple[float, str]:
        score = item.get("buyScore")
        return (-(float(score) if score is not None else -1.0), normalize_ticker(str(item["ticker"])))
    return [normalize_ticker(str(item["ticker"])) for item in sorted(items, key=key)]


def database_rows() -> dict[str, dict[str, Any]]:
    rows = psql_json("""
        select ticker, calculation_version, fundamentals_status, valuation_eligible, updated_at,
               total_score, growth_score, quality_score, valuation_score, balance_sheet_score,
               buy_score, buy_label, appeal_score, crowding_score,
               price_bottom_score, volume_confirmation_score, failure_risk_score,
               confirmed_bottom_score, confirmed_bottom_state,
               (fundamentals_status='CURRENT' and valuation_eligible
                   and total_score is not null and growth_score is not null
                   and quality_score is not null and valuation_score is not null
                   and balance_sheet_score is not null and buy_score is not null
                   and appeal_score is not null and crowding_score is not null
                   and buy_label is not null) as score_comparable,
               (price_bottom_score is not null and volume_confirmation_score is not null
                   and failure_risk_score is not null and confirmed_bottom_score is not null
                   and confirmed_bottom_state is not null) as price_bundle_complete
          from company.research_summary
         order by ticker
    """)
    return {normalize_ticker(str(row["ticker"])): row for row in rows}


def audit(base_url: str, observed_at: dt.datetime | None = None) -> dict[str, Any]:
    observed_at = observed_at or dt.datetime.now(UTC)
    sectors = http_json(base_url, "/api/research/sectors")
    themes = http_json(base_url, "/api/research/themes")
    sector_details = {
        value["id"]: http_json(base_url, "/api/research/sectors/" + urllib.parse.quote(value["id"]))
        for value in sectors["sectors"]
    }
    theme_details = {
        value["id"]: http_json(
            base_url,
            "/api/research/themes/" + urllib.parse.quote(value["id"]) + "?companySort=buy",
        )
        for value in themes["themes"]
    }

    first = http_json(base_url, "/api/research/companies?sort=buy&page=1&pageSize=100")
    company_items = list(first["items"])
    for page in range(2, int(first["totalPages"]) + 1):
        page_value = http_json(
            base_url, f"/api/research/companies?sort=buy&page={page}&pageSize=100"
        )
        company_items.extend(page_value["items"])

    rows = database_rows()
    problems: list[dict[str, Any]] = []
    union: set[str] = set()
    membership_counts: list[dict[str, Any]] = []
    detail_groups: list[tuple[str, list[dict[str, Any]]]] = []

    for sector_id, detail in sector_details.items():
        declared = {normalize_ticker(value) for value in detail["sector"]["tickers"]}
        actual = {normalize_ticker(value["ticker"]) for value in detail["items"]}
        union.update(declared)
        membership_counts.append({"kind": "sector", "id": sector_id, "count": len(declared)})
        if declared != actual:
            problems.append({
                "code": "SECTOR_MEMBERSHIP_DRIFT", "id": sector_id,
                "missing": sorted(declared - actual), "unexpected": sorted(actual - declared),
            })
        detail_groups.append((f"sector:{sector_id}", detail["items"]))

    for theme_id, detail in theme_details.items():
        declared = {normalize_ticker(value) for value in detail["theme"]["tickers"]}
        actual = {normalize_ticker(value["ticker"]) for value in detail["items"]}
        union.update(declared)
        membership_counts.append({"kind": "theme", "id": theme_id, "count": len(declared)})
        if declared != actual:
            problems.append({
                "code": "THEME_MEMBERSHIP_DRIFT", "id": theme_id,
                "missing": sorted(declared - actual), "unexpected": sorted(actual - declared),
            })
        detail_groups.append((f"theme:{theme_id}", detail["items"]))

    company_tickers = [normalize_ticker(value["ticker"]) for value in company_items]
    company_set = set(company_tickers)
    if len(company_tickers) != int(first["total"]) or len(company_set) != len(company_tickers):
        problems.append({
            "code": "COMPANY_PAGINATION_DRIFT", "declared": first["total"],
            "items": len(company_tickers), "unique": len(company_set),
        })
    if company_set != union:
        problems.append({
            "code": "COMPANY_CATALOG_UNION_DRIFT",
            "missing": sorted(union - company_set), "unexpected": sorted(company_set - union),
        })
    if set(rows) != union:
        problems.append({
            "code": "COMPANY_DB_UNIVERSE_DRIFT",
            "missing": sorted(union - set(rows)), "unexpected": sorted(set(rows) - union),
        })
    non_v6 = sorted(ticker for ticker, row in rows.items() if int(row["calculation_version"]) != 6)
    if non_v6:
        problems.append({"code": "LEGACY_COMPANY_CALCULATION", "tickers": non_v6})

    for item in company_items:
        compare_metrics(problems, "companies", item, rows, observed_at)
    if company_tickers != buy_order(company_items):
        problems.append({"code": "CURRENT_BUY_RANK_DRIFT", "where": "companies"})

    for where, items in detail_groups:
        for item in items:
            compare_metrics(problems, where, item, rows, observed_at)
        if [normalize_ticker(value["ticker"]) for value in items] != buy_order(items):
            problems.append({"code": "CURRENT_BUY_RANK_DRIFT", "where": where})
        if any(int(value.get("rank", -1)) != index for index, value in enumerate(items, start=1)):
            problems.append({"code": "CURRENT_RANK_NUMBER_DRIFT", "where": where})

    sector_keys = {value["sectorKey"] for value in sectors["sectors"]}
    rotation = sectors.get("rotation") or {}
    rotation_counts: dict[str, int] = {}
    for field in ("currentLeaders", "nextCandidates", "secondaryCandidates", "fadingCandidates"):
        candidates = rotation.get(field) or []
        rotation_counts[field] = len(candidates)
        unknown = sorted({value["sectorKey"] for value in candidates} - sector_keys)
        if unknown:
            problems.append({"code": "UNKNOWN_ROTATION_SECTOR", "field": field, "keys": unknown})

    return {
        "status": "OK" if not problems else "FAILED",
        "observedAt": observed_at.isoformat(),
        "databaseRows": len(rows),
        "catalogUnion": len(union),
        "allCompanyItems": len(company_items),
        "sectorCount": len(sector_details),
        "themeCount": len(theme_details),
        "membershipCounts": membership_counts,
        "rotationCounts": rotation_counts,
        "problemCount": len(problems),
        "problems": problems[:100],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:5846")
    parser.add_argument("--no-fail", action="store_true")
    args = parser.parse_args()
    try:
        result = audit(args.base_url)
    except Exception as error:  # bounded operational evidence, no traceback/secrets
        result = {
            "status": "ERROR",
            "errorType": type(error).__name__,
            "problemCount": 1,
            "problems": [{"code": "AUDIT_EXECUTION_FAILED"}],
        }
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if args.no_fail or result["status"] == "OK" else 1


if __name__ == "__main__":
    raise SystemExit(main())
