#!/usr/bin/env python3
"""P10 of docs/DESIGN-PROD.md §19 (REQ-PROD-031, API-PROD-026): two map shards, the real client sent
from one to the other and back 100 times. Standard library only: CI runs it with the runner's
python3, a local run with the repository's .venv (docs/TEST.md TC-PROD-550).

    p10.py shards --host 127.0.0.1 --ports 25611,25612 --out maps/p10/shards.json

writes the shard directory of the fixture (docs/DESIGN-M3.md API-M3-010): one region r0 over the
chunks 0,0 to 15,15 with no gates, its channels r0-c1 and r0-c2 on those two ports; the file is
0644 whatever the umask.

    SYNAXIS_MANAGEMENT_TOKEN=... p10.py drive --management r0-c1=127.0.0.1:25585 \\
        --management r0-c2=127.0.0.1:25586 --psql 'psql -h 127.0.0.1 -U postgres -d postgres' \\
        --done-file p10/done --out p10/drive.json

waits for the one player on the first shard's minecraft:players, then 2 x --round-trips times asks
the shard it is on for synaxis:transfer to the other one (ShardService#transferTo), waits for it on
the target's minecraft:players and reads its character.version, which has to be larger every time.
A transfer starts --gap seconds after the previous one was answered sent at the earliest: the answer
comes after the ticket's issued_at, where the 5 s cooldown starts. The first one starts --gap seconds
after the first character.version was read, and --done-file is created --gap seconds after the last
one was answered: the shards list the player before the client has handled the join, and a client
sent on or ended at once can leave a world before its first tick there, without a state line for it,
or end its run before it joins the last one. It stops at the first transfer not answered sent, not
arrived within --arrival-timeout or not followed by a larger version. It always writes --out and
creates --done-file, which ends the client's run; the exit code is 1 after a failure.

    p10.py judge --drive p10/drive.json --ledger r0-c1=p10/work-r0-c1/logs \\
        --ledger r0-c2=p10/work-r0-c2/logs --client-report <run dir>/synaxis-gametest/p10.txt

judges by the scenario's Then, once the client and both shards have stopped: every transfer
answered sent and arrived, character.version larger after each one, exactly 2 x --round-trips
transfer_issued events over both ledgers; and the client joined 2 x --round-trips + 1 worlds,
alternating between the two shards, with the same inventory in each, and left because the driver was done.
"""

from __future__ import annotations

import argparse
import base64
import gzip
import json
import os
import shlex
import socket
import struct
import subprocess
import sys
import time
import uuid
from pathlib import Path

REGION = "r0"
CHANNELS = ("r0-c1", "r0-c2")
# Java-WebSocket closes a connection whose last pong is older than 1.5 times its 60 s timer
KEEPALIVE_SECONDS = 20.0


class Failure(Exception):
    pass


def pair(text: str) -> tuple[str, str]:
    name, sep, value = text.partition("=")
    if not sep or not name or not value:
        raise argparse.ArgumentTypeError(f"expected <shard_id>=<value>, got {text}")
    return name, value


def shards(args: argparse.Namespace) -> None:
    ports = [int(port) for port in args.ports.split(",")]
    if len(ports) != len(CHANNELS):
        raise SystemExit("--ports takes two ports, r0-c1's and r0-c2's")
    directory = {
        "format_version": 1,
        "map_id": "p10",
        "regions": [{"region_id": REGION, "bounds": {"min_x": 0, "min_z": 0, "max_x": 15, "max_z": 15},
                     "gates": []}],
        "channels": [{"shard_id": shard, "region_id": REGION, "channel": n + 1, "host": args.host,
                      "port": port, "capacity": 200} for n, (shard, port) in enumerate(zip(CHANNELS, ports))],
    }
    out = Path(args.out)
    out.write_text(json.dumps(directory, indent=1) + "\n", encoding="utf-8")
    # A shard refuses a shards.json group or others may write (docs/DESIGN-M3.md API-M3-002), and
    # what the runner's umask made of the file is not up to this script
    out.chmod(0o644)


class Management:
    """One connection to a shard's management interface (API-PROD-022): JSON-RPC 2.0 over a
    WebSocket, the upgrade carrying the bearer token."""

    def __init__(self, shard: str, address: str, token: str) -> None:
        host, _, port = address.rpartition(":")
        self.shard = shard
        self.sock = socket.create_connection((host, int(port)), timeout=30)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET / HTTP/1.1\r\nHost: {address}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                           f"Authorization: Bearer {token}\r\n\r\n").encode())
        head = b""
        # A byte at a time: a notification may follow the 101 in the same segment
        while b"\r\n\r\n" not in head:
            head += self._exact(1)
        status = head.split(b"\r\n", 1)[0].decode(errors="replace")
        if status.split(" ")[1:2] != ["101"]:
            raise Failure(f"{shard}: the management interface answered the upgrade with {status}")
        self.last_sent = time.monotonic()
        self.next_id = 0

    def _exact(self, count: int) -> bytes:
        data = b""
        while len(data) < count:
            chunk = self.sock.recv(count - len(data))
            if not chunk:
                raise Failure(f"{self.shard}: the management connection was closed")
            data += chunk
        return data

    def _send(self, opcode: int, payload: bytes) -> None:
        mask = os.urandom(4)
        head = bytes([0x80 | opcode])
        if len(payload) < 126:
            head += bytes([0x80 | len(payload)])
        elif len(payload) < 65536:
            head += bytes([0x80 | 126]) + struct.pack(">H", len(payload))
        else:
            head += bytes([0x80 | 127]) + struct.pack(">Q", len(payload))
        self.sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))
        self.last_sent = time.monotonic()

    def _message(self) -> str:
        """The next text message; pings on the way are answered, pongs skipped."""
        parts = []
        while True:
            first, second = self._exact(2)
            length = second & 0x7F
            if length == 126:
                length, = struct.unpack(">H", self._exact(2))
            elif length == 127:
                length, = struct.unpack(">Q", self._exact(8))
            mask = self._exact(4) if second & 0x80 else None
            payload = self._exact(length)
            if mask:
                payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
            opcode = first & 0x0F
            if opcode == 0x9:
                self._send(0xA, payload)
            elif opcode == 0x8:
                raise Failure(f"{self.shard}: the server closed the management connection: "
                              f"{payload[2:].decode(errors='replace')}")
            elif opcode in (0x0, 0x1):
                parts.append(payload)
                if first & 0x80:
                    return b"".join(parts).decode()

    def call(self, method: str, params: object = None) -> object:
        self.next_id += 1
        request = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        if params is not None:
            request["params"] = params
        self._send(0x1, json.dumps(request).encode())
        while True:
            reply = json.loads(self._message())
            # Anything else is a notification (players joined or left)
            if reply.get("id") == self.next_id:
                if "error" in reply:
                    raise Failure(f"{self.shard} {method}: {json.dumps(reply['error'])}")
                return reply["result"]

    def keepalive(self) -> None:
        if time.monotonic() - self.last_sent > KEEPALIVE_SECONDS:
            self._send(0xA, b"")

    def close(self) -> None:
        self.sock.close()


def wait_for_player(connections: dict[str, Management], shard: str, player_id: str | None,
                    timeout: float, interval: float) -> dict:
    """Polls one shard's minecraft:players until it lists the player (any one player when none is
    named); the other connections are kept alive meanwhile."""
    deadline = time.monotonic() + timeout
    while True:
        online = connections[shard].call("minecraft:players")
        if player_id is None and len(online) > 1:
            raise Failure(f"{shard} lists {len(online)} players, the fixture has one client: {online}")
        found = [p for p in online if player_id is None or p["id"] == player_id]
        if found:
            return found[0]
        if time.monotonic() > deadline:
            raise Failure(f"{shard} did not list {player_id or 'a player'} within {timeout:.0f} s")
        for connection in connections.values():
            connection.keepalive()
        time.sleep(interval)


def wait_until(connections: dict[str, Management], ready: float) -> None:
    """Sleeps until time.monotonic() reaches ready; the connections are kept alive meanwhile."""
    while time.monotonic() < ready:
        for connection in connections.values():
            connection.keepalive()
        time.sleep(min(0.5, max(0.0, ready - time.monotonic())))


def read_version(psql: str, player_id: str) -> int:
    account = str(uuid.UUID(player_id))
    sql = f"SELECT version FROM character WHERE account_uuid = '{account}' AND slot = 0"
    out = subprocess.run(shlex.split(psql) + ["-tAc", sql], capture_output=True, text=True, timeout=30)
    rows = out.stdout.split()
    if out.returncode != 0 or len(rows) != 1:
        raise Failure(f"reading character.version of {account}: exit {out.returncode}, "
                      f"stdout {out.stdout.strip()!r}, stderr {out.stderr.strip()!r}")
    return int(rows[0])


def drive(args: argparse.Namespace) -> int:
    token = os.environ.get("SYNAXIS_MANAGEMENT_TOKEN")
    if not token:
        raise SystemExit("SYNAXIS_MANAGEMENT_TOKEN is not set")
    endpoints = dict(args.management)
    if len(endpoints) != 2:
        raise SystemExit("--management names the two shards, the one the client joins first")
    summary: dict = {"round_trips": args.round_trips, "shards": list(endpoints), "player": None,
                     "versions": [], "transfers": [], "failure": None}
    connections: dict[str, Management] = {}
    started = time.monotonic()
    try:
        for shard, address in endpoints.items():
            connections[shard] = Management(shard, address, token)
        here, there = list(endpoints)
        player = wait_for_player(connections, here, None, args.first_join_timeout, 1.0)
        summary["player"] = player
        summary["versions"].append(read_version(args.psql, player["id"]))
        print(f"{player['name']} {player['id']} on {here}, character.version {summary['versions'][0]}", flush=True)
        # The shards list the player before the client has handled the join. Sent on or ended at once,
        # the client can leave a world before its first tick there and record no state for it, or end
        # its run before it joins the last world; so it gets --gap in the first and the last world too
        ready = time.monotonic() + args.gap
        for n in range(1, 2 * args.round_trips + 1):
            wait_until(connections, ready)
            asked = time.monotonic()
            result = connections[here].call("synaxis:transfer", {"player": {"id": player["id"]}, "shard": there})
            answered = time.monotonic()
            ready = answered + args.gap
            record = {"n": n, "from": here, "to": there, "result": result,
                      "answer_ms": round((answered - asked) * 1000)}
            summary["transfers"].append(record)
            if result != "sent":
                raise Failure(f"transfer {n} {here} -> {there} was answered {result}")
            wait_for_player(connections, there, player["id"], args.arrival_timeout, 0.5)
            record["arrival_ms"] = round((time.monotonic() - asked) * 1000)
            record["version"] = read_version(args.psql, player["id"])
            summary["versions"].append(record["version"])
            print(f"transfer {n} {here} -> {there}: sent in {record['answer_ms']} ms, arrived in "
                  f"{record['arrival_ms']} ms, character.version {record['version']}", flush=True)
            if record["version"] <= summary["versions"][-2]:
                raise Failure(f"transfer {n}: character.version {record['version']} is not larger than "
                              f"{summary['versions'][-2]}")
            here, there = there, here
        wait_until(connections, ready)
    # Whatever stopped it goes into the record, which is what the judge and a reader look at
    except Exception as e:
        summary["failure"] = str(e) if isinstance(e, Failure) else f"{type(e).__name__}: {e}"
        print(f"FAIL {summary['failure']}", flush=True)
    finally:
        summary["seconds"] = round(time.monotonic() - started)
        Path(args.out).write_text(json.dumps(summary, indent=1) + "\n", encoding="utf-8")
        Path(args.done_file).touch()
        for connection in connections.values():
            connection.close()
    return 1 if summary["failure"] else 0


def ledger_events(directory: Path, event: str) -> int:
    count = 0
    for path in sorted(directory.glob("events-*.jsonl")) + sorted(directory.glob("events-*.jsonl.gz")):
        opener = gzip.open if path.suffix == ".gz" else open
        with opener(path, "rt", encoding="utf-8") as lines:
            count += sum(1 for line in lines if line.strip() and json.loads(line).get("event") == event)
    return count


def judge(args: argparse.Namespace) -> int:
    failures = 0

    def expect(ok: bool, what: str) -> None:
        nonlocal failures
        print(("ok   " if ok else "FAIL ") + what)
        failures += not ok

    transfers = 2 * args.round_trips
    drive_file = Path(args.drive)
    if drive_file.is_file():
        drive_run = json.loads(drive_file.read_text(encoding="utf-8"))
        records = drive_run["transfers"]
        sent = sum(1 for r in records if r["result"] == "sent")
        arrived = sum(1 for r in records if "arrival_ms" in r)
        versions = drive_run["versions"]
        larger = all(b > a for a, b in zip(versions, versions[1:]))
        expect(drive_run["failure"] is None, "the driver ran to the end" if drive_run["failure"] is None
               else f"the driver stopped: {drive_run['failure']}")
        expect(len(records) == transfers and sent == transfers,
               f"{sent} of {len(records)} transfers answered sent, expected {transfers} of {transfers}")
        expect(arrived == transfers, f"{arrived} transfers arrived on their target shard, expected {transfers}")
        expect(len(versions) == transfers + 1 and larger,
               f"character.version read {len(versions)} times, expected {transfers + 1}, "
               f"larger after every transfer: {larger} ({versions[:1]} .. {versions[-1:]})")
    else:
        expect(False, f"no driver record at {drive_file}")

    issued = {shard: ledger_events(Path(directory), "transfer_issued") for shard, directory in args.ledger}
    expect(sum(issued.values()) == transfers,
           f"transfer_issued {issued}, {sum(issued.values())} in all, expected {transfers}")

    report = Path(args.client_report)
    if report.is_file():
        lines = report.read_text(encoding="utf-8").splitlines()
        ports = [line.split(" port=")[1].split(" ")[0] for line in lines if line.startswith("join ")]
        alternate = all(a != b for a, b in zip(ports, ports[1:]))
        expect(len(ports) == transfers + 1, f"the client joined {len(ports)} worlds, expected {transfers + 1}")
        expect(alternate, f"the client's joins alternate between two shards: {sorted(set(ports))}")
        # One state line per world, what the client held when it left it: a transfer carries the
        # inventory over (synaxis:player, API-PROD-032), so every world shows the one before it
        states = {}
        for line in lines:
            if line.startswith("state "):
                fields = dict(f.split("=", 1) for f in line.split(" ")[2:] if "=" in f)
                if "inventory" in fields:
                    states[line.split(" ")[1]] = f"held={fields.get('held')} inventory={fields['inventory']}"
        worlds = [line.split(" ")[1] for line in lines if line.startswith("join ")]
        recorded = [states[n] for n in worlds if n in states]
        expect(len(recorded) == len(worlds), f"the client recorded its state in {len(recorded)} of {len(worlds)} worlds")
        changed = [(n, states[m], states[n]) for m, n in zip(worlds, worlds[1:]) if m in states and n in states
                   and states[m] != states[n]]
        expect(not changed, "the inventory changed on arriving in world {}: {} before, {} after".format(*changed[0])
               if changed else f"the inventory is the same in all {len(worlds)} worlds: {recorded[0] if recorded else None}")
        expect(bool(lines) and lines[-1] == "end done", f"the client's report ends with {lines[-1:]}")
    else:
        expect(False, f"no client report at {report}")

    if failures:
        print(f"P10: {failures} check(s) failed")
        return 1
    print("P10: all checks passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)

    p = commands.add_parser("shards")
    p.add_argument("--host", required=True)
    p.add_argument("--ports", required=True, help="r0-c1's and r0-c2's game port, comma separated")
    p.add_argument("--out", required=True)

    p = commands.add_parser("drive")
    p.add_argument("--management", type=pair, action="append", required=True,
                   help="<shard_id>=<host:port>, the shard the client joins first given first")
    p.add_argument("--psql", required=True, help="the psql command line, -tAc <query> is appended")
    p.add_argument("--done-file", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--round-trips", type=int, default=100)
    p.add_argument("--gap", type=float, default=5.5)
    p.add_argument("--arrival-timeout", type=float, default=60)
    p.add_argument("--first-join-timeout", type=float, default=900)

    p = commands.add_parser("judge")
    p.add_argument("--drive", required=True)
    p.add_argument("--ledger", type=pair, action="append", required=True, help="<shard_id>=<logs dir>")
    p.add_argument("--client-report", required=True)
    p.add_argument("--round-trips", type=int, default=100)

    args = parser.parse_args()
    if args.command == "shards":
        shards(args)
        return 0
    return drive(args) if args.command == "drive" else judge(args)


if __name__ == "__main__":
    sys.exit(main())
