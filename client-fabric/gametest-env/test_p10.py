#!/usr/bin/env python3
"""Unit tests of p10.py, standard library only (docs/TEST.md TC-PROD-660, TC-PROD-744, TC-PROD-753, TC-PROD-754):

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


def load_p10():
    spec = importlib.util.spec_from_file_location("p10_under_test", P10)
    p10 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(p10)
    return p10


class SeedTest(unittest.TestCase):
    """p10.py seed against a manifest of the map the jobs bake and a stand-in psql that records its arguments."""

    MANIFEST = {"format_version": 1, "map_id": "p10", "build_id": "p10-41d364b9d82e-p776"}

    def seed(self, psql_exit: int = 0) -> tuple[subprocess.CompletedProcess, Path, Path]:
        """The run, the file the stand-in psql wrote its arguments to and the seed record's path."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        (directory / "manifest.json").write_text(json.dumps(self.MANIFEST), encoding="utf-8")
        psql = directory / "psql.py"
        psql.write_text("import json, sys\n"
                        f"open({str(directory / 'calls.json')!r}, 'w').write(json.dumps(sys.argv[1:]))\n"
                        f"sys.exit({psql_exit})\n", encoding="utf-8")
        run = subprocess.run([sys.executable, str(P10), "seed", "--name", "SynaxisP10",
                              "--manifest", str(directory / "manifest.json"),
                              "--psql", f"{sys.executable} {psql} -h 127.0.0.1 -U postgres -d postgres",
                              "--out", str(directory / "seed.json")], capture_output=True, text=True)
        return run, directory / "calls.json", directory / "seed.json"

    def test_the_account_is_the_one_the_shards_record_for_the_name(self) -> None:
        # The production client without --uuid, the fake sessionserver in record mode: Player998 joins the shards
        # as 5bd62aa7-8886-32aa-953e-97bdbc2a6931
        self.assertEqual(load_p10().offline_uuid("Player998"), "5bd62aa7-8886-32aa-953e-97bdbc2a6931")

    def test_the_section_goes_to_the_character_of_the_account(self) -> None:
        run, calls, record = self.seed()
        self.assertEqual(run.returncode, 0, run.stderr)
        account = load_p10().offline_uuid("SynaxisP10")
        arguments = json.loads(calls.read_text(encoding="utf-8"))
        self.assertEqual(arguments[:-1], ["-h", "127.0.0.1", "-U", "postgres", "-d", "postgres", "-c"])
        statements = arguments[-1].split("; ")
        self.assertEqual(len(statements), 3, arguments[-1])
        # The rows the shard's own load makes (PostgresBackend), version 0 and no lease, kept if they are there
        self.assertEqual(statements[0], f"INSERT INTO account (account_uuid, last_name) VALUES ('{account}', "
                                        "'SynaxisP10') ON CONFLICT (account_uuid) DO NOTHING")
        self.assertEqual(statements[1], "INSERT INTO character (account_uuid, slot, version, lease_session_id) "
                                        f"VALUES ('{account}', 0, 0, NULL) ON CONFLICT (account_uuid, slot) DO NOTHING")
        head = ("INSERT INTO character_section (character_id, section_id, schema_version, data) "
                "SELECT character_id, 'synaxis:player', 1, '")
        tail = (f"'::jsonb FROM character WHERE account_uuid = '{account}' AND slot = 0 "
                "ON CONFLICT (character_id, section_id) DO UPDATE SET schema_version = EXCLUDED.schema_version, "
                "data = EXCLUDED.data, updated_at = now()")
        self.assertTrue(statements[2].startswith(head) and statements[2].endswith(tail), statements[2])
        data = json.loads(statements[2][len(head):-len(tail)].replace("''", "'"))
        seeded = json.loads(record.read_text(encoding="utf-8"))
        self.assertEqual(seeded, {"name": "SynaxisP10", "account": account, "section": data})

    def test_the_section_is_one_the_shard_decodes_and_applies(self) -> None:
        # PlayerStateSection#decode and the MapShard rule of API-PROD-032
        run, _, record = self.seed()
        self.assertEqual(run.returncode, 0, run.stderr)
        section = json.loads(record.read_text(encoding="utf-8"))["section"]
        self.assertEqual(sorted(section), ["health", "held_slot", "inventory", "pos", "world"])
        self.assertEqual(section["world"], {"kind": "map", "map_id": "p10", "build_id": "p10-41d364b9d82e-p776"})
        self.assertEqual(sorted(section["pos"]), ["pitch", "x", "y", "yaw", "z"])
        # The first shard's bounds, those of r0-c1's region in the shard directory p10.py shards writes
        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "shards.json"
            subprocess.run([sys.executable, str(P10), "shards", "--host", "127.0.0.1", "--ports", "25611,25612",
                            "--out", str(out)], check=True)
            shards = json.loads(out.read_text(encoding="utf-8"))
        first = shards["channels"][0]
        bounds = next(r["bounds"] for r in shards["regions"] if r["region_id"] == first["region_id"])
        self.assertEqual(first["shard_id"], "r0-c1")
        chunk_x, chunk_z = section["pos"]["x"] // 16, section["pos"]["z"] // 16
        self.assertTrue(bounds["min_x"] <= chunk_x <= bounds["max_x"] and bounds["min_z"] <= chunk_z <= bounds["max_z"],
                        (section["pos"], bounds))
        slots = [entry["slot"] for entry in section["inventory"]]
        self.assertTrue(slots and slots == sorted(set(slots)) and all(0 <= slot <= 45 for slot in slots), slots)
        for entry in section["inventory"]:
            self.assertEqual(sorted(entry["item"]), ["count", "id"])
            self.assertTrue(entry["item"]["id"].startswith("minecraft:") and entry["item"]["count"] >= 1, entry)
        self.assertIn(section["held_slot"], slots)
        self.assertTrue(0 <= section["held_slot"] <= 8)
        # Above 0, or the shard takes the character for one left on the death screen and keeps it at the spawn
        self.assertTrue(0 < section["health"] <= 20, section["health"])

    def test_a_failed_write_leaves_no_record(self) -> None:
        run, _, record = self.seed(psql_exit=1)
        self.assertNotEqual(run.returncode, 0)
        self.assertFalse(record.exists())


class JudgeTest(unittest.TestCase):
    """p10.py judge on a passing record of one round trip, laid out the way CI leaves it, with a seed record and
    a client report carrying the given state lines."""

    SECTION = {"world": {"kind": "map", "map_id": "p10", "build_id": "p10-41d364b9d82e-p776"},
               "pos": {"x": 100.5, "y": 79.0, "z": 120.5, "yaw": 90.0, "pitch": 0.0},
               "inventory": [{"slot": 0, "item": {"id": "minecraft:stone", "count": 32}},
                             {"slot": 1, "item": {"id": "minecraft:iron_pickaxe", "count": 1}},
                             {"slot": 9, "item": {"id": "minecraft:oak_log", "count": 16}}],
               "held_slot": 1, "health": 16.0}
    # The same items by the slots of the inventory menu: the hotbar is 36 to 44 there
    KIT = "9:minecraft:oak_log*16,36:minecraft:stone*32,37:minecraft:iron_pickaxe*1"
    SAVED = "100.50,79.00,120.50"
    ARRIVAL = "120.50,78.00,120.50"

    @staticmethod
    def state(pos: str = ARRIVAL, held: int = 1, inventory: str = KIT) -> str:
        return f"pos={pos} yaw=0.0 pitch=0.0 held={held} inventory={inventory}"

    def passing(self) -> list[str]:
        """The states of a passing run: the saved position in the first world, the arrival point in the others."""
        return [self.state(self.SAVED), self.state(), self.state()]

    def judge(self, states: list[str | None], section: dict | None = None) -> subprocess.CompletedProcess:
        """states[n] is the state the client recorded in the (n + 1)th of its three worlds, none
        where it is None; section is the seeded one, SECTION by default."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        directory = Path(temporary.name)
        transfers = [{"n": n, "from": a, "to": b, "result": "sent", "answer_ms": 15, "arrival_ms": 1800,
                      "version": n + 1} for n, (a, b) in enumerate((("r0-c1", "r0-c2"), ("r0-c2", "r0-c1")), 1)]
        drive = {"round_trips": 1, "shards": ["r0-c1", "r0-c2"], "player": {"id": "00000000-0000-0000-0000-000000000001"},
                 "versions": [1, 2, 3], "transfers": transfers, "failure": None}
        (directory / "drive.json").write_text(json.dumps(drive), encoding="utf-8")
        seed = {"name": "SynaxisP10", "account": "00000000-0000-0000-0000-000000000001",
                "section": self.SECTION if section is None else section}
        (directory / "seed.json").write_text(json.dumps(seed), encoding="utf-8")
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
                               "--client-report", str(directory / "p10.txt"), "--seed", str(directory / "seed.json"),
                               "--round-trips", "1"],
                              capture_output=True, text=True)

    def failures(self, run: subprocess.CompletedProcess) -> list[str]:
        return [line for line in run.stdout.splitlines() if line.startswith("FAIL ")]

    def test_every_world_shows_the_seeded_inventory(self) -> None:
        run = self.judge(self.passing())
        self.assertEqual(run.returncode, 0, run.stdout)
        self.assertIn("ok   the client recorded its state in 3 of 3 worlds\n", run.stdout)
        self.assertIn(f"ok   the inventory is the seeded one in all 3 worlds: held=1 inventory={self.KIT}\n", run.stdout)

    def test_an_inventory_other_than_the_seeded_one_fails(self) -> None:
        empty = self.state(held=0, inventory="empty")
        cases = {"emptied by a transfer": ([self.state(self.SAVED), self.state(inventory="empty"), self.state()], 2,
                                           "held=1 inventory=empty"),
                 "held slot": ([self.state(self.SAVED), self.state(), self.state(held=4)], 3, f"held=4 inventory={self.KIT}"),
                 # No shard applied a saved inventory, and every world is empty
                 "never applied": ([self.state(self.SAVED, held=0, inventory="empty"), empty, empty], 1,
                                   "held=0 inventory=empty")}
        for name, (states, world, shown) in cases.items():
            with self.subTest(case=name):
                run = self.judge(states)
                self.assertEqual(run.returncode, 1, run.stdout)
                self.assertEqual(self.failures(run), [f"FAIL the inventory in world {world} is {shown}, the seeded one is "
                                                      f"held=1 inventory={self.KIT}"], run.stdout)

    def test_a_seeded_inventory_that_is_empty_fails(self) -> None:
        empty = self.state(held=0, inventory="empty")
        run = self.judge([self.state(self.SAVED, held=0, inventory="empty"), empty, empty],
                         dict(self.SECTION, inventory=[], held_slot=0))
        self.assertEqual(run.returncode, 1, run.stdout)
        self.assertEqual(self.failures(run), ["FAIL the seeded inventory is empty"], run.stdout)

    def test_the_first_world_shows_the_saved_position(self) -> None:
        # Within half a block of it; the later worlds are arrivals, at the point the shard gives a ticket
        cases = {"0.3 east": ("100.80,79.00,120.50", True, "0.30"), "0.6 east": ("101.10,79.00,120.50", False, "0.60"),
                 "the arrival point": (self.ARRIVAL, False, "20.02")}
        for name, (pos, ok, distance) in cases.items():
            with self.subTest(case=name):
                run = self.judge([self.state(pos), self.state(), self.state()])
                self.assertEqual(run.returncode, 0 if ok else 1, run.stdout)
                line = (f"the client stood {distance} blocks from the saved position {self.SAVED} in world 1, "
                        f"at {pos} (0.5 at most)")
                self.assertIn(("ok   " if ok else "FAIL ") + line + "\n", run.stdout)
                self.assertEqual(len(self.failures(run)), 0 if ok else 1, run.stdout)

    def test_a_world_without_a_state_fails(self) -> None:
        for states in ([self.state(self.SAVED), self.state(), None], [self.state(self.SAVED), "none", self.state()]):
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
        # write state 1 none
        asked, _ = self.drive()
        self.assertEqual(asked, [5.5, 11.0, 16.5, 22.0])

    def test_the_run_ends_the_gap_after_the_last_transfer(self) -> None:
        # Ended at once, the client can see the done file before it joins the last world
        asked, ended = self.drive()
        self.assertEqual(ended, [asked[-1] + 5.5])

if __name__ == "__main__":
    unittest.main()
