from __future__ import annotations

import importlib.util
import datetime as dt
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "audit-home-observability.py"
SPEC = importlib.util.spec_from_file_location("audit_home_observability", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class AuditEvidenceClassificationTest(unittest.TestCase):
    def test_http_error_rate_excludes_deployment_readiness_probes(self) -> None:
        queries = AUDIT.http_metric_queries(24)

        self.assertIn('uri!~"/actuator.*"', queries["requests"])
        self.assertIn('uri!~"/actuator.*"', queries["errors"])
        self.assertIn('uri=~"/actuator/health.*"', queries["healthProbeErrors"])

    def test_successful_broken_pipe_is_classified_as_client_disconnect(self) -> None:
        self.assertTrue(AUDIT.is_client_disconnect_span({
            "status": "200",
            "otel.status_description": "Broken pipe",
            "error": True,
        }))

    def test_broken_pipe_does_not_hide_a_server_error(self) -> None:
        self.assertFalse(AUDIT.is_client_disconnect_span({
            "status": "500",
            "otel.status_description": "Broken pipe",
            "error": True,
        }))

    def test_application_exception_is_not_a_client_disconnect(self) -> None:
        self.assertFalse(AUDIT.is_client_disconnect_span({
            "status": "200",
            "otel.status_description": "IllegalStateException",
            "error": True,
        }))

    def test_naaim_only_gap_is_optional_when_other_sentiment_data_persisted(self) -> None:
        self.assertTrue(AUDIT.is_optional_collection_gap({
            "source": "SENTIMENT",
            "status": "DEGRADED",
            "failure_keys": "NAAIM_EXPOSURE",
            "collected_count": 2,
            "persisted_count": 2,
        }))

    def test_new_sentiment_failure_remains_critical(self) -> None:
        self.assertFalse(AUDIT.is_optional_collection_gap({
            "source": "SENTIMENT",
            "status": "DEGRADED",
            "failure_keys": "NAAIM_EXPOSURE,FEAR_GREED",
            "collected_count": 2,
            "persisted_count": 2,
        }))

    def test_optional_source_without_persisted_fallback_remains_critical(self) -> None:
        self.assertFalse(AUDIT.is_optional_collection_gap({
            "source": "SENTIMENT",
            "status": "DEGRADED",
            "failure_keys": ["NAAIM_EXPOSURE"],
            "collected_count": 0,
            "persisted_count": 0,
        }))

    def test_optional_gap_with_partial_persistence_remains_critical(self) -> None:
        self.assertFalse(AUDIT.is_optional_collection_gap({
            "source": "SENTIMENT",
            "status": "DEGRADED",
            "failure_keys": ["NAAIM_EXPOSURE"],
            "collected_count": 2,
            "persisted_count": 1,
        }))

    def test_collection_staleness_uses_each_sources_real_schedule(self) -> None:
        self.assertTrue(AUDIT.is_collection_stale({"source": "YAHOO", "age_seconds": 1801}))
        self.assertFalse(AUDIT.is_collection_stale({"source": "FRED", "age_seconds": 4 * 3600}))
        self.assertTrue(AUDIT.is_collection_stale({"source": "FRED", "age_seconds": 12 * 3600 + 1}))

    def test_recent_candidate_scan_start_is_active_not_failed(self) -> None:
        result = AUDIT.scheduler_durations(
            ["2026-08-07T09:20:00.000Z INFO scan started (trigger=weekday)"],
            observed_at=dt.datetime(2026, 8, 7, 9, 25, tzinfo=dt.timezone.utc),
        )

        self.assertEqual(1, len(result["activeStarts"]))
        self.assertEqual([], result["stalledStarts"])
        self.assertEqual([], result["unmatchedStarts"])

    def test_candidate_scan_is_stalled_only_after_bounded_window(self) -> None:
        result = AUDIT.scheduler_durations(
            ["2026-08-07T09:00:00.000Z INFO scan started (trigger=weekday)"],
            observed_at=dt.datetime(2026, 8, 7, 9, 21, tzinfo=dt.timezone.utc),
        )

        self.assertEqual([], result["activeStarts"])
        self.assertEqual(1, len(result["stalledStarts"]))
        self.assertEqual(result["stalledStarts"], result["unmatchedStarts"])

    def test_candidate_start_from_replaced_process_is_not_currently_stalled(self) -> None:
        result = AUDIT.scheduler_durations(
            ["2026-08-17T01:00:00.000Z INFO scan started (trigger=weekday)"],
            observed_at=dt.datetime(2026, 8, 17, 1, 30, tzinfo=dt.timezone.utc),
            process_started_at=dt.datetime(2026, 8, 17, 1, 20, tzinfo=dt.timezone.utc),
        )

        self.assertEqual([], result["activeStarts"])
        self.assertEqual([], result["stalledStarts"])
        self.assertEqual(["2026-08-17T01:00:00+00:00"], result["interruptedByRestart"])

    def test_failed_and_skipped_candidate_scans_close_active_lifecycle(self) -> None:
        failed = AUDIT.scheduler_durations([
            "2026-08-07T09:00:00.000Z INFO scan started (trigger=weekday)",
            "2026-08-07T09:01:00.000Z ERROR scan failed (trigger=weekday)",
        ], observed_at=dt.datetime(2026, 8, 7, 9, 2, tzinfo=dt.timezone.utc))
        skipped = AUDIT.scheduler_durations([
            "2026-08-07T09:00:00.000Z INFO scan started (trigger=weekday)",
            "2026-08-07T09:00:01.000Z INFO scan skipped because another instance owns the task (trigger=weekday)",
        ], observed_at=dt.datetime(2026, 8, 7, 9, 2, tzinfo=dt.timezone.utc))

        self.assertEqual(1, len(failed["failures"]))
        self.assertEqual([], failed["activeStarts"])
        self.assertEqual([], skipped["failures"])
        self.assertEqual([], skipped["activeStarts"])

    def test_detects_cross_job_provider_overlap_and_clears_completed_jobs(self) -> None:
        overlaps = AUDIT.provider_heavy_scheduler_overlaps([
            "2026-08-17T01:00:00Z INFO Company research summary refresh started",
            "2026-08-17T01:01:00Z INFO Analyst history run started (trigger=startup-seed)",
            "2026-08-17T01:02:00Z INFO Company research summaries refreshed (attempted=277, written=277)",
            "2026-08-17T01:03:00Z INFO Spring investment entry notification scan started (trigger=post-startup-candidate-recalculation)",
            "2026-08-17T01:04:00Z INFO Analyst history completed (trigger=startup-seed)",
            "2026-08-17T01:05:00Z INFO Spring investment entry notification scan completed (trigger=post-startup-candidate-recalculation)",
        ])

        self.assertEqual(2, len(overlaps))
        self.assertEqual("analyst-history", overlaps[0]["startedJob"])
        self.assertEqual("company-summary", overlaps[0]["activeJob"])
        self.assertEqual("candidate-scan", overlaps[1]["startedJob"])
        self.assertEqual("analyst-history", overlaps[1]["activeJob"])

    def test_does_not_report_staggered_provider_jobs(self) -> None:
        overlaps = AUDIT.provider_heavy_scheduler_overlaps([
            "2026-08-17T01:00:00Z INFO Company research summary refresh started",
            "2026-08-17T01:02:00Z INFO Company research summaries refreshed (attempted=277, written=277)",
            "2026-08-17T01:03:00Z INFO Analyst history run started (trigger=startup-seed)",
            "2026-08-17T01:04:00Z INFO Analyst history completed (trigger=startup-seed)",
        ])

        self.assertEqual([], overlaps)


if __name__ == "__main__":
    unittest.main()
