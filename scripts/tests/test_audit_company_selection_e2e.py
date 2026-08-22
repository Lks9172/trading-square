from __future__ import annotations

import datetime as dt
import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "audit-company-selection-e2e.py"
SPEC = importlib.util.spec_from_file_location("audit_company_selection_e2e", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class CompanySelectionAuditTest(unittest.TestCase):

    def test_metric_view_fails_scores_closed_but_keeps_independent_current_price_signals(self) -> None:
        now = dt.datetime(2026, 8, 8, 12, tzinfo=dt.timezone.utc)
        row = {
            "updated_at": "2026-08-08T11:00:00+00:00",
            "score_comparable": False,
            "price_bundle_complete": True,
            "total_score": 88,
            "buy_score": 79,
            "confirmed_bottom_score": 72,
            "confirmed_bottom_state": "CONVICTION",
            "price_bottom_score": 68,
            "volume_confirmation_score": 75,
            "failure_risk_score": 20,
        }

        value = MODULE.metric_view(row, now)

        self.assertIsNone(value["totalScore"])
        self.assertIsNone(value["buyScore"])
        self.assertEqual(72, value["confirmedBottomScore"])
        self.assertEqual("확신", value["confirmedBottomState"])

    def test_metric_view_rejects_stale_and_future_rows(self) -> None:
        now = dt.datetime(2026, 8, 8, 12, tzinfo=dt.timezone.utc)
        baseline = {
            "score_comparable": True,
            "price_bundle_complete": True,
            "total_score": 88,
            "buy_score": 79,
            "confirmed_bottom_score": 72,
            "confirmed_bottom_state": "CANDIDATE",
            "price_bottom_score": 68,
            "volume_confirmation_score": 75,
            "failure_risk_score": 20,
        }
        for updated_at in ("2026-08-08T09:59:59+00:00", "2026-08-08T12:05:01+00:00"):
            value = MODULE.metric_view({**baseline, "updated_at": updated_at}, now)
            self.assertTrue(all(item is None for item in value.values()))

    def test_buy_order_uses_current_score_then_canonical_ticker(self) -> None:
        items = [
            {"ticker": "BRK.B", "buyScore": 70},
            {"ticker": "AAPL", "buyScore": None},
            {"ticker": "AMD", "buyScore": 70},
        ]
        self.assertEqual(["AMD", "BRK-B", "AAPL"], MODULE.buy_order(items))


if __name__ == "__main__":
    unittest.main()
