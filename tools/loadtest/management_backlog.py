#!/usr/bin/env python3
"""A management client that never reads, for the outbound bound of ``docs/DESIGN-PROD.md``
``API-PROD-022`` (``docs/TEST.md`` ``TC-PROD-494``). Standard library only.

It upgrades with ``Authorization: Bearer $SYNAXIS_MANAGEMENT_TOKEN`` on a socket whose receive
buffer is 4 KiB, reads the 101 answer and nothing after it, then writes ``--megabytes`` of frames:
pings with 125 B of payload (``--mode ping``, each answered with a pong) or
``minecraft:server/status`` requests (``--mode status``, each answered with a reply). Every 20 s it
also writes an unsolicited pong, which keeps Java-WebSocket's lost connection timer from closing
the connection, so what the server holds for it stays held. After the flood it keeps the socket
open for ``--hold`` seconds. Prints one JSON line: the frames and bytes it wrote, whether the
server closed the connection and after how many frames.

    SYNAXIS_MANAGEMENT_TOKEN=... .venv/bin/python tools/loadtest/management_backlog.py --port 25612 \\
        --mode ping --megabytes 200 --hold 30
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import struct
import time


def frame(opcode: int, payload: bytes) -> bytes:
    mask = os.urandom(4)
    head = bytes([0x80 | opcode])
    if len(payload) < 126:
        head += bytes([0x80 | len(payload)])
    else:
        head += bytes([0x80 | 126]) + struct.pack(">H", len(payload))
    return head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--mode", choices=["ping", "status"], default="ping")
    parser.add_argument("--megabytes", type=float, required=True)
    parser.add_argument("--hold", type=float, default=0)
    args = parser.parse_args()

    sock = socket.socket()
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
    sock.connect((args.host, args.port))
    key = base64.b64encode(os.urandom(16)).decode()
    sock.sendall((f"GET / HTTP/1.1\r\nHost: {args.host}:{args.port}\r\nUpgrade: websocket\r\n"
                  f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                  f"Authorization: Bearer {os.environ['SYNAXIS_MANAGEMENT_TOKEN']}\r\n\r\n").encode())
    answer = b""
    while b"\r\n\r\n" not in answer:
        answer += sock.recv(1)

    if args.mode == "ping":
        one = frame(0x9, b"p" * 125)
    else:
        one = frame(0x1, b'{"jsonrpc":"2.0","id":1,"method":"minecraft:server/status"}')
    batch = one * 64
    pong = frame(0xA, b"")
    target = int(args.megabytes * 1024 * 1024)
    written = 0
    closed_after = None
    last_pong = start = time.monotonic()
    try:
        while written < target:
            sock.sendall(batch)
            written += len(batch)
            if time.monotonic() - last_pong > 20:
                sock.sendall(pong)
                last_pong = time.monotonic()
        end = time.monotonic() + args.hold
        while time.monotonic() < end:
            time.sleep(min(20.0, max(0.0, end - time.monotonic())))
            sock.sendall(pong)
    except OSError:
        closed_after = written // len(one)
    print(json.dumps({
        "handshake": answer.split(b"\r\n")[0].decode(),
        "mode": args.mode,
        "frames_written": written // len(one),
        "bytes_written": written,
        "seconds": round(time.monotonic() - start, 1),
        "closed_by_server": closed_after is not None,
        "closed_after_frames": closed_after,
    }))
    sock.close()


if __name__ == "__main__":
    main()
