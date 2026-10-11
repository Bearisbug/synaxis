#!/usr/bin/env python3
"""Unit tests of p10.py, standard library only (docs/TEST.md TC-PROD-660, TC-PROD-740, TC-PROD-744):

    python3 -m unittest discover -s client-fabric/gametest-env -p 'test_*.py'
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

P10 = Path(__file__).with_name("p10.py")


class ShardsTest(unittest.TestCase):

    def test_the_shard_directory_is_0644_whatever_the_umask(self) -> None:
        # A map shard refuses a shards.json group or others may write (docs/DESIGN-M3.md API-M3-002),
        # and a CI runner's umask may be 002 as well as 022
        for umask in (0o002, 0o022, 0o077):
            with self.subTest(umask=oct(umask)), tempfile.TemporaryDirectory() as directory:
                out = Path(directory) / "shards.json"
                subprocess.run([sys.executable, str(P10), "shards", "--host", "127.0.0.1",
                                "--ports", "25611,25612", "--out", str(out)],
                               check=True, preexec_fn=lambda u=umask: os.umask(u))
                self.assertEqual(oct(stat.S_IMODE(out.stat().st_mode)), oct(0o644))
                channels = json.loads(out.read_text(encoding="utf-8"))["channels"]
                self.assertEqual([(c["shard_id"], c["port"]) for c in channels],
                                 [("r0-c1", 25611), ("r0-c2", 25612)])


class JudgeTest(unittest.TestCase):
    """p10.py judge on a passing record of one round trip, laid out the way CI leaves it, whose
    client report carries the given state lines."""

    KIT = "36:minecraft:stone*64,37:minecraft:iron_pickaxe*1"

    @staticmethod
    def state(held: int = 0, inventory: str = "empty") -> str:
        return f"pos=0.50,40.00,0.50 yaw=0.0 pitch=0.0 held={held} inventory={inventory}"

    def judge(self, states: list[str | None]) -> subprocess.CompletedProcess:
        """states[n] is the state the client recorded in the (n + 1)th of its three worlds, none
        where it is None."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        transfers = [{"n": n, "from": a, "to": b, "result": "sent", "answer_ms": 15, "arrival_ms": 1800,
                      "version": n + 1} for n, (a, b) in enumerate((("r0-c1", "r0-c2"), ("r0-c2", "r0-c1")), 1)]
        drive = {"round_trips": 1, "shards": ["r0-c1", "r0-c2"], "player": {"id": "00000000-0000-0000-0000-000000000001"},
                 "versions": [1, 2, 3], "transfers": transfers, "failure": None}
        (directory / "drive.json").write_text(json.dumps(drive), encoding="utf-8")
        for shard in ("r0-c1", "r0-c2"):
            (directory / shard).mkdir()
            (directory / shard / "events-2026-10-11.jsonl").write_text(
                json.dumps({"event": "transfer_issued"}) + "\n", encoding="utf-8")
        lines = []
        for n, (port, state) in enumerate(zip(("25611", "25612", "25611"), states), 1):
            lines.append(f"join {n} port={port} at={1760140800000 + 6000 * n}")
            if state is not None:
                lines.append(f"state {n} {state}")
        (directory / "p10.txt").write_text("\n".join(lines + ["end done"]) + "\n", encoding="utf-8")
        return subprocess.run([sys.executable, str(P10), "judge", "--drive", str(directory / "drive.json"),
                               "--ledger", f"r0-c1={directory / 'r0-c1'}", "--ledger", f"r0-c2={directory / 'r0-c2'}",
                               "--client-report", str(directory / "p10.txt"), "--round-trips", "1"],
                              capture_output=True, text=True)

    def failures(self, run: subprocess.CompletedProcess) -> list[str]:
        return [line for line in run.stdout.splitlines() if line.startswith("FAIL ")]

    def test_the_inventory_the_client_arrives_with_is_the_one_it_left_with(self) -> None:
        # Empty until the map shards register synaxis:player and give the player something to carry
        for inventory in ("empty", self.KIT):
            with self.subTest(inventory=inventory):
                run = self.judge([self.state(inventory=inventory)] * 3)
                self.assertEqual(run.returncode, 0, run.stdout)
                self.assertIn("ok   the client recorded its state in 3 of 3 worlds\n", run.stdout)
                self.assertIn(f"ok   the inventory is the same in all 3 worlds: held=0 inventory={inventory}\n",
                              run.stdout)

    def test_an_inventory_a_transfer_changed_fails(self) -> None:
        cases = {"items": ([self.state(inventory=self.KIT), self.state(), self.state()], 2),
                 "held slot": ([self.state(inventory=self.KIT)] * 2 + [self.state(held=4, inventory=self.KIT)], 3)}
        for name, (states, world) in cases.items():
            with self.subTest(changed=name):
                run = self.judge(states)
                self.assertEqual(run.returncode, 1, run.stdout)
                failures = self.failures(run)
                self.assertEqual(len(failures), 1, run.stdout)
                self.assertTrue(failures[0].startswith(f"FAIL the inventory changed on arriving in world {world}: "),
                                failures[0])

    def test_a_world_without_a_state_fails(self) -> None:
        for states in ([self.state(), self.state(), None], [self.state(), "none", self.state()]):
            with self.subTest(states=states):
                run = self.judge(states)
                self.assertEqual(run.returncode, 1, run.stdout)
                self.assertEqual(self.failures(run), ["FAIL the client recorded its state in 2 of 3 worlds"], run.stdout)


class DriveTest(unittest.TestCase):
    """p10.py drive --round-trips 2 --gap 5.5 against two fake shards on a fake clock: the first
    shard lists the player at 0 s, and a transfer moves it to the target on the spot. Real shards
    list the player before the client has handled the join, so whatever the driver does at once
    after a listing can come before the client's first tick in that world."""

    def drive(self) -> tuple[list[float], list[float]]:
        """The times the four transfers were asked at, and the time the done file was there at."""
        spec = importlib.util.spec_from_file_location("p10_under_test", P10)
        p10 = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(p10)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        done = directory / "done"
        clock = [0.0]
        on = ["r0-c1"]
        asked: list[float] = []
        ended: list[float] = []
        player = {"id": "00000000-0000-0000-0000-000000000001", "name": "Player1"}

        class Shard:
            def __init__(self, shard: str, address: str, token: str) -> None:
                self.shard = shard

            def call(self, method: str, params: dict | None = None) -> object:
                if method == "minecraft:players":
                    return [player] if on[0] == self.shard else []
                asked.append(clock[0])
                on[0] = params["shard"]
                return "sent"

            def keepalive(self) -> None:
                pass

            def close(self) -> None:
                if done.exists() and not ended:
                    ended.append(clock[0])

        def sleep(seconds: float) -> None:
            clock[0] += seconds

        versions = iter(range(1, 10))
        p10.Management = Shard
        p10.read_version = lambda psql, player_id: next(versions)
        p10.time = SimpleNamespace(monotonic=lambda: clock[0], sleep=sleep)
        args = SimpleNamespace(management=[("r0-c1", "127.0.0.1:25585"), ("r0-c2", "127.0.0.1:25586")],
                               psql="psql", done_file=str(done), out=str(directory / "drive.json"),
                               round_trips=2, gap=5.5, arrival_timeout=60, first_join_timeout=900)
        with mock.patch.dict(os.environ, {"SYNAXIS_MANAGEMENT_TOKEN": "unused"}), \
                contextlib.redirect_stdout(io.StringIO()):
            code = p10.drive(args)
        self.assertEqual(code, 0)
        self.assertIsNone(json.loads((directory / "drive.json").read_text(encoding="utf-8"))["failure"])
        return asked, ended

    def test_the_first_transfer_waits_the_gap_like_every_other_one(self) -> None:
        # Sent on at once, the client can leave the first world before its first tick there and
        # write state 1 none (RUN-073, run 38096423208)
        asked, _ = self.drive()
        self.assertEqual(asked, [5.5, 11.0, 16.5, 22.0])

    def test_the_run_ends_the_gap_after_the_last_transfer(self) -> None:
        # Ended at once, the client can see the done file before it joins the last world
        asked, ended = self.drive()
        self.assertEqual(ended, [asked[-1] + 5.5])

if __name__ == "__main__":
    unittest.main()
