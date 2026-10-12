#!/usr/bin/env python3
"""Unit tests of rush_judge.py, standard library only (docs/TEST.md TC-PLAY-028, TC-PLAY-119):

    python3 -m unittest discover -s client-fabric/gametest-env -p 'test_*.py'

Each test writes one good run of job rush into a temporary directory, two launches' worth, breaks one
thing in it and expects the judge to name exactly that.
"""

from __future__ import annotations

import gzip
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

JUDGE = Path(__file__).with_name("rush_judge.py")
STEPS = ["Q0a", "Q0b", "Q0c"] + [f"Q{n}" for n in range(1, 10)]
START = datetime(2026, 10, 12, 8, 0, 0, tzinfo=timezone.utc)
LOBBY = "11111111-1111-1111-1111-111111111111"


def load_judge():
    spec = importlib.util.spec_from_file_location("rush_judge_under_test", JUDGE)
    judge = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(judge)
    return judge


def report(**statuses: str) -> str:
    """A report of every step, all PASS unless a keyword says otherwise."""
    lines = []
    for step in STEPS:
        evidence = f"evidence of {step}"
        if step == "Q0b":
            evidence = "requested at tick 0; screens [net.minecraft.client.gui.screens.LevelLoadingScreen ticks 3..5]; ready at tick 6"
        lines.append(f"rush {step} {statuses.get(step, 'PASS')} {evidence}")
    return "\n".join(lines) + "\n"


def ts(seconds: float) -> str:
    """A ledger timestamp the way Instant#toString writes it, nanoseconds and all."""
    moment = START + timedelta(seconds=seconds)
    return moment.strftime("%Y-%m-%dT%H:%M:%S.%f") + "123Z"


class Ledger:
    """The event ledger of the sample server for a run of `launches` launches that each play one round alone."""

    def __init__(self, launches: int = 2) -> None:
        self.events: list[dict] = []
        self.add(0, "play_instance_created", kind_id="rush:lobby", instance_id=LOBBY, chunk_radius=1)
        self.add(0.1, "play_instance_opened", kind_id="rush:lobby", instance_id=LOBBY, prepare_ms=40)
        self.add(0.2, "play_instance_started", kind_id="rush:lobby", instance_id=LOBBY, players=0)
        for launch in range(launches):
            self.launch(launch)

    def add(self, at: float, event: str, **attributes) -> dict:
        line = {"ts": ts(at), "event": event, **attributes}
        self.events.append(line)
        return line

    def launch(self, launch: int) -> None:
        base = 100 + 200 * launch
        arena = f"2222222{launch}-2222-2222-2222-222222222222"
        connection = f"3333333{launch}-3333-3333-3333-333333333333"
        self.add(base, "client_verified", connection_id=connection)
        self.add(base + 1, "play_scene_entered", connection_id=connection, scene_id="rush:lobby", scene_seq=1)
        self.add(base + 1.5, "play_scene_ready", connection_id=connection, scene_seq=1)
        self.add(base + 5, "play_instance_created", kind_id="rush:round", instance_id=arena, chunk_radius=2)
        self.add(base + 5.05, "play_instance_opened", kind_id="rush:round", instance_id=arena, prepare_ms=49)
        self.add(base + 5.1, "play_instance_moved", instance_id=arena, connection_id=connection, from_instance_id=LOBBY)
        self.add(base + 5.4, "play_scene_ready", connection_id=connection, scene_seq=2)
        self.add(base + 5.5, "play_instance_started", kind_id="rush:round", instance_id=arena, players=1)
        self.add(base + 50.5, "rush_round_ended", round_id=f"rush:round_{launch + 1}", players=1, winner="Player",
                 cause="time_limit", duration_ms=45000)
        self.add(base + 50.5, "play_instance_ended", kind_id="rush:round", instance_id=arena, cause="time_limit",
                 players=1, duration_ms=45000)
        self.add(base + 53, "play_instance_moved", instance_id=LOBBY, connection_id=connection, from_instance_id=arena)
        self.add(base + 53.3, "play_scene_ready", connection_id=connection, scene_seq=3)
        self.add(base + 53.1, "play_instance_closed", kind_id="rush:round", instance_id=arena, cause="time_limit",
                 players=0, duration_ms=47600, evacuated=0, kicked=0, settle_timeout="false")

    def of(self, event: str, kind_id: str | None = None) -> list[dict]:
        return [line for line in self.events
                if line["event"] == event and (kind_id is None or line.get("kind_id") == kind_id)]

    def text(self) -> str:
        return "".join(json.dumps(line, separators=(",", ":")) + "\n" for line in self.events)


GOOD_SERVER_LOG = """\
> Task :server-app:runRush
08:00:00.001 [main] INFO  i.g.b.synaxis.app.PluginHost - Plugin enabled: plugin_id=rush version=0.1.0 enable_ms=120
08:00:00.002 [main] INFO  i.g.b.synaxis.app.Main - synaxis server-app listening on 0.0.0.0:25567
08:01:40.500 [Ms-Tick-0] WARN  plugin.rush - a warning is not an error
"""

GOOD_SESSION_LOG = """\
fake sessionserver listening on http://127.0.0.1:40000, mode record
1 POST /session/minecraft/join -> 204
2 GET /session/minecraft/hasJoined?username=Player&serverId=abc -> 200
3 POST /session/minecraft/join -> 204
4 GET /session/minecraft/hasJoined?username=Player&serverId=def -> 200
"""


class JudgeTest(unittest.TestCase):

    def setUp(self) -> None:
        self.judge = load_judge()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.reports = [self.directory / "dev.txt", self.directory / "production.txt"]
        for path in self.reports:
            path.write_text(report(), encoding="utf-8")
        self.ledger = Ledger()
        self.ledger_dir = self.directory / "logs"
        self.ledger_dir.mkdir()
        self.server_log = self.directory / "rush-server.log"
        self.server_log.write_text(GOOD_SERVER_LOG, encoding="utf-8")
        self.session_log = self.directory / "sessionserver.log"
        self.session_log.write_text(GOOD_SESSION_LOG, encoding="utf-8")
        self.client_source = self.directory / "java"
        (self.client_source / "play").mkdir(parents=True)
        (self.client_source / "play" / "PlayClient.java").write_text(
            "// Brushes and rushes are not the word; neither is rush_like nor rush2\nfinal class PlayClient {}\n",
            encoding="utf-8")

    def write_ledger(self) -> None:
        (self.ledger_dir / "events-local-20261012.jsonl").write_text(self.ledger.text(), encoding="utf-8")

    def verdict(self):
        self.write_ledger()
        return self.judge.judge(self.reports, ledger_dir=self.ledger_dir, server_log=self.server_log,
                                session_log=self.session_log, client_source=self.client_source)

    def problems(self) -> list[str]:
        return self.verdict().problems

    def assert_one_problem(self, *fragments: str) -> None:
        problems = self.problems()
        self.assertEqual(len(problems), 1, problems)
        for fragment in fragments:
            self.assertIn(fragment, problems[0])

    # ---- the good run

    def test_a_good_run_passes(self) -> None:
        self.assertEqual(self.problems(), [])

    def test_the_prepare_times_and_the_screens_of_q0b_go_into_the_notes(self) -> None:
        notes = "\n".join(self.verdict().notes)
        self.assertIn("prepare_ms 49", notes)
        self.assertEqual(notes.count("prepare_ms 49"), 2, notes)
        self.assertIn("LevelLoadingScreen ticks 3..5", notes)

    def test_a_day_already_gzipped_is_read_too(self) -> None:
        events = self.ledger.text().splitlines(keepends=True)
        with gzip.open(self.ledger_dir / "events-local-20261011.jsonl.gz", "wt", encoding="utf-8") as day:
            day.writelines(events[:10])
        (self.ledger_dir / "events-local-20261012.jsonl").write_text("".join(events[10:]), encoding="utf-8")
        problems = self.judge.judge(self.reports, ledger_dir=self.ledger_dir, server_log=self.server_log,
                                    session_log=self.session_log, client_source=self.client_source).problems
        self.assertEqual(problems, [])

    # ---- the reports

    def test_a_failed_step_fails(self) -> None:
        self.reports[0].write_text(report(Q5="FAIL"), encoding="utf-8")
        self.assert_one_problem("rush Q5 FAIL")

    def test_a_skipped_step_fails(self) -> None:
        for step in ["Q0a", "Q3", "Q9"]:
            with self.subTest(step=step):
                self.reports[1].write_text(report(**{step: "SKIP"}), encoding="utf-8")
                self.assert_one_problem(f"{step} was skipped")

    def test_a_missing_report_fails(self) -> None:
        self.reports[1].unlink()
        self.assert_one_problem(f"{self.reports[1]}: no report")

    def test_no_report_named_fails(self) -> None:
        self.reports = []
        self.assertIn("no report named", self.problems())

    def test_a_missing_line_fails(self) -> None:
        text = "".join(line + "\n" for line in report().splitlines() if not line.startswith("rush Q7 "))
        self.reports[0].write_text(text, encoding="utf-8")
        self.assert_one_problem("no line for Q7")

    def test_a_doubled_line_fails(self) -> None:
        self.reports[0].write_text(report() + "rush Q0a PASS again\n", encoding="utf-8")
        self.assert_one_problem("Q0a a second time")

    def test_a_line_of_another_shape_fails(self) -> None:
        for line in ["rush Q0a OK fine", "rush Q10 PASS no such step", "Q0a PASS no prefix"]:
            with self.subTest(line=line):
                self.reports[0].write_text(report() + line + "\n", encoding="utf-8")
                self.assert_one_problem("not a step line")

    # ---- the arenas in the ledger

    def test_an_arena_whose_states_come_out_of_order_fails(self) -> None:
        started = self.ledger.of("play_instance_started", "rush:round")[0]
        opened = self.ledger.of("play_instance_opened", "rush:round")[0]
        started["ts"], opened["ts"] = opened["ts"], started["ts"]
        self.assert_one_problem("out of order", started["instance_id"])

    def test_an_arena_without_its_closed_line_fails(self) -> None:
        self.ledger.events.remove(self.ledger.of("play_instance_closed", "rush:round")[1])
        problems = self.problems()
        self.assertTrue(any("play_instance_closed 0 times" in problem for problem in problems), problems)

    def test_an_arena_closed_16_s_after_it_ended_fails(self) -> None:
        closed = self.ledger.of("play_instance_closed", "rush:round")[0]
        closed["ts"] = ts(100 + 50.5 + 16)
        self.assert_one_problem("16.0 s from ended to closed")

    def test_a_prepare_time_over_1000_ms_fails(self) -> None:
        self.ledger.of("play_instance_opened", "rush:round")[1]["prepare_ms"] = 1200
        self.assert_one_problem("prepare_ms 1200")

    def test_an_arena_more_than_the_launches_fails(self) -> None:
        self.ledger.launch(2)
        problems = self.problems()
        self.assertTrue(any("3 arenas" in problem for problem in problems), problems)

    def test_a_round_closed_by_the_settle_timeout_is_not_a_normal_end(self) -> None:
        self.ledger.of("play_instance_closed", "rush:round")[0]["settle_timeout"] = "true"
        self.assert_one_problem("normal end rate 1/2")

    # ---- moves and readiness

    def test_a_ready_6_s_after_the_move_fails(self) -> None:
        moved = self.ledger.of("play_instance_moved")[0]
        ready = [line for line in self.ledger.of("play_scene_ready") if line["scene_seq"] == 2][0]
        ready["ts"] = ts(100 + 5.1 + 6)
        self.assert_one_problem("6.0 s after", moved["connection_id"])

    def test_a_move_no_ready_follows_fails(self) -> None:
        last = self.ledger.of("play_scene_ready")[-1]
        self.ledger.events.remove(last)
        self.ledger.add(400, "play_scene_ready", connection_id="44444444-4444-4444-4444-444444444444", scene_seq=1)
        self.assert_one_problem("no play_scene_ready after", last["connection_id"])

    def test_fewer_readies_than_two_a_launch_fails(self) -> None:
        self.ledger.events = [line for line in self.ledger.events
                              if not (line["event"] in ("play_scene_ready", "play_instance_moved")
                                      and line["ts"] >= ts(300))]
        problems = self.problems()
        self.assertTrue(any("play_scene_ready 3 times, expected at least 4" in problem for problem in problems), problems)

    # ---- rush_round_ended, the handshake and the sessionserver

    def test_three_rounds_ended_fail(self) -> None:
        self.ledger.add(500, "rush_round_ended", round_id="rush:round_3", players=1, winner="Player",
                        cause="time_limit", duration_ms=45000)
        self.assert_one_problem("rush_round_ended 3 times, expected 2")

    def test_a_round_ended_by_its_objective_fails(self) -> None:
        self.ledger.of("rush_round_ended")[0]["cause"] = "objective"
        self.assert_one_problem("cause=objective")

    def test_a_round_of_two_players_fails(self) -> None:
        self.ledger.of("rush_round_ended")[1]["players"] = 2
        self.assert_one_problem("players=2")

    def test_a_connection_not_verified_or_rejected_fails(self) -> None:
        self.ledger.events.remove(self.ledger.of("client_verified")[0])
        self.assert_one_problem("client_verified 1 times, expected 2")
        self.ledger.add(10, "client_verified", connection_id="x")
        self.ledger.add(11, "client_rejected", connection_id="y", reason="synaxis:client-mod-outdated")
        self.assert_one_problem("client_rejected 1 times, expected 0")

    def test_a_join_the_sessionserver_did_not_see_fails(self) -> None:
        self.session_log.write_text(GOOD_SESSION_LOG.replace("3 POST /session/minecraft/join -> 204\n", ""),
                                    encoding="utf-8")
        self.assert_one_problem("sessionserver join 1, expected 2")

    def test_a_has_joined_not_answered_200_fails(self) -> None:
        self.session_log.write_text(GOOD_SESSION_LOG.replace("serverId=def -> 200", "serverId=def -> 204"),
                                    encoding="utf-8")
        self.assert_one_problem("hasJoined 200 1 of 2")

    # ---- the server log and the client source

    def test_an_error_line_in_the_server_log_fails(self) -> None:
        for logger in ["plugin.rush", "i.g.b.synaxis.app.scene.SceneService"]:
            with self.subTest(logger=logger):
                self.server_log.write_text(
                    GOOD_SERVER_LOG + f"08:02:00.000 [Ms-Tick-0] ERROR {logger} - something broke\n", encoding="utf-8")
                self.assert_one_problem("ERROR", logger)

    def test_a_plugin_channel_handler_failure_fails(self) -> None:
        self.server_log.write_text(GOOD_SERVER_LOG + "08:02:00.000 [Ms-Tick-0] WARN  i.g.b.s.a.PluginChannelRouter - "
                                   "Plugin channel handler failed: plugin_id=rush channel=rush:x connection_id=z\n",
                                   encoding="utf-8")
        self.assert_one_problem("Plugin channel handler failed")

    def test_rush_in_the_client_source_fails(self) -> None:
        (self.client_source / "play" / "Hud.java").write_text("// draws the rush timer\nfinal class Hud {}\n",
                                                              encoding="utf-8")
        self.assert_one_problem("Hud.java")

    # ---- the command line

    def test_the_exit_code_is_0_when_it_holds_and_1_when_it_does_not(self) -> None:
        self.write_ledger()
        arguments = [sys.executable, str(JUDGE), "--ledger-dir", str(self.ledger_dir), "--server-log",
                     str(self.server_log), "--session-log", str(self.session_log), "--client-source",
                     str(self.client_source)]
        passed = subprocess.run(arguments + [str(path) for path in self.reports], capture_output=True, text=True)
        self.reports[0].write_text(report(Q0a="FAIL"), encoding="utf-8")
        failed = subprocess.run(arguments + [str(path) for path in self.reports], capture_output=True, text=True)
        self.assertEqual(passed.returncode, 0, passed.stdout + passed.stderr)
        self.assertIn("rush judge: PASS (2 reports, 0 problems)", passed.stdout)
        self.assertIn("prepare_ms 49", passed.stdout)
        self.assertEqual(failed.returncode, 1, failed.stdout + failed.stderr)
        self.assertIn("rush judge: FAIL (2 reports, 1 problems)", failed.stdout)

    def test_the_client_source_defaults_to_the_mod_beside_the_script(self) -> None:
        self.assertEqual(self.judge.CLIENT_SOURCE, JUDGE.resolve().parent.parent / "src" / "main" / "java")


if __name__ == "__main__":
    unittest.main()
