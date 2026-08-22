#!/usr/bin/env python3
"""Pure, fail-full classifier for home deployment inputs."""

from __future__ import annotations

import argparse
import json
import re

RELEASE_PATTERN = re.compile(
    r"(^|[ /\t])(pom\.xml|bootstrap/src/main/resources/db/migration/|"
    r"adapter/out/persistence/|Postgres.*IntegrationTest)"
)


def classify(
    *,
    server: bool,
    client: bool,
    scripts: bool,
    docs: bool,
    observability: bool,
    compose: bool,
    readme: bool,
    server_changes: str = "",
) -> dict[str, object]:
    if compose or observability or (server and client):
        scope = "full"
    elif server:
        scope = "server"
    elif client:
        scope = "client"
    elif scripts:
        scope = "scripts"
    elif docs or readme:
        scope = "docs"
    else:
        scope = "verify"
    return {
        "scope": scope,
        "serverRelease": bool(server and RELEASE_PATTERN.search(server_changes)),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    for name in ("server", "client", "scripts", "docs", "observability", "compose", "readme"):
        parser.add_argument(f"--{name}", action="store_true")
    parser.add_argument("--server-changes", default="")
    args = parser.parse_args()
    print(json.dumps(classify(**vars(args)), separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
