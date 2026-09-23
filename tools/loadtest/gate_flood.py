#!/usr/bin/env python3
"""One address opening and closing connections at a fixed rate, for the connection gate row of
``docs/DESIGN-PROD.md`` §15 (``docs/TEST.md`` ``TC-PROD-069``). Standard library only.

Each connection sends a login handshake and a login start (``--idle``: nothing at all), keeps the
socket for ``--hold`` seconds reading whatever the server answers, and closes it. The login is never
acknowledged, so a connection the gate lets through never reaches the configuration state. Prints
one JSON line: how many connections were opened, how each one ended (closed without a byte, a
login disconnect with its translate key, or the first packet of a login that went through), and
how many the server closed within 1 s and after 4 s.

    python3 tools/loadtest/gate_flood.py --host 192.168.1.22 --port 25621 --rate 100 --duration 60
    python3 tools/loadtest/gate_flood.py --host 192.168.1.22 --port 25621 --idle --rate 200 \\
        --duration 5.12 --hold 10

``--host`` has to be an address of this machine that the server does not trust, so that the flood
comes from another address than bots connecting over 127.0.0.1.
"""

from __future__ import annotations

import argparse
import json
import socket
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "protocol-probe"))

import probe  # noqa: E402  the path above is what makes it importable


def frame(state: str, packet: str, payload: bytes) -> bytes:
    body = probe.Writer().varint(probe.client_id(state, packet)).raw(payload).bytes()
    return probe.Writer().byte_array(body).bytes()


def classify(data: bytes) -> str:
    if not data:
        return "closed_without_a_byte"
    reader = probe.Reader(data)
    length = reader.varint()
    start = reader.stream.tell()
    body = probe.Reader(data[start:start + length])
    name = probe.server_name(probe.LOGIN, body.varint())
    if name == "LoginDisconnectPacket":
        text = body.string()
        try:
            return "disconnect:" + json.loads(text).get("translate", text)
        except ValueError:
            return "disconnect:" + text
    return "answered:" + name


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--rate", type=float, default=100.0, help="connections opened per second")
    parser.add_argument("--duration", type=float, default=60.0, help="seconds of opening connections")
    parser.add_argument("--hold", type=float, default=0.5, help="seconds each connection is kept")
    parser.add_argument("--idle", action="store_true", help="send nothing at all")
    args = parser.parse_args()

    login = frame(probe.HANDSHAKE, "ClientHandshakePacket",
                  probe.handshake_payload(args.host, args.port, probe.PROTOCOL_VERSION))
    outcomes: dict[str, int] = {}
    opened = connect_failed = 0
    live: list[tuple[float, float, socket.socket, bytearray]] = []
    closed_after: list[float] = []
    start = time.monotonic()
    next_open = start
    while True:
        now = time.monotonic()
        if now - start >= args.duration and not live:
            break
        if now - start < args.duration and now >= next_open:
            next_open += 1.0 / args.rate
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(0.2)
            try:
                sock.connect((args.host, args.port))
                if not args.idle:
                    name = "flood" + uuid.uuid4().hex[:8]
                    sock.sendall(login + frame(probe.LOGIN, "ClientLoginStartPacket",
                                               probe.login_start_payload(name)))
                sock.setblocking(False)
                live.append((now, now + args.hold, sock, bytearray()))
                opened += 1
            except OSError:
                connect_failed += 1
                sock.close()
        remaining = []
        for opened_at, deadline, sock, data in live:
            try:
                chunk = sock.recv(65536)
                if chunk:
                    data += chunk
                else:
                    deadline = 0.0  # closed by the server
                    closed_after.append(time.monotonic() - opened_at)
            except BlockingIOError:
                pass
            except OSError:
                deadline = 0.0
                closed_after.append(time.monotonic() - opened_at)
            if time.monotonic() >= deadline:
                key = classify(bytes(data))
                outcomes[key] = outcomes.get(key, 0) + 1
                sock.close()
            else:
                remaining.append((opened_at, deadline, sock, data))
        live = remaining
        time.sleep(0.002)
    print(json.dumps({
        "opened": opened,
        "connect_failed": connect_failed,
        "outcomes": outcomes,
        "closed_by_server": len(closed_after),
        "closed_within_1s": sum(1 for seconds in closed_after if seconds < 1),
        "closed_after_4s": sum(1 for seconds in closed_after if seconds >= 4),
    }, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
