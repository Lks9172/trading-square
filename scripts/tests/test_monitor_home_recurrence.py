from __future__ import annotations

import datetime as dt
import importlib.util
import pathlib
import sys
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "monitor-home-recurrence.py"
SPEC = importlib.util.spec_from_file_location("monitor_home_recurrence", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MONITOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MONITOR
SPEC.loader.exec_module(MONITOR)
UTC = dt.timezone.utc
NOW = dt.datetime(2026, 8, 8, 1, 0, tzinfo=UTC)


class RealtimeRecurrenceMonitorTest(unittest.TestCase):
    def test_parses_application_error_and_redacts_token(self) -> None:
        synthetic_token = "123456789:" + ("A" * 35)
        line = (
            "2026-08-08T01:00:00.000Z ERROR 7 --- [macrosquare-server-spring] "
            "[task-1] [abc-def] i.m.Sample : fetch failed token "
            f"{synthetic_token}"
        )

        result = MONITOR.parse_spring_error(line, "1")

        self.assertIsNotNone(result)
        self.assertNotIn("AAAAAAAA", result.sample)
        self.assertIn("<redacted-token>", result.sample)

    def test_durable_data_integrity_alert_is_not_duplicated(self) -> None:
        line = (
            "2026-08-08T01:00:00.000Z ERROR 7 --- [macrosquare-server-spring] "
            "[task-1] [abc-def] i.m.DataIntegrityScheduler : "
            "Data integrity recurrence detected (fingerprint=abc, violations=[])"
        )

        self.assertIsNone(MONITOR.parse_spring_error(line, "1"))

    def test_parses_hikari_thread_starvation_as_a_runtime_recurrence(self) -> None:
        line = (
            "2026-08-08T18:35:35.439Z WARN 7 --- [macrosquare-server-spring] "
            "[HikariPool-1:housekeeper] [ ] com.zaxxer.hikari.pool.HikariPool : "
            "HikariPool-1 - Thread starvation or clock leap detected "
            "(housekeeper delta=10m13s169ms)."
        )

        result = MONITOR.parse_spring_runtime_warning(line, "2")

        self.assertIsNotNone(result)
        self.assertEqual("RUNTIME_THREAD_STARVATION", result.kind)
        self.assertIn("scheduler 지연", result.sample)
        self.assertNotIn("10m13", result.fingerprint)

    def test_same_active_error_alerts_once(self) -> None:
        first = MONITOR.event("APP_ERROR", "sample", "same", "first", NOW)
        state, alerts = MONITOR.evaluate({}, [first], NOW)
        MONITOR.mark_alerted(state, alerts, NOW)
        repeated = MONITOR.event(
            "APP_ERROR", "sample", "same", "again", NOW + dt.timedelta(minutes=1))

        next_state, repeated_alerts = MONITOR.evaluate(
            state, [repeated], NOW + dt.timedelta(minutes=1))

        self.assertEqual(1, len(alerts))
        self.assertEqual([], repeated_alerts)
        self.assertEqual(2, next(iter(next_state["active"].values()))["occurrences"])

    def test_error_rearms_after_five_quiet_minutes(self) -> None:
        first = MONITOR.event("APP_ERROR", "sample", "same", "first", NOW)
        state, alerts = MONITOR.evaluate({}, [first], NOW)
        MONITOR.mark_alerted(state, alerts, NOW)
        recurrence_at = NOW + dt.timedelta(minutes=6)
        recurrence = MONITOR.event("APP_ERROR", "sample", "same", "again", recurrence_at)

        next_state, repeated_alerts = MONITOR.evaluate(state, [recurrence], recurrence_at)

        self.assertEqual(1, len(repeated_alerts))
        self.assertEqual(1, next(iter(next_state["active"].values()))["occurrences"])

    def test_overlapping_loki_window_deduplicates_the_same_event(self) -> None:
        value = MONITOR.event("APP_ERROR", "sample", "same", "first", NOW)
        state, alerts = MONITOR.evaluate({}, [value], NOW)
        MONITOR.mark_alerted(state, alerts, NOW)

        next_state, repeated_alerts = MONITOR.evaluate(
            state, [value], NOW + dt.timedelta(minutes=1))

        self.assertEqual([], repeated_alerts)
        self.assertEqual(1, next(iter(next_state["active"].values()))["occurrences"])

    def test_monitor_source_failure_has_stable_fingerprint_without_secret(self) -> None:
        first = MONITOR.source_failure_event("loki", RuntimeError("secret one"), NOW)
        second = MONITOR.source_failure_event(
            "loki", RuntimeError("different secret"), NOW + dt.timedelta(minutes=1))

        self.assertEqual(first.fingerprint, second.fingerprint)
        self.assertNotIn("secret", first.sample)

    def test_runtime_starting_within_rollout_grace_is_not_an_incident(self) -> None:
        state = {
            "Status": "running",
            "Health": {"Status": "starting"},
            "StartedAt": (NOW - dt.timedelta(seconds=30)).isoformat(),
        }

        result = MONITOR.runtime_health_event(state, NOW)

        self.assertIsNone(result)

    def test_runtime_stuck_starting_after_grace_is_an_incident(self) -> None:
        state = {
            "Status": "running",
            "Health": {"Status": "starting"},
            "StartedAt": (NOW - dt.timedelta(minutes=4)).isoformat(),
        }

        result = MONITOR.runtime_health_event(state, NOW)

        self.assertIsNotNone(result)
        self.assertEqual("RUNTIME_HEALTH", result.kind)

    def test_unhealthy_runtime_is_an_incident_even_during_rollout_grace(self) -> None:
        state = {
            "Status": "running",
            "Health": {"Status": "unhealthy"},
            "StartedAt": (NOW - dt.timedelta(seconds=30)).isoformat(),
        }

        result = MONITOR.runtime_health_event(state, NOW)

        self.assertIsNotNone(result)
        self.assertIn("unhealthy", result.sample)

    def test_paused_runtime_is_an_incident_even_when_health_is_still_green(self) -> None:
        state = {
            "Status": "running",
            "Paused": True,
            "Health": {"Status": "healthy"},
            "StartedAt": (NOW - dt.timedelta(hours=1)).isoformat(),
        }

        result = MONITOR.runtime_health_event(state, NOW)

        self.assertIsNotNone(result)
        self.assertEqual("RUNTIME_PAUSED", result.kind)
        self.assertIn("pause", result.sample)


if __name__ == "__main__":
    unittest.main()
