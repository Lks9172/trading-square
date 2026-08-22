#!/usr/bin/env python3
"""One-time, bounded projection handoff from the retiring Node process.

This program is deliberately not part of the Spring runtime.  It is executed
while the old process is still healthy, writes crash-safe `{key, updatedAt,
value}` envelopes, and lets the Java service start without any runtime call to
Node.  Existing company seed documents are copied rather than mutated.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import shutil
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


MAX_RESPONSE_BYTES = 32 * 1024 * 1024
TIMEOUT_SECONDS = 180


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")


def get_json(base_url: str, path: str) -> object:
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        headers={"Accept": "application/json", "User-Agent": "MacroSquare-Spring-Migration/1.0"},
    )
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        if response.status != 200:
            raise RuntimeError(f"GET {path} returned HTTP {response.status}")
        body = response.read(MAX_RESPONSE_BYTES + 1)
    if len(body) > MAX_RESPONSE_BYTES:
        raise RuntimeError(f"GET {path} exceeded {MAX_RESPONSE_BYTES} bytes")
    return json.loads(body)


def atomic_envelope(directory: pathlib.Path, file_name: str, key: str, value: object) -> None:
    if not file_name.endswith(".json") or pathlib.Path(file_name).name != file_name:
        raise ValueError(f"unsafe output file name: {file_name}")
    directory.mkdir(parents=True, exist_ok=True)
    payload = {"key": key, "updatedAt": now_iso(), "value": value}
    fd, temporary = tempfile.mkstemp(prefix=file_name + ".tmp-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
            handle.flush()
            os.fsync(handle.fileno())
            os.fchmod(handle.fileno(), 0o644)
        os.replace(temporary, directory / file_name)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def atomic_text(directory: pathlib.Path, file_name: str, value: str) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=file_name + ".tmp-", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(value)
            handle.flush()
            os.fsync(handle.fileno())
            os.fchmod(handle.fileno(), 0o644)
        os.replace(temporary, directory / file_name)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def copy_company_seeds(source: pathlib.Path, target: pathlib.Path) -> int:
    patterns = (
        "sec-company-ticker-map.json",
        "company-research-lite-*.json",
        "company-research-full-*.json",
        "route_company-detail_v1_*.json",
        "current-telegram-bottom-company-candidates-v2.json",
        "current-telegram-bottom-crypto-candidates-v1.json",
    )
    copied = 0
    target.mkdir(parents=True, exist_ok=True)
    for pattern in patterns:
        for path in source.glob(pattern):
            if not path.is_file() or path.stat().st_size > MAX_RESPONSE_BYTES:
                continue
            destination = target / path.name
            temporary = target / (path.name + ".copying")
            shutil.copyfile(path, temporary)
            os.replace(temporary, destination)
            copied += 1
    return copied


def capture(base_url: str, output: pathlib.Path, legacy_cache: pathlib.Path | None) -> dict[str, int]:
    counts = {"api": 0, "companySeeds": 0, "companies": 0}
    if legacy_cache is not None:
        counts["companySeeds"] = copy_company_seeds(legacy_cache, output)

    def save(path: str, file_name: str, key: str) -> object:
        value = get_json(base_url, path)
        atomic_envelope(output, file_name, key, value)
        counts["api"] += 1
        return value

    snapshot = save("/api/snapshot", "latest-system-snapshot-default-v1.json", "spring:seed:snapshot:v1")
    if not isinstance(snapshot, dict) or not all(k in snapshot for k in ("raw", "derived", "signals", "allocation")):
        raise RuntimeError("snapshot contract is incomplete")

    themes = save("/api/research/themes", "route_research-themes_v1.json", "spring:research:themes:v1")
    sectors = save("/api/research/sectors", "route_research-sectors_v6.json", "spring:research:sectors:v6")
    for theme in themes.get("themes", []) if isinstance(themes, dict) else []:
        theme_id = str(theme.get("id", ""))
        if not theme_id.replace("-", "").isalnum():
            continue
        save(
            f"/api/research/themes/{urllib.parse.quote(theme_id)}?sort=buy&companySort=priority",
            f"route_research-theme-detail_v1_{theme_id}_sort_buy_companysort_priority.json",
            f"spring:research:theme:{theme_id}:buy:priority",
        )
    for sector in sectors.get("sectors", []) if isinstance(sectors, dict) else []:
        sector_id = str(sector.get("id", ""))
        if not sector_id.replace("-", "").isalnum():
            continue
        save(
            f"/api/research/sectors/{urllib.parse.quote(sector_id)}",
            f"route_research-sector-detail_v1_{sector_id}.json",
            f"spring:research:sector:{sector_id}",
        )

    crypto = save("/api/research/crypto", "route_research-crypto_v1.json", "spring:crypto:catalog:v1")
    for asset in crypto.get("assets", []) if isinstance(crypto, dict) else []:
        symbol = str(asset.get("symbol", "")).upper()
        if not symbol.isalnum():
            continue
        save(
            f"/api/research/crypto/{urllib.parse.quote(symbol)}",
            f"route_research-crypto-detail_v1_{symbol.lower()}.json",
            f"spring:crypto:{symbol}:v1",
        )

    bottlenecks = save(
        "/api/bottleneck/themes", "spring_bottleneck-themes_v1.json", "spring:bottleneck:themes:v1"
    )
    for item in bottlenecks.get("themes", []) if isinstance(bottlenecks, dict) else []:
        item_id = str(item.get("id", ""))
        if not item_id.replace("-", "").isalnum():
            continue
        save(
            f"/api/bottleneck/themes/{urllib.parse.quote(item_id)}",
            f"spring_bottleneck-theme_v1_{item_id}.json",
            f"spring:bottleneck:{item_id}:v1",
        )

    # Capture one complete, unpaged company projection.  Runtime filtering,
    # sorting and pagination are then Java-owned and work for every query.
    all_items: list[object] = []
    first: dict | None = None
    page = 1
    while True:
        payload = get_json(base_url, f"/api/research/companies?sort=buy&page={page}&pageSize=100")
        if not isinstance(payload, dict) or not isinstance(payload.get("items"), list):
            raise RuntimeError("company catalog contract is incomplete")
        if first is None:
            first = payload
        all_items.extend(payload["items"])
        total_pages = int(payload.get("totalPages", 1))
        if page >= total_pages:
            break
        page += 1
        if page > 100:
            raise RuntimeError("company catalog exceeded 100 bounded pages")
    catalog = {
        "items": all_items,
        "themes": (first or {}).get("themes", []),
        "sectors": (first or {}).get("sectors", []),
    }
    atomic_envelope(output, "spring_research-companies-catalog_v1.json", "spring:research:companies:v1", catalog)
    counts["companies"] = len(all_items)

    static_gets = (
        ("/api/smart-money", "spring_smart-money_v1.json", "spring:smart-money:v1"),
        ("/api/research/highlights", "spring_research-highlights_v1.json", "spring:research:highlights:v1"),
        ("/api/earnings", "spring_earnings_v1.json", "spring:earnings:v1"),
        ("/api/domestic-reports", "spring_domestic-reports_v1.json", "spring:domestic-reports:v1"),
        ("/api/weekly-report?format=json", "spring_weekly-report_v1.json", "spring:weekly-report:v1"),
        ("/api/backtest/summary", "spring_backtest-summary_v1.json", "spring:backtest:summary:v1"),
    )
    for args in static_gets:
        save(*args)

    for days in (30, 60, 120, 250):
        save(
            f"/api/correlation?lookback={days}",
            f"spring_correlation_v1_{days}.json",
            f"spring:correlation:{days}:v1",
        )
    for years in range(1, 6):
        save(
            f"/api/backtest/portfolio?years={years}",
            f"spring_backtest-portfolio_v1_{years}.json",
            f"spring:backtest:portfolio:{years}:v1",
        )
        save(
            f"/api/backtest/user-plan?years={years}",
            f"spring_backtest-user-plan_v1_{years}.json",
            f"spring:backtest:user-plan:{years}:v1",
        )
    for years in range(3, 6):
        save(
            f"/api/research/sectors/backtest?years={years}",
            f"route_research-sectors-backtest_v1_{years}.json",
            f"spring:research:sector-backtest:{years}:v1",
        )

    weekly_text = get_json(base_url, "/api/weekly-report?format=json")
    if isinstance(weekly_text, dict) and isinstance(weekly_text.get("text"), str):
        atomic_text(output, "spring_weekly-report_v1.txt", weekly_text["text"])
    manifest = {"capturedAt": now_iso(), "baseUrl": base_url, **counts}
    atomic_envelope(output, "spring_projection-manifest_v1.json", "spring:projection:manifest:v1", manifest)
    return counts


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:5846")
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--legacy-cache", type=pathlib.Path)
    args = parser.parse_args()
    started = time.monotonic()
    counts = capture(args.base_url, args.output.resolve(), args.legacy_cache.resolve() if args.legacy_cache else None)
    print(json.dumps({**counts, "elapsedSeconds": round(time.monotonic() - started, 2)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
