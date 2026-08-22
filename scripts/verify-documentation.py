#!/usr/bin/env python3
"""Verify MacroSquare documentation against executable production contracts.

This is intentionally standard-library only so it can run in CI, local preflight and
the home-server deployment path. It does not infer financial behavior from prose; it
prevents known high-impact documentation drift around versions, universe size,
migrations, routes, schedules, freshness and decision-record registration.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.parse
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]

STATUS_DOCUMENTS: dict[str, tuple[str, ...]] = {
    "CURRENT": (
        "docs/README.md",
        "docs/DOCUMENT-GOVERNANCE.md",
        "docs/DECISION-RECORDS.md",
        "docs/INVESTMENT-DECISION-STACK.md",
        "docs/RUNBOOK-daily-observability-audit.md",
        "docs/SECTOR-ROTATION-METHODOLOGY-V2.md",
        "docs/finance/README.md",
        "docs/finance/FINANCIAL-DECISION-MODEL.md",
        "docs/finance/COMPANY-SCORECARD.md",
        "docs/finance/DATA-SOURCES-AND-FRESHNESS.md",
        "docs/finance/BACKTEST-AND-MODEL-GOVERNANCE.md",
        "docs/finance/SECTOR-ROTATION-PLAYBOOK.md",
        "docs/finance/CRYPTO-AND-CROSS-ASSET.md",
        "docs/finance/MACD-TIMING-METHODOLOGY.md",
        "docs/development/README.md",
        "docs/development/SYSTEM-ARCHITECTURE.md",
        "docs/development/DATA-CONTRACTS-AND-LINEAGE.md",
        "docs/development/SCHEDULERS-CONCURRENCY-IDEMPOTENCY.md",
        "docs/development/TESTING-AND-QUALITY-GATES.md",
        "docs/development/INCIDENT-RECURRENCE-PREVENTION.md",
        "docs/development/CHANGE-TRACEABILITY-MATRIX.md",
        "docs/development/DEPLOYMENT-ROLLBACK-RECOVERY.md",
        "docs/development/API-SURFACE.md",
        "server-spring/ARCHITECTURE.md",
        "server-spring/docs/BACKUP-RESTORE.md",
    ),
    "DECISION": (
        "server-spring/docs/ADR-001-storage-and-database-boundaries.md",
        "server-spring/docs/ADR-002-sector-rotation-total-return-momentum.md",
        "docs/PDR-001-sector-rotation-product-interpretation.md",
    ),
    "SNAPSHOT": (
        "docs/ASSET-X2-VIDEO-COVERAGE-2026-07-26.md",
        "docs/TODO-STATUS-2026-07-21.md",
    ),
    "ARCHIVED": (
        "docs/LONGTERM-architecture-company-bottleneck-narrative.md",
        "docs/TODO-institutional-policy-integration.md",
        "docs/TODO-topdown-video-integration.md",
        "docs/history-alignment-audit.md",
    ),
}

LINK_ENTRYPOINTS = (
    "README.md",
    "server-spring/README.md",
    *tuple(path for paths in STATUS_DOCUMENTS.values() for path in paths),
)

SCHEDULE_KEYS = (
    "FRED_COLLECTION_FIXED_DELAY",
    "YAHOO_COLLECTION_FIXED_DELAY",
    "SECTOR_TOTAL_RETURN_COLLECTION_FIXED_DELAY",
    "SECTOR_MARKET_EVIDENCE_FIXED_DELAY",
    "FEAR_GREED_COLLECTION_FIXED_DELAY",
    "SENTIMENT_COLLECTION_FIXED_DELAY",
    "STABLECOIN_COLLECTION_FIXED_DELAY",
    "KRX_COLLECTION_FIXED_DELAY",
    "MARKET_SNAPSHOT_FIXED_DELAY",
    "COMPANY_RESEARCH_SUMMARY_FIXED_DELAY",
    "COMPANY_RESEARCH_SUMMARY_STARTUP_DELAY",
    "COMPANY_ANALYST_HISTORY_STARTUP_DELAY",
    "SEC_13F_FIXED_DELAY",
    "SEC_13F_STARTUP_FRESHNESS",
    "POLICY_FIXED_DELAY",
    "PEER_DISCOVERY_FIXED_DELAY",
    "NARRATIVE_SOURCE_FIXED_DELAY",
    "DART_FIXED_DELAY",
    "DATA_INTEGRITY_MONITOR_FIXED_DELAY",
    "TELEGRAM_OUTBOX_DISPATCH_DELAY",
    "TELEGRAM_POST_STARTUP_RECALCULATION_DELAY",
)

CACHE_KEYS = (
    "COMPANY_ANALYST_CONSENSUS_CACHE_TTL",
    "YAHOO_PRICE_HISTORY_CACHE_TTL",
    "SEC_COMPANYFACTS_CACHE_TTL",
    "SEC_SUBMISSIONS_CACHE_TTL",
    "SEC_FILING_DETAIL_CACHE_TTL",
)


def read(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8")


def one(pattern: str, text: str, label: str, flags: int = 0) -> str:
    match = re.search(pattern, text, flags)
    if not match:
        raise ValueError(f"cannot extract {label}")
    return match.group(1)


def extract_env_values(root: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    application = read(root, "server-spring/bootstrap/src/main/resources/application.yml")
    compose = read(root, "docker-compose.yml")
    for key, value in re.findall(r"\$\{([A-Z0-9_]+):([^}]+)}", application):
        values[key] = value.strip().strip('"')
    # Production compose overrides application defaults.
    for key, value in re.findall(r"^\s+-\s+([A-Z0-9_]+)=([^\s#]+)", compose, re.MULTILINE):
        values[key] = value.strip().strip('"')
    return values


def extract_contracts(root: Path) -> dict[str, str | int]:
    pom = read(root, "server-spring/pom.xml")
    package = json.loads(read(root, "client/package.json"))
    decision = read(
        root,
        "server-spring/domain/src/main/java/io/macrosquare/company/domain/investment/CompanyInvestmentDecisionPolicy.java",
    )
    sector = read(
        root,
        "server-spring/domain/src/main/java/io/macrosquare/research/domain/rotation/SectorWalkForwardBacktestPolicy.java",
    )
    application = read(root, "server-spring/bootstrap/src/main/resources/application.yml")
    route_test = read(
        root,
        "server-spring/bootstrap/src/test/java/io/macrosquare/bootstrap/PublicApiRouteCoverageTest.java",
    )
    verify_home = read(root, "scripts/verify-home.sh")
    migrations = list(
        (root / "server-spring/bootstrap/src/main/resources/db/migration").glob("V*__*.sql")
    )
    migration_versions = [
        int(match.group(1))
        for path in migrations
        if (match := re.match(r"V(\d+)__", path.name))
    ]
    if not migration_versions:
        raise ValueError("cannot extract Flyway migration version")

    parent = one(
        r"<parent>.*?<artifactId>spring-boot-starter-parent</artifactId>.*?<version>([^<]+)</version>.*?</parent>",
        pom,
        "Spring Boot version",
        re.DOTALL,
    )
    return {
        "java": int(one(r"<maven\.compiler\.release>(\d+)</maven\.compiler\.release>", pom, "Java release")),
        "spring_boot": parent,
        "next": package["dependencies"]["next"],
        "decision": one(r'public static final String VERSION\s*=\s*"([^"]+)"', decision, "decision version"),
        "sector": one(r'METHODOLOGY_VERSION\s*=\s*\n?\s*"([^"]+)"', sector, "sector methodology"),
        "universe": int(one(r"expected-company-universe:\s*\$\{[^:}]+:(\d+)}", application, "company universe")),
        "calculation": int(one(r"calculation-version:\s*\$\{[^:}]+:(\d+)}", application, "calculation version")),
        "flyway": max(migration_versions),
        "routes": len(re.findall(r'"(?:GET|POST|PUT|PATCH|DELETE) /api/', route_test)),
        "smoke": int(one(r"assert result\['passed'] == (\d+)", verify_home, "smoke count")),
    }


def markdown_links(text: str) -> list[str]:
    without_fences = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
    return [match.group(1).strip() for match in re.finditer(r"!?\[[^\]]*]\(([^)]+)\)", without_fences)]


def local_link_target(source: Path, raw_target: str) -> Path | None:
    target = raw_target.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1]
    # Markdown title after a destination is outside the target for all project links.
    target = target.split(' "', 1)[0].split(" '", 1)[0]
    if not target or target.startswith(("#", "/", "mailto:")):
        return None
    parsed = urllib.parse.urlparse(target)
    if parsed.scheme or parsed.netloc:
        return None
    path = urllib.parse.unquote(parsed.path)
    if not path:
        return None
    return (source.parent / path).resolve()


def verify_statuses(root: Path) -> list[str]:
    errors: list[str] = []
    for status, documents in STATUS_DOCUMENTS.items():
        marker = f"문서 상태: **{status}**"
        for relative in documents:
            path = root / relative
            if not path.is_file():
                errors.append(f"missing {status} document: {relative}")
                continue
            if marker not in path.read_text(encoding="utf-8"):
                errors.append(f"wrong/missing status in {relative}: expected {marker}")
    return errors


def verify_links(root: Path) -> list[str]:
    errors: list[str] = []
    for relative in dict.fromkeys(LINK_ENTRYPOINTS):
        source = root / relative
        if not source.is_file():
            continue
        for raw_target in markdown_links(source.read_text(encoding="utf-8")):
            target = local_link_target(source, raw_target)
            if target is not None and not target.exists():
                errors.append(f"broken link: {relative} -> {raw_target}")
    return errors


def require(document: str, token: str, root: Path, errors: list[str], label: str) -> None:
    if token not in read(root, document):
        errors.append(f"contract drift in {document}: missing {label} ({token})")


def verify_contracts(root: Path) -> list[str]:
    errors: list[str] = []
    try:
        contracts = extract_contracts(root)
        env = extract_env_values(root)
    except (OSError, KeyError, TypeError, ValueError) as error:
        return [f"contract extraction failed: {error}"]

    require("docs/README.md", f"Java {contracts['java']}", root, errors, "Java version")
    require("docs/README.md", f"Spring Boot {contracts['spring_boot']}", root, errors, "Spring Boot version")
    require("docs/README.md", f"Next.js {contracts['next']}", root, errors, "Next.js version")
    require("docs/development/SYSTEM-ARCHITECTURE.md", f"V1~V{contracts['flyway']}", root, errors, "Flyway head")
    require("docs/development/DATA-CONTRACTS-AND-LINEAGE.md", f"V1~V{contracts['flyway']}", root, errors, "Flyway head")
    for document in (
        "docs/finance/FINANCIAL-DECISION-MODEL.md",
        "docs/finance/COMPANY-SCORECARD.md",
        "docs/INVESTMENT-DECISION-STACK.md",
    ):
        require(document, str(contracts["decision"]), root, errors, "company decision policy version")
    for document in (
        "docs/finance/FINANCIAL-DECISION-MODEL.md",
        "docs/SECTOR-ROTATION-METHODOLOGY-V2.md",
    ):
        require(document, str(contracts["sector"]), root, errors, "sector methodology version")
    for document in (
        "docs/finance/DATA-SOURCES-AND-FRESHNESS.md",
        "docs/development/DATA-CONTRACTS-AND-LINEAGE.md",
    ):
        require(document, str(contracts["universe"]), root, errors, "company universe")
        require(document, str(contracts["calculation"]), root, errors, "calculation version")
    require("docs/development/API-SURFACE.md", f"**{contracts['routes']}개**", root, errors, "public route count")
    require("docs/development/API-SURFACE.md", f"**{contracts['smoke']}개**", root, errors, "smoke count")
    require("server-spring/README.md", f"{contracts['routes']}개", root, errors, "public route count")
    require("server-spring/README.md", f"{contracts['smoke']}개", root, errors, "smoke count")

    schedule_document = "docs/development/SCHEDULERS-CONCURRENCY-IDEMPOTENCY.md"
    for key in SCHEDULE_KEYS:
        if key not in env:
            errors.append(f"cannot extract schedule config: {key}")
        else:
            require(schedule_document, f"{key}={env[key]}", root, errors, f"schedule {key}")
    cache_document = "docs/finance/DATA-SOURCES-AND-FRESHNESS.md"
    for key in CACHE_KEYS:
        if key not in env:
            errors.append(f"cannot extract cache config: {key}")
        else:
            require(cache_document, f"{key}={env[key]}", root, errors, f"cache {key}")
    return errors


def verify_decision_registry(root: Path) -> list[str]:
    registry = read(root, "docs/DECISION-RECORDS.md")
    records = sorted((root / "server-spring/docs").glob("ADR-*.md"))
    records += sorted((root / "docs").glob("ADR-*.md"))
    records += sorted((root / "docs").glob("PDR-*.md"))
    return [
        f"decision record not registered: {record.relative_to(root)}"
        for record in records
        if record.name not in registry
    ]


def verify_incident_catalog_ids(root: Path) -> list[str]:
    document = "docs/development/INCIDENT-RECURRENCE-PREVENTION.md"
    identifiers = re.findall(
        r"^\|\s*((?:FIN|DATA|OPS|NOTI|DOC)-\d+)\s*\|",
        read(root, document),
        re.MULTILINE,
    )
    duplicates = sorted({value for value in identifiers if identifiers.count(value) > 1})
    return [f"duplicate incident catalog id in {document}: {value}" for value in duplicates]


def verify_repository(root: Path = ROOT) -> list[str]:
    return [
        *verify_statuses(root),
        *verify_links(root),
        *verify_contracts(root),
        *verify_decision_registry(root),
        *verify_incident_catalog_ids(root),
    ]


def main() -> int:
    errors = verify_repository()
    if errors:
        for error in errors:
            print(f"documentation contract failed: {error}", file=sys.stderr)
        return 1
    contracts = extract_contracts(ROOT)
    managed = sum(len(documents) for documents in STATUS_DOCUMENTS.values())
    print(
        "documentation contracts: OK "
        f"managed={managed} links={len(set(LINK_ENTRYPOINTS))} "
        f"java={contracts['java']} spring={contracts['spring_boot']} next={contracts['next']} "
        f"flyway=V{contracts['flyway']} routes={contracts['routes']} smoke={contracts['smoke']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
