from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "verify-documentation.py"
SPEC = importlib.util.spec_from_file_location("verify_documentation", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class VerifyDocumentationTest(unittest.TestCase):
    def test_markdown_links_ignores_fenced_examples(self) -> None:
        text = "[real](guide.md)\n```md\n[example](missing.md)\n```\n[web](https://example.com)"
        self.assertEqual(["guide.md", "https://example.com"], MODULE.markdown_links(text))

    def test_local_link_target_resolves_relative_path_and_anchor(self) -> None:
        source = Path("/tmp/project/docs/development/readme.md")
        actual = MODULE.local_link_target(source, "../finance/model.md#score")
        self.assertEqual(Path("/tmp/project/docs/finance/model.md").resolve(), actual)
        self.assertIsNone(MODULE.local_link_target(source, "https://example.com/a"))
        self.assertIsNone(MODULE.local_link_target(source, "#local"))

    def test_verify_links_reports_missing_local_target(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "README.md").write_text("[missing](docs/nope.md)", encoding="utf-8")
            previous = MODULE.LINK_ENTRYPOINTS
            try:
                MODULE.LINK_ENTRYPOINTS = ("README.md",)
                self.assertEqual(
                    ["broken link: README.md -> docs/nope.md"],
                    MODULE.verify_links(root),
                )
            finally:
                MODULE.LINK_ENTRYPOINTS = previous

    def test_repository_contracts_are_current(self) -> None:
        self.assertEqual([], MODULE.verify_repository(MODULE.ROOT))

    def test_incident_catalog_rejects_duplicate_ids(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "docs/development/INCIDENT-RECURRENCE-PREVENTION.md"
            path.parent.mkdir(parents=True)
            path.write_text(
                "| ID | incident |\n|---|---|\n| OPS-001 | one |\n| OPS-001 | two |\n",
                encoding="utf-8",
            )

            self.assertEqual(
                ["duplicate incident catalog id in docs/development/INCIDENT-RECURRENCE-PREVENTION.md: OPS-001"],
                MODULE.verify_incident_catalog_ids(root),
            )


if __name__ == "__main__":
    unittest.main()
