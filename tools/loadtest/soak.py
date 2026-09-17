#!/usr/bin/env python3
"""Load and soak harness for the synaxis server core, standard library only.

The protocol itself is not reimplemented here: the handshake, login and configuration phases come
from ``tools/protocol-probe/probe.py`` (packet ids are still derived from the repository, see that
file). This module adds what a soak needs on top of a single probe run:

* many connections held at once, each answering KeepAlive and TeleportConfirm and walking a circle
  so that chunk tracking, entity visibility and broadcasting are actually exercised;
* connection churn, a configurable number of logins per second that hang up again after a hold;
* the malicious probe subcommands of ``docs/TEST.md`` §3 fired while that load runs;
* sampling of the server process through tools that already exist (``jcmd``, ``jstat``, ``lsof``,
  ``ps``) and of the tick loop through the engine's own JFR event ``synaxis.ServerTickTime``.

Nothing in the engine is changed or instrumented for this: every server side number comes from a
JDK tool pointed at the running pid.

All connections are driven by one selector loop, only the login handshakes run on worker threads.
That keeps the harness itself off the critical path: a thread per connection spends more CPU on the
client than the server spends serving it, which would measure the harness instead of the engine.

Subcommands:

    run     drive a load profile against a running server and write the samples
    ticks   summarise ``synaxis.ServerTickTime`` out of a JFR recording (run does this too)

Usage, see docs/research/soak-2026-09-18/README.md:

    python3 tools/loadtest/soak.py run --connections 64 --duration 1200 --out /tmp/soak/steady
"""

from __future__ import annotations

import argparse
import json
import math
import os
import queue
import re
import selectors
import socket
import statistics
import struct
import subprocess
import sys
import threading
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "protocol-probe"))

import probe  # noqa: E402  the path above is what makes it importable

PROBE = Path(__file__).resolve().parents[1] / "protocol-probe" / "probe.py"

#: Frames whose declared decompressed size is above this are chunk or entity data. The harness has
#: no use for them, and decompressing them would spend more client CPU than the whole soak needs.
#: Every packet the harness answers (KeepAlive, PlayerPositionAndLook, the echo PluginMessage) is
#: far below the 256 byte compression threshold and therefore arrives uncompressed anyway.
CONTROL_FRAME_LIMIT = 1024

#: The core world floor, see CoreMode.FLOOR_HEIGHT; spawn is (0.5, 40, 0.5).
FLOOR_Y = 40.0

#: Walking speed of a vanilla player, blocks per second: the circles are walked, not teleported.
WALK_SPEED = 4.3

ECHO_CHANNEL = "synaxis:main"


class QuietSession(probe.Session):
    """A probe session that does not print a line per packet."""

    def log(self, message: str) -> None:
        pass


class Metrics:
    """Counters shared between the login workers and the selector loop."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.login_attempts = 0
        self.login_failures: dict[str, int] = {}
        self.login_latencies: list[float] = []
        self.disconnects: dict[str, int] = {}
        self.keep_alives = 0
        self.moves = 0
        self.echoes_sent = 0
        self.rtt: list[float] = []
        self.steady_losses = 0

    def fail(self, reason: str) -> None:
        with self.lock:
            self.login_failures[reason] = self.login_failures.get(reason, 0) + 1

    def disconnect(self, reason: str) -> None:
        with self.lock:
            self.disconnects[reason] = self.disconnects.get(reason, 0) + 1


class LoadClient:
    """One logged in connection driven by the selector loop."""

    def __init__(self, session: QuietSession, username: str, index: int, expire: float | None) -> None:
        self.session = session
        self.connection = session.connection
        self.username = username
        self.index = index
        self.expire = expire
        self.radius = 16.0 + (index % 8) * 8.0
        self.phase = (index % 64) * (2 * math.pi / 64)
        self.position = (0.5, FLOOR_Y, 0.5)
        self.next_move = 0.0
        self.next_echo = 0.0
        self.pending_echo: float | None = None
        self.closed = False
        self.close_reason = ""

    def fileno(self) -> int:
        return self.connection.socket.fileno()

    def send(self, packet: str, payload: bytes) -> None:
        self.connection.send(packet, payload)

    def close(self, reason: str) -> None:
        if self.closed:
            return
        self.closed = True
        self.close_reason = reason
        self.connection.close()

    # -- reading ----------------------------------------------------------------------

    def readable(self, metrics: Metrics) -> None:
        try:
            chunk = self.connection.socket.recv(65536)
        except (BlockingIOError, socket.timeout):
            return
        except OSError as error:
            self.close(f"recv: {error}")
            return
        if not chunk:
            self.close("closed by server")
            return
        self.connection.incoming += chunk
        while True:
            try:
                frame = self.connection._take_frame()  # noqa: SLF001  same repository, see module docstring
            except ValueError as error:
                self.close(f"frame: {error}")
                return
            if frame is None:
                return
            try:
                self.handle(frame, metrics)
            except (EOFError, ValueError, zlib.error) as error:
                self.close(f"decode: {error}")
                return
            if self.closed:
                return

    def handle(self, frame: bytes, metrics: Metrics) -> None:
        decoded = decode_frame(self.connection, frame)
        if decoded is None:
            return
        packet, reader = decoded
        if packet == "KeepAlivePacket":
            self.send("ClientKeepAlivePacket", probe.Writer().long(reader.long()).bytes())
            with metrics.lock:
                metrics.keep_alives += 1
        elif packet == "PlayerPositionAndLookPacket":
            teleport_id = reader.varint()
            self.position = (reader.double(), reader.double(), reader.double())
            self.send("ClientTeleportConfirmPacket", probe.Writer().varint(teleport_id).bytes())
        elif packet == "PluginMessagePacket":
            channel = reader.string()
            payload = reader.raw(reader.remaining())
            if channel == ECHO_CHANNEL and self.pending_echo is not None and len(payload) == 8:
                sent = struct.unpack(">d", payload)[0]
                with metrics.lock:
                    metrics.rtt.append(time.monotonic() - sent)
                self.pending_echo = None
        elif packet == "DisconnectPacket":
            reason = probe.disconnect_reason("DisconnectPacket", reader)
            metrics.disconnect(reason)
            self.close(f"disconnect: {reason}")

    # -- writing ----------------------------------------------------------------------

    def step(self, now: float, started: float, move_interval: float, echo_interval: float,
             metrics: Metrics) -> None:
        if move_interval and now >= self.next_move:
            self.next_move = now + move_interval
            angle = self.phase + (now - started) * WALK_SPEED / self.radius
            x = 0.5 + self.radius * math.cos(angle)
            z = 0.5 + self.radius * math.sin(angle)
            yaw = math.degrees(angle) % 360.0 - 180.0
            payload = (probe.Writer()
                       .raw(struct.pack(">ddd", x, FLOOR_Y, z))
                       .raw(struct.pack(">ff", yaw, 0.0))
                       .raw(b"\x01")  # on ground
                       .bytes())
            try:
                self.send("ClientPlayerPositionAndRotationPacket", payload)
            except OSError as error:
                self.close(f"send: {error}")
                return
            self.position = (x, FLOOR_Y, z)
            with metrics.lock:
                metrics.moves += 1
        if echo_interval and now >= self.next_echo:
            self.next_echo = now + echo_interval
            self.pending_echo = now
            payload = probe.plugin_message_payload(ECHO_CHANNEL, struct.pack(">d", now))
            try:
                self.send("ClientPluginMessagePacket", payload)
            except OSError as error:
                self.close(f"send: {error}")
                return
            with metrics.lock:
                metrics.echoes_sent += 1


def decode_frame(connection: probe.Connection, frame: bytes):
    """Returns the packet name and a reader, or None for a frame the harness does not read."""
    if connection.compression_threshold >= 0:
        reader = probe.Reader(frame)
        data_length = reader.varint()
        offset = reader.stream.tell()
        if data_length == 0:
            body = frame[offset:]
        elif data_length > CONTROL_FRAME_LIMIT:
            return None
        else:
            body = zlib.decompress(frame[offset:])
    else:
        body = frame
    reader = probe.Reader(body)
    return probe.server_name(probe.PLAY, reader.varint()), reader


# --------------------------------------------------------------------------------------
# Server side sampling, every number comes from a JDK or system tool
# --------------------------------------------------------------------------------------


def shell(command: list[str], timeout: float = 30.0) -> str:
    try:
        done = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return done.stdout


def jstat(pid: int) -> dict[str, float]:
    output = shell(["jstat", "-gc", str(pid)]).splitlines()
    if len(output) < 2:
        return {}
    keys, values = output[0].split(), output[1].split()
    sample = {}
    for key, value in zip(keys, values):
        try:
            sample[key] = float(value)
        except ValueError:
            pass
    return sample


def thread_count(pid: int) -> int:
    lines = shell(["ps", "-M", str(pid)]).splitlines()
    return max(len(lines) - 1, 0)


def fd_count(pid: int) -> int:
    lines = shell(["lsof", "-p", str(pid), "-n", "-P"]).splitlines()
    return max(len(lines) - 1, 0)


def process_stats(pid: int) -> tuple[float, float]:
    output = shell(["ps", "-o", "%cpu=,rss=", "-p", str(pid)]).split()
    if len(output) < 2:
        return 0.0, 0.0
    return float(output[0]), float(output[1]) / 1024.0


def load_average() -> float:
    output = shell(["sysctl", "-n", "vm.loadavg"]).strip().strip("{} ").split()
    return float(output[0]) if output else 0.0


def gc_live_set(pid: int) -> float:
    """Forces a full GC and returns the heap used afterwards in MiB, the live set.

    A young collection only says how much garbage there was; what grows in a leak is what survives
    a full collection, so this is the number a soak has to watch. It stops the world, so the caller
    records when it ran and the tick spike it causes is attributed to it.
    """
    shell(["jcmd", str(pid), "GC.run"], timeout=120.0)
    # "garbage-first heap   total reserved 6291456K, committed 110592K, used 30709K [...]"
    found = re.search(r"heap.*used (\d+)K", shell(["jcmd", str(pid), "GC.heap_info"], timeout=60.0))
    return round(int(found.group(1)) / 1024.0, 1) if found else 0.0


class Sampler(threading.Thread):
    def __init__(self, pid: int, interval: float, gc_interval: float, out: Path,
                 metrics: Metrics, clients: list[LoadClient]) -> None:
        super().__init__(name="sampler", daemon=True)
        self.pid = pid
        self.interval = interval
        self.gc_interval = gc_interval
        self.out = out
        self.metrics = metrics
        self.clients = clients
        self.stop = threading.Event()
        self.samples: list[dict] = []
        self.gc_probes: list[dict] = []

    def run(self) -> None:
        started = time.monotonic()
        next_gc = started + self.gc_interval if self.gc_interval else math.inf
        with self.out.open("w", encoding="utf-8") as handle:
            while not self.stop.is_set():
                now = time.monotonic()
                cpu, rss = process_stats(self.pid)
                sample = {
                    "t": round(now - started, 1),
                    "clients": sum(1 for client in self.clients if not client.closed),
                    "threads": thread_count(self.pid),
                    "fds": fd_count(self.pid),
                    "cpu_percent": cpu,
                    "rss_mib": round(rss, 1),
                    "load1": load_average(),
                    # The harness is on the same machine: its own cost is what says whether the
                    # server or the client was the bottleneck.
                    "harness_cpu_percent": process_stats(os.getpid())[0],
                }
                sample.update({key: value for key, value in jstat(self.pid).items()
                               if key in ("OU", "OC", "EU", "S0U", "S1U", "MU", "CCSU",
                                          "YGC", "YGCT", "FGC", "FGCT", "GCT")})
                with self.metrics.lock:
                    sample["keep_alives"] = self.metrics.keep_alives
                    sample["moves"] = self.metrics.moves
                    sample["logins"] = self.metrics.login_attempts
                    sample["rtt_samples"] = len(self.metrics.rtt)
                handle.write(json.dumps(sample) + "\n")
                handle.flush()
                self.samples.append(sample)
                if now >= next_gc:
                    next_gc = now + self.gc_interval
                    probe_started = time.monotonic()
                    live = gc_live_set(self.pid)
                    self.gc_probes.append({"t": round(probe_started - started, 1), "live_mib": live})
                self.stop.wait(self.interval)


# --------------------------------------------------------------------------------------
# JFR: the engine already emits synaxis.ServerTickTime once per tick
# --------------------------------------------------------------------------------------


def jfr_start(pid: int, path: Path) -> bool:
    # synaxis.ServerTickTime needs no switch: an event the engine declares itself is on unless a
    # settings file turns it off, and neither default.jfc nor profile.jfc knows about it.
    # The virtual thread events are off in default.jfc and are what shows a connection whose
    # reader, writer or login timeout thread outlived it: every start must have an end.
    output = shell(["jcmd", str(pid), "JFR.start", "name=soak", "settings=default",
                    f"filename={path}", "maxsize=512m",
                    "jdk.VirtualThreadStart#enabled=true", "jdk.VirtualThreadEnd#enabled=true"],
                   timeout=60.0)
    print(f"JFR.start: {output.strip()}", flush=True)
    return "Started recording" in output


def jfr_dump(pid: int, path: Path) -> None:
    print(shell(["jcmd", str(pid), "JFR.dump", "name=soak", f"filename={path}"], timeout=180.0).strip(),
          flush=True)
    print(shell(["jcmd", str(pid), "JFR.stop", "name=soak"], timeout=120.0).strip(), flush=True)


def parse_iso(value: str) -> float:
    """Seconds since the epoch out of the ISO timestamp jfr prints."""
    return time.mktime(time.strptime(value[:19], "%Y-%m-%dT%H:%M:%S"))


def tick_summary(jfr: Path) -> dict:
    """p50 / p99 / max of synaxis.ServerTickTime, whole run and per minute."""
    # --stack-depth 0 is what keeps this parseable: every tick event shares one call site, and
    # printing it per event turns a 15 MiB document into a gigabyte of repeated frames.
    raw = shell(["jfr", "print", "--events", "synaxis.ServerTickTime", "--stack-depth", "0",
                 "--json", str(jfr)], timeout=600.0)
    if not raw.strip():
        return {"error": "jfr print produced no output"}
    events = json.loads(raw)["recording"]["events"]
    if not events:
        return {"error": "no synaxis.ServerTickTime events in the recording"}
    samples = []
    for event in events:
        values = event["values"]
        samples.append((parse_iso(values["startTime"]), duration_ms(values["duration"])))
    samples.sort()
    base = samples[0][0]
    buckets: dict[int, list[float]] = {}
    for when, value in samples:
        buckets.setdefault(int((when - base) // 60), []).append(value)
    return {
        "ticks": len(samples),
        "overall": distribution([value for _, value in samples]),
        "per_minute": {str(minute): distribution(values) for minute, values in sorted(buckets.items())},
    }


def duration_ms(value) -> float:
    if isinstance(value, (int, float)):
        return float(value) / 1e6  # nanoseconds
    text = str(value)
    if text.startswith("PT"):
        return float(text[2:].rstrip("S")) * 1000.0
    return float(text)


def distribution(values: list[float]) -> dict:
    ordered = sorted(values)
    return {
        "count": len(ordered),
        "mean_ms": round(statistics.fmean(ordered), 3),
        "p50_ms": round(percentile(ordered, 0.50), 3),
        "p99_ms": round(percentile(ordered, 0.99), 3),
        "max_ms": round(ordered[-1], 3),
        "over_50ms": sum(1 for value in ordered if value > 50.0),
    }


def percentile(ordered: list[float], fraction: float) -> float:
    if not ordered:
        return 0.0
    index = min(int(math.ceil(fraction * len(ordered))) - 1, len(ordered) - 1)
    return ordered[max(index, 0)]


# --------------------------------------------------------------------------------------
# Attacks: the malicious probe subcommands of docs/TEST.md section 3, run as they ship
# --------------------------------------------------------------------------------------

ATTACKS = {
    "zero-frame": ["zero-frame"],
    "alloc-bomb": ["alloc-bomb"],
    "nbt-bomb": ["nbt-bomb", "--mib", "3"],
    "premature-finish": ["premature-finish"],
    "bad-version": ["bad-version", "--protocol", "775"],
    "deep-recursion": ["deep-recursion", "--depth", "200"],
}


class Attacker(threading.Thread):
    def __init__(self, host: str, port: int, names: list[str], interval: float, out: Path) -> None:
        super().__init__(name="attacker", daemon=True)
        self.host, self.port = host, port
        self.names = names
        self.interval = interval
        self.out = out
        self.stop = threading.Event()
        self.results: list[dict] = []

    def run(self) -> None:
        index = 0
        with self.out.open("w", encoding="utf-8") as handle:
            while not self.stop.is_set():
                name = self.names[index % len(self.names)]
                index += 1
                started = time.monotonic()
                command = [sys.executable, str(PROBE), "--host", self.host, "--port", str(self.port),
                           "--username", f"atk{index:05d}", "--timeout", "10", *ATTACKS[name]]
                try:
                    done = subprocess.run(command, capture_output=True, text=True, timeout=90,
                                          check=False)
                    code, tail = done.returncode, done.stdout.strip().splitlines()[-2:]
                except (OSError, subprocess.TimeoutExpired) as error:
                    code, tail = -1, [str(error)]
                result = {"attack": name, "exit": code, "seconds": round(time.monotonic() - started, 2),
                          "tail": tail}
                handle.write(json.dumps(result) + "\n")
                handle.flush()
                self.results.append(result)
                self.stop.wait(self.interval)


# --------------------------------------------------------------------------------------
# The run itself
# --------------------------------------------------------------------------------------


def login(host: str, port: int, username: str, timeout: float, index: int,
          expire: float | None, metrics: Metrics) -> LoadClient | None:
    started = time.monotonic()
    with metrics.lock:
        metrics.login_attempts += 1
    session = None
    try:
        session = QuietSession(argparse.Namespace(host=host, port=port, username=username,
                                                  timeout=timeout))
        session.enter_play()
    except probe.Disconnected as disconnected:
        metrics.fail(f"disconnect:{disconnected.reason}")
        if session:
            session.close()
        return None
    except (OSError, EOFError, ValueError, probe.ClosedByServer) as error:
        metrics.fail(f"{type(error).__name__}:{error}")
        if session:
            session.close()
        return None
    with metrics.lock:
        metrics.login_latencies.append(time.monotonic() - started)
    return LoadClient(session, username, index, expire)


class LoginPool:
    """Logins block for as long as the server takes, so they never run on the selector loop."""

    def __init__(self, workers: int, host: str, port: int, timeout: float, metrics: Metrics) -> None:
        self.requests: queue.Queue = queue.Queue()
        self.ready: queue.Queue = queue.Queue()
        self.metrics = metrics
        self.host, self.port, self.timeout = host, port, timeout
        self.stop = threading.Event()
        self.threads = [threading.Thread(target=self.work, name=f"login-{index}", daemon=True)
                        for index in range(workers)]
        for thread in self.threads:
            thread.start()

    def work(self) -> None:
        while not self.stop.is_set():
            try:
                username, index, hold = self.requests.get(timeout=0.2)
            except queue.Empty:
                continue
            expire = time.monotonic() + hold if hold else None
            client = login(self.host, self.port, username, self.timeout, index, expire, self.metrics)
            if client is not None:
                self.ready.put(client)

    def submit(self, username: str, index: int, hold: float | None) -> None:
        self.requests.put((username, index, hold))

    def shutdown(self) -> None:
        self.stop.set()


def run(args: argparse.Namespace) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    metrics = Metrics()
    clients: list[LoadClient] = []
    selector = selectors.DefaultSelector()
    pool = LoginPool(args.login_workers, args.host, args.port, args.timeout, metrics)

    pid = args.pid or find_pid()
    jfr_path = out / "soak.jfr"
    recording = bool(pid) and not args.no_jfr and jfr_start(pid, jfr_path)
    sampler = None
    if pid and not args.no_sample:
        sampler = Sampler(pid, args.sample_interval, args.gc_probe_interval,
                          out / "samples.jsonl", metrics, clients)
        sampler.start()
    attacker = None
    if args.attacks:
        attacker = Attacker(args.host, args.port, args.attacks.split(","), args.attack_interval,
                            out / "attacks.jsonl")
        attacker.start()

    print(f"soak: pid={pid} connections={args.connections} duration={args.duration}s "
          f"churn={args.churn_hz}/s out={out}", flush=True)

    for index in range(args.connections):
        pool.submit(f"soak{index:04d}", index, None)

    started = time.monotonic()
    deadline = started + args.duration
    move_interval = 1.0 / args.move_hz if args.move_hz else 0.0
    echo_interval = 1.0 / args.echo_hz if args.echo_hz else 0.0
    churn_interval = 1.0 / args.churn_hz if args.churn_hz else 0.0
    next_churn = started + churn_interval if churn_interval else math.inf
    churn_index = 0
    churn_started = 0
    churn_finished = 0
    connected_peak = 0

    while True:
        now = time.monotonic()
        if now >= deadline:
            break
        while True:
            try:
                client = pool.ready.get_nowait()
            except queue.Empty:
                break
            client.connection.socket.settimeout(1.0)
            clients.append(client)
            selector.register(client.connection.socket, selectors.EVENT_READ, client)
        for key, _ in selector.select(timeout=0.01):
            key.data.readable(metrics)
        now = time.monotonic()
        for client in clients:
            if client.closed:
                continue
            if client.expire is not None and now >= client.expire:
                client.close("churn hang up")
                continue
            client.step(now, started, move_interval, echo_interval, metrics)
        closed = [client for client in clients if client.closed]
        for client in closed:
            try:
                selector.unregister(client.connection.socket)
            except (KeyError, ValueError):
                pass
            clients.remove(client)
            if client.expire is None:
                with metrics.lock:
                    metrics.steady_losses += 1
                print(f"steady connection {client.username} lost: {client.close_reason}", flush=True)
            else:
                churn_finished += 1
        connected_peak = max(connected_peak, len(clients))
        if now >= next_churn:
            next_churn = now + churn_interval
            pool.submit(f"chrn{churn_index:05d}", 1000 + (churn_index % 64), args.churn_hold)
            churn_index += 1
            churn_started += 1

    elapsed = time.monotonic() - started
    if attacker:
        attacker.stop.set()
        attacker.join(timeout=120)
    pool.shutdown()
    for client in clients:
        client.close("end of run")
    selector.close()

    settle = {}
    if sampler is not None:
        time.sleep(args.settle)
        if args.gc_probe_interval:
            settle["live_mib_after_disconnect"] = gc_live_set(pid)
        settle["threads_after_disconnect"] = thread_count(pid)
        settle["fds_after_disconnect"] = fd_count(pid)
        sampler.stop.set()
        sampler.join(timeout=120)

    ticks = {}
    if recording:
        jfr_dump(pid, jfr_path)
        ticks = tick_summary(jfr_path)

    with metrics.lock:
        summary = {
            "profile": {key: getattr(args, key) for key in
                        ("connections", "duration", "move_hz", "echo_hz", "churn_hz", "churn_hold",
                         "attacks", "attack_interval")},
            "pid": pid,
            "elapsed_s": round(elapsed, 1),
            "connected_peak": connected_peak,
            "connected_end": len(clients),
            "login_attempts": metrics.login_attempts,
            "login_failures": metrics.login_failures,
            "login_latency_s": distribution([value * 1000 for value in metrics.login_latencies]),
            "steady_connections_lost": metrics.steady_losses,
            "churn_started": churn_started,
            "churn_finished": churn_finished,
            "disconnect_reasons": metrics.disconnects,
            "keep_alives": metrics.keep_alives,
            "moves": metrics.moves,
            "moves_per_second": round(metrics.moves / elapsed, 1),
            "echoes_sent": metrics.echoes_sent,
            "rtt_ms": distribution([value * 1000 for value in metrics.rtt]),
            "ticks": ticks,
            "gc_probes": sampler.gc_probes if sampler else [],
            "settle": settle,
            "attacks": attacker.results if attacker else [],
            "samples": sampler.samples if sampler else [],
        }
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    report(summary)
    return 0


def find_pid() -> int:
    output = shell(["pgrep", "-f", "io.github.bearisbug.synaxis.app.Main"]).split()
    return int(output[0]) if output else 0


def report(summary: dict) -> None:
    print("-" * 78, flush=True)
    print(f"elapsed={summary['elapsed_s']}s peak_connections={summary['connected_peak']} "
          f"logins={summary['login_attempts']} failures={summary['login_failures']}", flush=True)
    print(f"steady connections lost={summary['steady_connections_lost']} "
          f"disconnects={summary['disconnect_reasons']}", flush=True)
    print(f"moves={summary['moves']} ({summary['moves_per_second']}/s) "
          f"keep_alives={summary['keep_alives']}", flush=True)
    print(f"rtt_ms={summary['rtt_ms']}", flush=True)
    if summary["ticks"].get("overall"):
        print(f"tick_ms={summary['ticks']['overall']}", flush=True)
    samples = summary["samples"]
    if samples:
        first, last = samples[0], samples[-1]
        for key in ("threads", "fds", "OU", "MU", "rss_mib"):
            if key in first and key in last:
                print(f"{key}: first={first[key]} last={last[key]}", flush=True)
        print(f"peak cpu={max(sample['cpu_percent'] for sample in samples)}% "
              f"peak harness cpu={max(sample['harness_cpu_percent'] for sample in samples)}% "
              f"peak load1={max(sample['load1'] for sample in samples)}", flush=True)
    if summary["gc_probes"]:
        print(f"live set MiB: {[probe_['live_mib'] for probe_ in summary['gc_probes']]} "
              f"settle={summary['settle']}", flush=True)
    for result in summary["attacks"]:
        print(f"attack {result['attack']} exit={result['exit']} {result['seconds']}s", flush=True)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    subparsers = parser.add_subparsers(dest="command", required=True)

    runner = subparsers.add_parser("run", help="drive a load profile against a running server")
    runner.add_argument("--host", default="127.0.0.1")
    runner.add_argument("--port", type=int, default=25565)
    runner.add_argument("--timeout", type=float, default=30.0)
    runner.add_argument("--connections", type=int, default=64, help="connections held for the run")
    runner.add_argument("--duration", type=float, default=1200.0, help="seconds")
    runner.add_argument("--move-hz", type=float, default=10.0, help="position packets per connection")
    runner.add_argument("--echo-hz", type=float, default=0.5, help="synaxis:main round trips per connection")
    runner.add_argument("--churn-hz", type=float, default=0.0, help="logins per second that hang up again")
    runner.add_argument("--churn-hold", type=float, default=5.0, help="seconds a churn connection stays")
    runner.add_argument("--attacks", default="", help="comma separated: " + ",".join(ATTACKS))
    runner.add_argument("--attack-interval", type=float, default=5.0)
    runner.add_argument("--login-workers", type=int, default=8)
    runner.add_argument("--sample-interval", type=float, default=10.0)
    runner.add_argument("--gc-probe-interval", type=float, default=300.0,
                        help="seconds between forced full GCs, 0 disables the live set series")
    runner.add_argument("--settle", type=float, default=15.0,
                        help="seconds to wait after the last disconnect before the final sample")
    runner.add_argument("--pid", type=int, default=0, help="server pid, found by pgrep when unset")
    runner.add_argument("--no-jfr", action="store_true")
    runner.add_argument("--no-sample", action="store_true",
                        help="drive connections only; used by the extra harness processes of an "
                             "overload run, where one process does the sampling for all of them")
    runner.add_argument("--out", required=True)
    runner.set_defaults(run=run)

    ticks = subparsers.add_parser("ticks", help="summarise ServerTickTime out of a JFR recording")
    ticks.add_argument("--jfr", required=True)
    ticks.set_defaults(run=lambda args: print(json.dumps(tick_summary(Path(args.jfr)), indent=2)) or 0)

    return parser


def main() -> int:
    args = build_parser().parse_args()
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
