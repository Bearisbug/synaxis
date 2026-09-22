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

    run     drive a load profile against a running server (core mode) and write the samples
    play    drive N players through the mod handshake into the RTS slice (gameplay mode): camera,
            orders and selections, with the inbound bytes split by channel and message
    ticks   summarise ``synaxis.ServerTickTime`` out of a JFR recording (run and play do this too)

Usage, see docs/research/soak-2026-09-18/README.md and docs/research/soak-2026-09-22/README.md:

    python3 tools/loadtest/soak.py run --connections 64 --duration 1200 --out /tmp/soak/steady
    python3 tools/loadtest/soak.py play --port 25566 --connections 50 --duration 600 --units 1000 \
        --server-log /tmp/synaxis-play/server.log --out /tmp/synaxis-play/gate
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
    if not ordered:
        # A profile can legitimately measure nothing here: --connections 0 has no logins,
        # --echo-hz 0 has no round trips. The run still owes the caller its summary.
        return {"count": 0}
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
    if not pid:
        print("soak: no server process found by pgrep -f io.github.bearisbug.synaxis.app.Main; "
              "start the server or pass --pid. Sampling nothing would look like a clean run.",
              file=sys.stderr, flush=True)
        return 2
    jfr_path = out / "soak.jfr"
    recording = not args.no_jfr
    if recording and not jfr_start(pid, jfr_path):
        print("soak: JFR.start failed, the run would carry no tick distribution; put the JDK bin "
              "on PATH or pass --no-jfr.", file=sys.stderr, flush=True)
        return 2
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
            "login_latency_ms": distribution([value * 1000 for value in metrics.login_latencies]),
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
            "gc_probes": sampler.gc_probes,
            "settle": settle,
            "attacks": attacker.results if attacker else [],
            "samples": sampler.samples,
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
        # The peaks span every sample, including the ones before the first login and after the
        # last disconnect, so print the sample each one came from: a peak at clients=0 says
        # nothing about the load window.
        peak_cpu = max(samples, key=lambda sample: sample["cpu_percent"])
        peak_harness = max(samples, key=lambda sample: sample["harness_cpu_percent"])
        print(f"peak cpu={peak_cpu['cpu_percent']}% (t={peak_cpu['t']}s clients={peak_cpu['clients']}) "
              f"peak harness cpu={peak_harness['harness_cpu_percent']}% "
              f"(t={peak_harness['t']}s clients={peak_harness['clients']}) "
              f"peak load1={max(sample['load1'] for sample in samples)}", flush=True)
    if summary["gc_probes"]:
        print(f"live set MiB: {[probe_['live_mib'] for probe_ in summary['gc_probes']]} "
              f"settle={summary['settle']}", flush=True)
    for result in summary["attacks"]:
        print(f"attack {result['attack']} exit={result['exit']} {result['seconds']}s", flush=True)


# --------------------------------------------------------------------------------------
# Play mode: N players inside the RTS slice, for the §15 budgets of docs/DESIGN-M2.md
# --------------------------------------------------------------------------------------

#: The standing surface of a generated map, MapTerrain.GROUND_HEIGHT: where the player entity is
#: put when it follows the camera anchor, and the y every move order is aimed at.
GROUND_Y = 40.0

#: The names the summary uses for the rts:game messages, by msg_id (docs/DESIGN-M2.md §8).
GAME_MESSAGE_NAMES = {
    probe.S2C_UNIT_SPAWN: "S2C_UNIT_SPAWN",
    probe.S2C_UNIT_STATE: "S2C_UNIT_STATE",
    probe.S2C_UNIT_DESPAWN: "S2C_UNIT_DESPAWN",
    probe.S2C_ORDER_RESULT: "S2C_ORDER_RESULT",
}

#: The play packets the harness has to read past the id: what it answers, what tells it it is out.
CONTROL_PACKETS = frozenset(("KeepAlivePacket", "PlayerPositionAndLookPacket", "DisconnectPacket"))

PLUGIN_MESSAGE = "PluginMessagePacket"

#: How many entries of the channel a summary lists on its own; the rest are folded into "other".
TOP_PACKETS = 20

#: A selection carries at most this many units (C2SSelect.MAX_UNITS), and §15 counts one order
#: operation as at most this many packets.
SELECT_MAX_UNITS = 8

#: Two snapshots further apart than this (seconds) had an empty interest set between them, which
#: is a fact about the camera, not about the 10 Hz of API-M2-006.
SNAPSHOT_GAP_LIMIT = 1.0

PLAY_PACKET_NAMES = probe.REGISTRIES["SERVER_PLAY"]


def read_varint_at(data, index: int) -> tuple[int, int]:
    """A var-int out of a buffer, returned with the index just past it."""
    value, shift = 0, 0
    while True:
        byte = data[index]
        index += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return value, index
        if shift > 35:
            raise ValueError("var-int is too long")


def packet_name(packet_id: int) -> str:
    if packet_id < len(PLAY_PACKET_NAMES):
        return PLAY_PACKET_NAMES[packet_id]
    return f"Unknown(0x{packet_id:02x})"


def peek_play_packet(connection: probe.Connection, frame: bytes) -> tuple[str, bytes | None]:
    """The packet name of a play frame, plus its body for the few packets the harness reads.

    Every other body is left alone — a chunk packet is only decompressed as far as its packet id,
    which is what keeps a harness that counts bytes from spending its CPU on the bytes.
    """
    if connection.compression_threshold < 0:
        packet_id, _ = read_varint_at(frame, 0)
        name = packet_name(packet_id)
        return name, frame if name == PLUGIN_MESSAGE or name in CONTROL_PACKETS else None
    data_length, offset = read_varint_at(frame, 0)
    if data_length == 0:
        body = frame[offset:]
        packet_id, _ = read_varint_at(body, 0)
        name = packet_name(packet_id)
        return name, body if name == PLUGIN_MESSAGE or name in CONTROL_PACKETS else None
    head = zlib.decompressobj().decompress(frame[offset:], 8)
    packet_id, _ = read_varint_at(head, 0)
    name = packet_name(packet_id)
    if name == PLUGIN_MESSAGE or name in CONTROL_PACKETS:
        return name, zlib.decompress(frame[offset:])
    return name, None


def prefix_size(length: int) -> int:
    """The bytes the length prefix of a frame of that many bytes takes on the wire."""
    return 1 if length < 0x80 else 2 if length < 0x4000 else 3


class PlayClient(LoadClient):
    """One player of the RTS slice: camera reports, orders and selections, and the inbound ledger.

    The camera anchor wanders over the whole map on a Lissajous figure, and the player entity is
    walked to the anchor with a position packet on every report: the server side of REQ-M2-018
    (teleporting the player to the anchor) is not in place yet, so without that packet nothing
    would stream chunks or change the interest set.
    """

    def __init__(self, session: QuietSession, username: str, index: int, args: argparse.Namespace) -> None:
        super().__init__(session, username, index, None)
        half = args.map_size / 2.0
        self.center = half
        self.amplitude = max(8.0, half - 8.0)
        # Angular speed is the pan speed over the amplitude; the two axes are detuned by the golden
        # ratio so that no connection retraces a closed loop inside one run
        omega = args.pan_speed / self.amplitude
        self.omega_x = omega
        self.omega_z = omega * 0.618034
        self.phase_x = index * 2 * math.pi / max(1, args.connections)
        self.phase_z = index * 2 * math.pi * 0.381966 / max(1, args.connections) + math.pi / 2
        self.zoom = args.zoom
        self.anchor = (half, half)
        self.next_camera = 0.0
        self.next_order = 0.0
        self.next_select = 0.0
        self.order_seq = 0
        self.order_index = 0
        self.select_index = 0
        self.connected_at = 0.0
        self.closed_at = 0.0
        # The interest set as the server describes it, and the units this player commands
        self.units_seen: set[int] = set()
        self.commandable: list[int] = []
        self.commandable_set: set[int] = set()
        # Inbound ledger: packet name -> [count, wire bytes]; message name -> [count, payload
        # bytes, wire bytes, max payload bytes, max entries]
        self.engine: dict[str, list[int]] = {}
        self.game: dict[str, list[int]] = {}
        self.engine_bytes = 0
        self.game_bytes = 0
        # The gaps between two consecutive snapshots. A snapshot is only sent to a player whose
        # interest set is not empty, so a camera over an empty stretch of map receives none at all;
        # gaps over SNAPSHOT_GAP_LIMIT are such stretches, not late snapshots, and are left out
        self.last_snapshot = 0.0
        self.snapshot_gaps: list[float] = []
        self.order_codes: dict[str, int] = {}
        self.camera_reports = 0
        self.orders_sent = 0
        self.selects_sent = 0

    def close(self, reason: str) -> None:
        if not self.closed:
            self.closed_at = time.monotonic()
        super().close(reason)

    def connected_seconds(self) -> float:
        if not self.connected_at:
            return 0.0
        end = self.closed_at if self.closed else time.monotonic()
        return max(0.0, end - self.connected_at)

    # -- reading ----------------------------------------------------------------------

    def readable(self, metrics: Metrics) -> None:
        try:
            chunk = self.connection.socket.recv(262144)
        except (BlockingIOError, socket.timeout):
            return
        except OSError as error:
            self.close(f"recv: {error}")
            return
        if not chunk:
            self.close("closed by server")
            return
        incoming = self.connection.incoming
        incoming += chunk
        size = len(incoming)
        position = 0
        try:
            while position < size:
                length, start = 0, position
                shift = 0
                while True:
                    if start >= size:
                        length = -1
                        break
                    byte = incoming[start]
                    start += 1
                    length |= (byte & 0x7F) << shift
                    shift += 7
                    if not byte & 0x80:
                        break
                    if shift > 35:
                        raise ValueError("var-int is too long")
                if length < 0 or start + length > size:
                    break
                self.handle_frame(bytes(incoming[start:start + length]), start - position + length, metrics)
                position = start + length
                if self.closed:
                    break
        except (EOFError, ValueError, zlib.error, IndexError) as error:
            self.close(f"decode: {error}")
        finally:
            del incoming[:position]

    def handle_frame(self, frame: bytes, wire_bytes: int, metrics: Metrics) -> None:
        name, body = peek_play_packet(self.connection, frame)
        if name == PLUGIN_MESSAGE:
            self.handle_plugin_message(body, wire_bytes)
            return
        entry = self.engine.get(name)
        if entry is None:
            self.engine[name] = [1, wire_bytes]
        else:
            entry[0] += 1
            entry[1] += wire_bytes
        self.engine_bytes += wire_bytes
        if body is None:
            return
        reader = probe.Reader(body)
        reader.varint()  # the packet id
        if name == "KeepAlivePacket":
            self.send("ClientKeepAlivePacket", probe.Writer().long(reader.long()).bytes())
            with metrics.lock:
                metrics.keep_alives += 1
        elif name == "PlayerPositionAndLookPacket":
            teleport_id = reader.varint()
            self.position = (reader.double(), reader.double(), reader.double())
            self.send("ClientTeleportConfirmPacket", probe.Writer().varint(teleport_id).bytes())
        elif name == "DisconnectPacket":
            reason = probe.disconnect_reason("DisconnectPacket", reader)
            metrics.disconnect(reason)
            self.close(f"disconnect: {reason}")

    def handle_plugin_message(self, body: bytes, wire_bytes: int) -> None:
        _, index = read_varint_at(body, 0)
        channel_length, index = read_varint_at(body, index)
        channel = body[index:index + channel_length]
        index += channel_length
        if channel != b"rts:game":
            # Whatever else arrives on a custom payload is the engine's, not the slice's
            entry = self.engine.setdefault(PLUGIN_MESSAGE + ":" + channel.decode("utf-8", "replace"), [0, 0])
            entry[0] += 1
            entry[1] += wire_bytes
            self.engine_bytes += wire_bytes
            return
        payload = body[index:]
        payload_bytes = len(payload)
        msg_id, index = read_varint_at(payload, 0)
        name = GAME_MESSAGE_NAMES.get(msg_id, f"msg_0x{msg_id:02x}")
        entries = 0
        if msg_id == probe.S2C_UNIT_STATE:
            _, index = read_varint_at(payload, index)  # server_tick
            entries, _ = read_varint_at(payload, index)
            now = time.monotonic()
            if self.last_snapshot and now - self.last_snapshot <= SNAPSHOT_GAP_LIMIT:
                self.snapshot_gaps.append(now - self.last_snapshot)
            self.last_snapshot = now
        elif msg_id == probe.S2C_UNIT_SPAWN:
            unit_id, index = read_varint_at(payload, index)
            _, index = read_varint_at(payload, index)  # entity_id
            _, index = read_varint_at(payload, index)  # archetype
            index += 1  # team
            _, index = read_varint_at(payload, index)  # hp
            _, index = read_varint_at(payload, index)  # max_hp
            flags = payload[index]
            self.units_seen.add(unit_id)
            if flags & 0x01 and unit_id not in self.commandable_set:
                self.commandable_set.add(unit_id)
                self.commandable.append(unit_id)
        elif msg_id == probe.S2C_UNIT_DESPAWN:
            entries, _ = read_varint_at(payload, index)
        elif msg_id == probe.S2C_ORDER_RESULT:
            _, index = read_varint_at(payload, index)  # order_seq
            code = payload[index]
            code_name = (probe.ORDER_RESULT_CODES[code] if code < len(probe.ORDER_RESULT_CODES)
                         else f"unknown({code})")
            self.order_codes[code_name] = self.order_codes.get(code_name, 0) + 1
        entry = self.game.get(name)
        if entry is None:
            self.game[name] = [1, payload_bytes, wire_bytes, payload_bytes, entries]
        else:
            entry[0] += 1
            entry[1] += payload_bytes
            entry[2] += wire_bytes
            if payload_bytes > entry[3]:
                entry[3] = payload_bytes
            if entries > entry[4]:
                entry[4] = entries
        self.game_bytes += wire_bytes

    # -- writing ----------------------------------------------------------------------

    def play_step(self, now: float, started: float, camera_interval: float, order_interval: float,
                  select_interval: float, metrics: Metrics) -> None:
        try:
            if now >= self.next_camera:
                self.next_camera = now + camera_interval
                elapsed = now - started
                x = self.center + self.amplitude * math.sin(self.omega_x * elapsed + self.phase_x)
                z = self.center + self.amplitude * math.sin(self.omega_z * elapsed + self.phase_z)
                self.anchor = (x, z)
                self.send("ClientPluginMessagePacket", probe.plugin_message_payload(
                    probe.GAME_CHANNEL, probe.camera_body(x, z, self.zoom, 0.0)))
                self.camera_reports += 1
                self.send("ClientPlayerPositionAndRotationPacket", (probe.Writer()
                          .raw(struct.pack(">ddd", x, GROUND_Y, z))
                          .raw(struct.pack(">ff", 0.0, 0.0))
                          .raw(b"\x01")
                          .bytes()))
                self.position = (x, GROUND_Y, z)
                with metrics.lock:
                    metrics.moves += 1
            if order_interval and now >= self.next_order and self.commandable:
                self.next_order = now + order_interval
                unit_id = self.commandable[self.order_index % len(self.commandable)]
                self.order_index += 1
                self.order_seq += 1
                x, z = self.anchor
                self.send("ClientPluginMessagePacket", probe.plugin_message_payload(
                    probe.GAME_CHANNEL,
                    probe.move_order_body(self.order_seq, self.order_seq, unit_id, (x, GROUND_Y, z))))
                self.orders_sent += 1
            if select_interval and now >= self.next_select and self.commandable:
                self.next_select = now + select_interval
                first = (self.select_index * SELECT_MAX_UNITS) % len(self.commandable)
                self.select_index += 1
                chosen = self.commandable[first:first + SELECT_MAX_UNITS]
                self.send("ClientPluginMessagePacket", probe.plugin_message_payload(
                    probe.GAME_CHANNEL, probe.select_body(chosen)))
                self.selects_sent += 1
        except OSError as error:
            self.close(f"send: {error}")


class ServerLogWatch:
    """Counts what the server logged during the run: WARN and ERROR lines, and kicks."""

    def __init__(self, path: str) -> None:
        self.path = Path(path) if path else None
        self.offset = self.path.stat().st_size if self.path and self.path.exists() else 0

    def finish(self) -> dict:
        if self.path is None:
            return {"path": None}
        result = {"path": str(self.path), "warn_lines": 0, "error_lines": 0, "kick_lines": 0, "samples": []}
        if not self.path.exists():
            return result
        seen: set[str] = set()
        with self.path.open("rb") as handle:
            handle.seek(self.offset)
            for raw in handle:
                line = raw.decode("utf-8", "replace").rstrip()
                level = " WARN " if " WARN " in line else " ERROR " if " ERROR " in line else None
                if "Kicking connection_id=" in line:
                    result["kick_lines"] += 1
                if level is None:
                    continue
                result["warn_lines" if level == " WARN " else "error_lines"] += 1
                # The message without its timestamp and thread, so that one budgeted line counts
                # once whatever the connection id in it
                message = re.sub(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>",
                                 line.split(level, 1)[1])
                if message not in seen and len(seen) < 10:
                    seen.add(message)
                    result["samples"].append(level.strip() + " " + message)
        return result


class LedgerWatch:
    """Reads what the event ledger (docs/DESIGN-M2.md §20) appended during the run.

    The pathfinding budget of §15 is a per tick occupancy, and the ledger has no tick number: the
    ``path_ms`` of every search is summed into 50 ms buckets of its ``ts`` instead, which is the
    closest a reader of the ledger can get to "what one tick spent on searching".
    """

    def __init__(self, directory: str) -> None:
        self.directory = Path(directory)
        self.offsets = {path: path.stat().st_size for path in self.directory.glob("events-*.jsonl")} \
            if self.directory.is_dir() else {}

    def finish(self) -> dict:
        counts: dict[str, int] = {}
        path_ms: list[float] = []
        path_nodes_max = 0
        buckets: dict[int, float] = {}
        if self.directory.is_dir():
            for path in sorted(self.directory.glob("events-*.jsonl")):
                with path.open("rb") as handle:
                    handle.seek(self.offsets.get(path, 0))
                    for raw in handle:
                        try:
                            event = json.loads(raw)
                        except ValueError:
                            continue
                        name = event.get("event", "?")
                        if name == "unit_order_rejected":
                            name = f"unit_order_rejected:{event.get('code', '?')}"
                        counts[name] = counts.get(name, 0) + 1
                        if "path_ms" not in event:
                            continue
                        milliseconds = float(event["path_ms"])
                        path_ms.append(milliseconds)
                        path_nodes_max = max(path_nodes_max, int(event.get("path_nodes", 0)))
                        bucket = int(ledger_seconds(str(event.get("ts", ""))) / 0.05)
                        buckets[bucket] = buckets.get(bucket, 0.0) + milliseconds
        return {
            "events": dict(sorted(counts.items())),
            "searches": len(path_ms),
            "path_ms": distribution(path_ms),
            "path_nodes_max": path_nodes_max,
            "tick_bucket_ms": distribution(list(buckets.values())),
        }


def ledger_seconds(timestamp: str) -> float:
    """Seconds into the day out of the ledger's ISO instant, with whatever fraction it carries."""
    clock = timestamp.split("T", 1)[-1].rstrip("Z")
    hours, minutes, seconds = clock.split(":")
    return int(hours) * 3600 + int(minutes) * 60 + float(seconds)


def fold_ledger(entries: dict[str, list[int]], keys: list[str], top: int) -> dict:
    ordered = sorted(entries.items(), key=lambda item: item[1][1], reverse=True)
    folded = {name: dict(zip(keys, values)) for name, values in ordered[:top]}
    if len(ordered) > top:
        rest = [sum(values[column] for _, values in ordered[top:]) for column in range(len(keys))]
        folded["other"] = dict(zip(keys, rest))
    return folded


def play(args: argparse.Namespace) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    metrics = Metrics()
    clients: list[PlayClient] = []
    selector = selectors.DefaultSelector()

    pid = args.pid or find_pid()
    if not pid:
        print("soak: no server process found by pgrep -f io.github.bearisbug.synaxis.app.Main; "
              "start the server or pass --pid. Sampling nothing would look like a clean run.",
              file=sys.stderr, flush=True)
        return 2
    jfr_path = out / "soak.jfr"
    recording = not args.no_jfr
    if recording and not jfr_start(pid, jfr_path):
        print("soak: JFR.start failed, the run would carry no tick distribution; put the JDK bin "
              "on PATH or pass --no-jfr.", file=sys.stderr, flush=True)
        return 2
    server_log = ServerLogWatch(args.server_log)
    ledger = LedgerWatch(args.events_dir)
    sampler = Sampler(pid, args.sample_interval, args.gc_probe_interval, out / "samples.jsonl", metrics, clients)
    sampler.start()

    print(f"soak play: pid={pid} connections={args.connections} duration={args.duration}s "
          f"units={args.units} camera={args.camera_hz}Hz orders={args.order_hz}/s out={out}", flush=True)

    ready: queue.Queue = queue.Queue()

    def log_in(index: int) -> None:
        client_session = None
        username = f"play{index:04d}"
        with metrics.lock:
            metrics.login_attempts += 1
        login_started = time.monotonic()
        try:
            client_session = QuietSession(argparse.Namespace(host=args.host, port=args.port,
                                                             username=username, timeout=args.timeout))
            client_session.enter_play()
        except probe.Disconnected as disconnected:
            metrics.fail(f"disconnect:{disconnected.reason}")
            if client_session:
                client_session.close()
            return
        except (OSError, EOFError, ValueError, probe.ClosedByServer) as error:
            metrics.fail(f"{type(error).__name__}:{error}")
            if client_session:
                client_session.close()
            return
        with metrics.lock:
            metrics.login_latencies.append(time.monotonic() - login_started)
        ready.put(PlayClient(client_session, username, index, args))

    pending: queue.Queue = queue.Queue()
    for index in range(args.connections):
        pending.put(index)

    def worker() -> None:
        while True:
            try:
                index = pending.get_nowait()
            except queue.Empty:
                return
            log_in(index)

    workers = [threading.Thread(target=worker, name=f"login-{i}", daemon=True) for i in range(args.login_workers)]
    for thread in workers:
        thread.start()

    started = time.monotonic()
    deadline = started + args.duration
    camera_interval = 1.0 / args.camera_hz if args.camera_hz else math.inf
    order_interval = 1.0 / args.order_hz if args.order_hz else 0.0
    select_interval = args.select_interval
    connected_peak = 0
    lost: list[PlayClient] = []

    while True:
        now = time.monotonic()
        if now >= deadline:
            break
        while True:
            try:
                client = ready.get_nowait()
            except queue.Empty:
                break
            client.connection.socket.settimeout(1.0)
            client.connected_at = time.monotonic()
            clients.append(client)
            selector.register(client.connection.socket, selectors.EVENT_READ, client)
        for key, _ in selector.select(timeout=0.005):
            key.data.readable(metrics)
        now = time.monotonic()
        for client in clients:
            if not client.closed:
                client.play_step(now, started, camera_interval, order_interval, select_interval, metrics)
        for client in [client for client in clients if client.closed]:
            try:
                selector.unregister(client.connection.socket)
            except (KeyError, ValueError):
                pass
            clients.remove(client)
            lost.append(client)
            with metrics.lock:
                metrics.steady_losses += 1
            print(f"connection {client.username} lost: {client.close_reason}", flush=True)
        connected_peak = max(connected_peak, len(clients))

    elapsed = time.monotonic() - started
    for thread in workers:
        thread.join(timeout=args.timeout)
    while True:
        try:
            late = ready.get_nowait()
        except queue.Empty:
            break
        # Logged in after the deadline: it drove nothing and is closed with the rest, so that the
        # settle sample sees no connection of ours
        late.close("logged in after the deadline")
        lost.append(late)
    everyone = clients + lost
    for client in clients:
        client.close("end of run")
    selector.close()

    settle = {}
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

    # -- the inbound ledger, over every connection that was ever in play ------------------
    connection_seconds = sum(client.connected_seconds() for client in everyone)
    engine: dict[str, list[int]] = {}
    game: dict[str, list[int]] = {}
    for client in everyone:
        for name, values in client.engine.items():
            entry = engine.setdefault(name, [0, 0])
            entry[0] += values[0]
            entry[1] += values[1]
        for name, values in client.game.items():
            entry = game.setdefault(name, [0, 0, 0, 0, 0])
            entry[0] += values[0]
            entry[1] += values[1]
            entry[2] += values[2]
            entry[3] = max(entry[3], values[3])
            entry[4] = max(entry[4], values[4])
    game_by_msg = fold_ledger(game, ["count", "payload_bytes", "wire_bytes", "max_payload_bytes", "max_entries"],
                              len(game))
    for name, values in game_by_msg.items():
        if connection_seconds:
            values["hz_per_connection"] = round(values["count"] / connection_seconds, 3)
            values["bytes_per_s_per_connection"] = round(values["payload_bytes"] / connection_seconds, 1)
        values["bytes_per_s_total"] = round(values["payload_bytes"] / elapsed, 1) if elapsed else 0.0
    if "S2C_UNIT_STATE" in game_by_msg:
        game_by_msg["S2C_UNIT_STATE"]["interval_ms"] = distribution(
            [gap * 1000 for client in everyone for gap in client.snapshot_gaps])
    engine_bytes = sum(values[1] for values in engine.values())
    game_bytes = sum(values[2] for values in game.values())
    order_codes: dict[str, int] = {}
    for client in everyone:
        for code, count in client.order_codes.items():
            order_codes[code] = order_codes.get(code, 0) + count
    units_seen_max = max((len(client.units_seen) for client in everyone), default=0)
    commandable_max = max((len(client.commandable) for client in everyone), default=0)
    live_probes = [probe_["live_mib"] for probe_ in sampler.gc_probes]
    rss = [sample["rss_mib"] for sample in sampler.samples if sample.get("clients", 0) > 0] or \
          [sample["rss_mib"] for sample in sampler.samples]

    with metrics.lock:
        summary = {
            "profile": {key: getattr(args, key) for key in
                        ("connections", "duration", "units", "map_size", "camera_hz", "order_hz",
                         "select_interval", "pan_speed", "zoom", "gc_probe_interval")},
            "pid": pid,
            "elapsed_s": round(elapsed, 1),
            "connected_peak": connected_peak,
            "connected_end": len(clients),
            "login_attempts": metrics.login_attempts,
            "login_failures": metrics.login_failures,
            "login_latency_ms": distribution([value * 1000 for value in metrics.login_latencies]),
            "connections_lost": metrics.steady_losses,
            "disconnect_reasons": metrics.disconnects,
            "keep_alives": metrics.keep_alives,
            "units": {
                "expected": args.units,
                "seen_max": units_seen_max,
                "commandable_max": commandable_max,
                # The outpost stands 220 blocks from the spawn and is the last unit numbered, so a
                # run whose cameras never came within entity view distance of it sees one fewer
                "check": ("unset" if not args.units else "ok" if units_seen_max == args.units
                          else "ok-outpost-unseen" if units_seen_max == args.units - 1 else "mismatch"),
            },
            "uplink": {
                "camera_reports": sum(client.camera_reports for client in everyone),
                "position_packets": metrics.moves,
                "orders_sent": sum(client.orders_sent for client in everyone),
                "selects_sent": sum(client.selects_sent for client in everyone),
            },
            "orders": {
                "sent": sum(client.orders_sent for client in everyone),
                "answered": sum(order_codes.values()),
                "codes": dict(sorted(order_codes.items())),
            },
            "inbound": {
                "connection_seconds": round(connection_seconds, 1),
                "per_connection_bytes_per_s": {
                    "total": round((engine_bytes + game_bytes) / connection_seconds, 1) if connection_seconds else 0.0,
                    "engine": round(engine_bytes / connection_seconds, 1) if connection_seconds else 0.0,
                    "rts_game": round(game_bytes / connection_seconds, 1) if connection_seconds else 0.0,
                },
                "per_connection_distribution": {
                    "engine": distribution([client.engine_bytes / client.connected_seconds()
                                            for client in everyone if client.connected_seconds() > 0]),
                    "rts_game": distribution([client.game_bytes / client.connected_seconds()
                                              for client in everyone if client.connected_seconds() > 0]),
                },
                "engine_by_packet": fold_ledger(engine, ["count", "wire_bytes"], TOP_PACKETS),
                "rts_game_by_msg": game_by_msg,
            },
            "ticks": ticks,
            "heap": {
                "live_mib_max": max(live_probes, default=0.0),
                "rss_mib_max": max(rss, default=0.0),
                "gc_probes": sampler.gc_probes,
                "settle": settle,
            },
            "pathfinding": ledger.finish(),
            "server_log": server_log.finish(),
            "samples": sampler.samples,
        }
    (out / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    report_play(summary)
    return 0


def report_play(summary: dict) -> None:
    print("-" * 78, flush=True)
    print(f"elapsed={summary['elapsed_s']}s peak_connections={summary['connected_peak']} "
          f"logins={summary['login_attempts']} failures={summary['login_failures']} "
          f"lost={summary['connections_lost']} disconnects={summary['disconnect_reasons']}", flush=True)
    units = summary["units"]
    print(f"units: expected={units['expected']} seen_max={units['seen_max']} "
          f"commandable_max={units['commandable_max']} check={units['check']}", flush=True)
    orders = summary["orders"]
    print(f"orders: sent={orders['sent']} answered={orders['answered']} codes={orders['codes']} "
          f"selects={summary['uplink']['selects_sent']} camera={summary['uplink']['camera_reports']}", flush=True)
    inbound = summary["inbound"]
    print(f"inbound per connection B/s: {inbound['per_connection_bytes_per_s']}", flush=True)
    state = inbound["rts_game_by_msg"].get("S2C_UNIT_STATE")
    if state:
        print(f"S2C_UNIT_STATE: count={state['count']} max_payload_bytes={state['max_payload_bytes']} "
              f"max_entries={state['max_entries']} hz_per_connection={state.get('hz_per_connection')} "
              f"bytes_per_s_per_connection={state.get('bytes_per_s_per_connection')} "
              f"bytes_per_s_total={state['bytes_per_s_total']} interval_ms={state['interval_ms']}", flush=True)
    else:
        print("S2C_UNIT_STATE: none arrived", flush=True)
    if summary["ticks"].get("overall"):
        print(f"tick_ms={summary['ticks']['overall']}", flush=True)
    heap = summary["heap"]
    print(f"heap: live_mib_max={heap['live_mib_max']} rss_mib_max={heap['rss_mib_max']} "
          f"live probes={[probe_['live_mib'] for probe_ in heap['gc_probes']]} settle={heap['settle']}", flush=True)
    path = summary["pathfinding"]
    print(f"pathfinding: searches={path['searches']} path_ms={path['path_ms']} "
          f"tick_bucket_ms={path['tick_bucket_ms']} path_nodes_max={path['path_nodes_max']}", flush=True)
    log = summary["server_log"]
    if log.get("path"):
        print(f"server log: warn={log['warn_lines']} error={log['error_lines']} kicks={log['kick_lines']}", flush=True)
        for sample in log["samples"]:
            print(f"  {sample[:160]}", flush=True)
    samples = summary["samples"]
    if samples:
        peak_cpu = max(samples, key=lambda sample: sample["cpu_percent"])
        peak_harness = max(samples, key=lambda sample: sample["harness_cpu_percent"])
        print(f"peak cpu={peak_cpu['cpu_percent']}% (t={peak_cpu['t']}s clients={peak_cpu['clients']}) "
              f"peak harness cpu={peak_harness['harness_cpu_percent']}% "
              f"(t={peak_harness['t']}s clients={peak_harness['clients']})", flush=True)


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
    runner.add_argument("--out", required=True)
    runner.set_defaults(run=run)

    player = subparsers.add_parser("play", help="drive N players through the RTS slice of a gameplay mode server")
    player.add_argument("--host", default="127.0.0.1")
    player.add_argument("--port", type=int, default=25565)
    player.add_argument("--timeout", type=float, default=30.0)
    player.add_argument("--connections", type=int, default=50, help="players held for the run")
    player.add_argument("--duration", type=float, default=600.0, help="seconds")
    player.add_argument("--units", type=int, default=0,
                        help="the roster size the server was started with (synaxis.app.units), recorded "
                             "and checked against the largest interest set seen; 0 skips the check")
    player.add_argument("--map-size", type=float, default=512.0,
                        help="side of the map preset in blocks, the range the camera anchors wander over")
    player.add_argument("--camera-hz", type=float, default=5.0, help="C2S_CAMERA reports per connection")
    player.add_argument("--order-hz", type=float, default=1.0, help="C2S_MOVE_ORDER per connection, 0 for none")
    player.add_argument("--select-interval", type=float, default=10.0,
                        help="seconds between two C2S_SELECT of one connection, 0 for none")
    player.add_argument("--pan-speed", type=float, default=20.0, help="blocks per second the anchor moves at")
    player.add_argument("--zoom", type=float, default=24.0, help="the zoom every camera report carries")
    player.add_argument("--login-workers", type=int, default=8)
    player.add_argument("--sample-interval", type=float, default=10.0)
    player.add_argument("--gc-probe-interval", type=float, default=60.0,
                        help="seconds between forced full GCs, 0 disables the live set series")
    player.add_argument("--settle", type=float, default=15.0,
                        help="seconds to wait after the last disconnect before the final sample")
    player.add_argument("--server-log", default="",
                        help="the file the server's stdout is redirected to; WARN / ERROR / kick lines "
                             "written during the run are counted")
    player.add_argument("--events-dir", default="logs",
                        help="the event ledger directory of the server (relative to its working directory)")
    player.add_argument("--pid", type=int, default=0, help="server pid, found by pgrep when unset")
    player.add_argument("--no-jfr", action="store_true")
    player.add_argument("--out", required=True)
    player.set_defaults(run=play)

    ticks = subparsers.add_parser("ticks", help="summarise ServerTickTime out of a JFR recording")
    ticks.add_argument("--jfr", required=True)
    ticks.set_defaults(run=lambda args: print(json.dumps(tick_summary(Path(args.jfr)), indent=2)) or 0)

    return parser


def main() -> int:
    args = build_parser().parse_args()
    return args.run(args)


if __name__ == "__main__":
    sys.exit(main())
