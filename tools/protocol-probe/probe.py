#!/usr/bin/env python3
"""Minimal Minecraft 26.2 (protocol 776) client used to smoke test game-engine.

Standard library only, no third party packages.

Packet ids are never hardcoded: they are the index of the packet class inside the
registry lists of ``src/main/java/dev/gameengine/network/packet/PacketVanilla.java``
(see ``PacketRegistryImpl``), and data component ids are the declaration order of
``src/main/java/dev/gameengine/component/DataComponents.java``. Both files are parsed
at start-up, so the probe follows the repository instead of a remembered protocol dump.

Subcommands, see docs/TEST.md section 3:

    login [--hold N]              log in and stay in the play state for N seconds
    premature-finish              acknowledge configuration before the server finished it
    bad-version --protocol N      handshake with an unsupported protocol version
    zero-frame                    send a single 0x00 byte while in the play state
    alloc-bomb [--declared N]     edit book packet declaring N pages but sending ten bytes
    deep-recursion [--depth N]    creative set slot packet with N nested potion effects
    echo --payload TEXT           round trip TEXT over the game:main custom payload channel
    record-registry --out FILE    dump every received RegistryData packet to FILE
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import socket
import struct
import sys
import time
import zlib
from pathlib import Path

PROTOCOL_VERSION = 776

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKET_VANILLA = REPO_ROOT / "src/main/java/dev/gameengine/network/packet/PacketVanilla.java"
DATA_COMPONENTS = REPO_ROOT / "src/main/java/dev/gameengine/component/DataComponents.java"


# --------------------------------------------------------------------------------------
# Packet ids derived from the repository
# --------------------------------------------------------------------------------------


def _registry_block(source: str, field: str) -> str:
    """Returns the argument list of the ``registry(...)`` call assigned to ``field``."""
    match = re.search(rf"\b{field}\s*=\s*(?:PacketRegistry\.<[^;]*?>)?registry\(", source)
    if match is None:
        raise SystemExit(f"cannot find packet registry {field} in {PACKET_VANILLA}")
    depth = 1
    index = match.end()
    while depth:
        char = source[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        index += 1
    return source[match.end():index - 1]


def _load_registries() -> dict[str, list[str]]:
    source = PACKET_VANILLA.read_text(encoding="utf-8")
    fields = ["CLIENT_HANDSHAKE", "CLIENT_STATUS", "CLIENT_LOGIN", "CLIENT_CONFIGURATION", "CLIENT_PLAY",
              "SERVER_STATUS", "SERVER_LOGIN", "SERVER_CONFIGURATION", "SERVER_PLAY"]
    registries = {}
    for field in fields:
        block = _registry_block(source, field)
        registries[field] = re.findall(r"entry\(\s*([A-Za-z0-9_]+)\.class", block)
    return registries


def _load_component_ids() -> dict[str, int]:
    source = DATA_COMPONENTS.read_text(encoding="utf-8")
    names = re.findall(r'=\s*register\(\s*"([^"]+)"', source)
    return {name: index for index, name in enumerate(names)}


REGISTRIES = _load_registries()
COMPONENT_IDS = _load_component_ids()

HANDSHAKE, STATUS, LOGIN, CONFIGURATION, PLAY = "HANDSHAKE", "STATUS", "LOGIN", "CONFIGURATION", "PLAY"
_CLIENT_REGISTRY = {HANDSHAKE: "CLIENT_HANDSHAKE", STATUS: "CLIENT_STATUS", LOGIN: "CLIENT_LOGIN",
                    CONFIGURATION: "CLIENT_CONFIGURATION", PLAY: "CLIENT_PLAY"}
_SERVER_REGISTRY = {STATUS: "SERVER_STATUS", LOGIN: "SERVER_LOGIN",
                    CONFIGURATION: "SERVER_CONFIGURATION", PLAY: "SERVER_PLAY"}


def client_id(state: str, packet: str) -> int:
    packets = REGISTRIES[_CLIENT_REGISTRY[state]]
    if packet not in packets:
        raise SystemExit(f"{packet} is not a client packet of {state}")
    return packets.index(packet)


def server_name(state: str, packet_id: int) -> str:
    packets = REGISTRIES[_SERVER_REGISTRY[state]]
    if packet_id >= len(packets):
        return f"Unknown(0x{packet_id:02x})"
    return packets[packet_id]


# --------------------------------------------------------------------------------------
# Buffer primitives
# --------------------------------------------------------------------------------------


class Writer:
    def __init__(self) -> None:
        self.buffer = bytearray()

    def raw(self, data: bytes) -> "Writer":
        self.buffer += data
        return self

    def varint(self, value: int) -> "Writer":
        value &= 0xFFFFFFFF
        while True:
            chunk = value & 0x7F
            value >>= 7
            if value:
                self.buffer.append(chunk | 0x80)
            else:
                self.buffer.append(chunk)
                return self

    def boolean(self, value: bool) -> "Writer":
        self.buffer.append(1 if value else 0)
        return self

    def short(self, value: int) -> "Writer":
        self.buffer += struct.pack(">h", value)
        return self

    def unsigned_short(self, value: int) -> "Writer":
        self.buffer += struct.pack(">H", value)
        return self

    def long(self, value: int) -> "Writer":
        self.buffer += struct.pack(">q", value)
        return self

    def string(self, value: str) -> "Writer":
        encoded = value.encode("utf-8")
        return self.varint(len(encoded)).raw(encoded)

    def byte_array(self, value: bytes) -> "Writer":
        return self.varint(len(value)).raw(value)

    def uuid(self, value: bytes) -> "Writer":
        return self.raw(value)

    def bytes(self) -> bytes:
        return bytes(self.buffer)


class Reader:
    def __init__(self, data: bytes) -> None:
        self.stream = io.BytesIO(data)
        self.length = len(data)

    def remaining(self) -> int:
        return self.length - self.stream.tell()

    def raw(self, count: int) -> bytes:
        data = self.stream.read(count)
        if len(data) != count:
            raise EOFError("truncated packet")
        return data

    def u8(self) -> int:
        return self.raw(1)[0]

    def varint(self) -> int:
        value = 0
        for shift in range(0, 35, 7):
            byte = self.u8()
            value |= (byte & 0x7F) << shift
            if not byte & 0x80:
                if value >= 1 << 31:
                    value -= 1 << 32
                return value
        raise ValueError("var-int is too long")

    def boolean(self) -> bool:
        return self.u8() != 0

    def short(self) -> int:
        return struct.unpack(">h", self.raw(2))[0]

    def int32(self) -> int:
        return struct.unpack(">i", self.raw(4))[0]

    def long(self) -> int:
        return struct.unpack(">q", self.raw(8))[0]

    def float32(self) -> float:
        return struct.unpack(">f", self.raw(4))[0]

    def double(self) -> float:
        return struct.unpack(">d", self.raw(8))[0]

    def string(self) -> str:
        return self.raw(self.varint()).decode("utf-8")

    def uuid(self) -> str:
        raw = self.raw(16).hex()
        return f"{raw[0:8]}-{raw[8:12]}-{raw[12:16]}-{raw[16:20]}-{raw[20:32]}"

    def nbt(self):
        """Reads a network NBT value (unnamed root), see BinaryTagTypeImpl."""
        tag = self.u8()
        if tag == 0:
            return None
        return self._nbt_payload(tag)

    def _nbt_payload(self, tag: int):
        if tag == 1:
            return struct.unpack(">b", self.raw(1))[0]
        if tag == 2:
            return self.short()
        if tag == 3:
            return self.int32()
        if tag == 4:
            return self.long()
        if tag == 5:
            return self.float32()
        if tag == 6:
            return self.double()
        if tag == 7:
            return self.raw(self.int32())
        if tag == 8:
            return self.raw(struct.unpack(">H", self.raw(2))[0]).decode("utf-8", "replace")
        if tag == 9:
            element, size = self.u8(), self.int32()
            return [self._nbt_payload(element) for _ in range(max(size, 0))]
        if tag == 10:
            compound = {}
            while True:
                entry = self.u8()
                if entry == 0:
                    return compound
                name = self.raw(struct.unpack(">H", self.raw(2))[0]).decode("utf-8", "replace")
                compound[name] = self._nbt_payload(entry)
        if tag == 11:
            return [self.int32() for _ in range(self.int32())]
        if tag == 12:
            return [self.long() for _ in range(self.int32())]
        raise ValueError(f"unknown nbt tag {tag}")


def offline_uuid(username: str) -> bytes:
    """The offline mode uuid a vanilla client sends, a name based md5 uuid."""
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return bytes(digest)


# --------------------------------------------------------------------------------------
# Connection
# --------------------------------------------------------------------------------------


class Disconnected(Exception):
    """The server sent a disconnect packet."""

    def __init__(self, packet: str, reason: str) -> None:
        super().__init__(f"{packet} reason={reason}")
        self.packet = packet
        self.reason = reason


class ClosedByServer(Exception):
    """The server closed the socket without a disconnect packet."""


class Connection:
    def __init__(self, host: str, port: int, timeout: float) -> None:
        self.host, self.port = host, port
        self.socket = socket.create_connection((host, port), timeout=timeout)
        self.socket.settimeout(timeout)
        self.incoming = bytearray()
        self.compression_threshold = -1
        self.state = HANDSHAKE

    def close(self) -> None:
        try:
            self.socket.close()
        except OSError:
            pass

    # -- writing ------------------------------------------------------------------

    def send_raw(self, data: bytes) -> None:
        self.socket.sendall(data)

    def send(self, packet: str, payload: bytes) -> None:
        body = Writer().varint(client_id(self.state, packet)).raw(payload).bytes()
        if self.compression_threshold < 0:
            frame = Writer().byte_array(body).bytes()
        elif len(body) >= self.compression_threshold:
            compressed = zlib.compress(body)
            data = Writer().varint(len(body)).raw(compressed).bytes()
            frame = Writer().byte_array(data).bytes()
        else:
            data = Writer().varint(0).raw(body).bytes()
            frame = Writer().byte_array(data).bytes()
        self.send_raw(frame)

    # -- reading ------------------------------------------------------------------

    def _take_frame(self) -> bytes | None:
        """Removes and returns the next complete frame, or None while it is incomplete.

        Nothing is consumed until the whole frame is buffered, so a socket timeout in the
        middle of a frame can simply be retried.
        """
        value, shift, index = 0, 0, 0
        while True:
            if index >= len(self.incoming):
                return None
            byte = self.incoming[index]
            index += 1
            value |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                break
            if shift > 35:
                raise ValueError("var-int is too long")
        if len(self.incoming) < index + value:
            return None
        frame = bytes(self.incoming[index:index + value])
        del self.incoming[:index + value]
        return frame

    def recv(self) -> tuple[str, Reader, bytes]:
        """Reads one packet, returns its name, a reader over its payload and its raw body."""
        while True:
            frame = self._take_frame()
            if frame is not None:
                break
            chunk = self.socket.recv(65536)
            if not chunk:
                raise ClosedByServer("the server closed the connection")
            self.incoming += chunk
        if self.compression_threshold >= 0:
            reader = Reader(frame)
            data_length = reader.varint()
            body = frame[reader.stream.tell():]
            if data_length:
                body = zlib.decompress(body)
                if len(body) != data_length:
                    raise ValueError(f"decompressed length mismatch: {len(body)} != {data_length}")
        else:
            body = frame
        reader = Reader(body)
        packet_id = reader.varint()
        return server_name(self.state, packet_id), reader, body


# --------------------------------------------------------------------------------------
# Packet payloads
# --------------------------------------------------------------------------------------


def handshake_payload(host: str, port: int, protocol: int) -> bytes:
    # protocolVersion, serverAddress, serverPort, intent (LOGIN = 2)
    return Writer().varint(protocol).string(host).unsigned_short(port).varint(2).bytes()


def login_start_payload(username: str) -> bytes:
    return Writer().string(username).uuid(offline_uuid(username)).bytes()


def known_packs_payload(entries: list[tuple[str, str, str]]) -> bytes:
    writer = Writer().varint(len(entries))
    for namespace, identifier, version in entries:
        writer.string(namespace).string(identifier).string(version)
    return writer.bytes()


def plugin_message_payload(channel: str, data: bytes) -> bytes:
    return Writer().string(channel).raw(data).bytes()


def nested_potion_settings(depth: int) -> bytes:
    """A CustomPotionEffect.Settings chain nested ``depth`` levels deep."""
    writer = Writer()
    for _ in range(depth):
        # amplifier, duration, isAmbient, showParticles, showIcon, then the optional hiddenEffect
        writer.varint(0).varint(0).boolean(False).boolean(False).boolean(False).boolean(True)
    writer.varint(0).varint(0).boolean(False).boolean(False).boolean(False).boolean(False)
    return writer.bytes()


def deep_recursion_payload(depth: int) -> bytes:
    """A creative set slot packet whose potion_contents component nests ``depth`` effects."""
    potion_contents = (Writer()
                       .boolean(False)  # potion
                       .boolean(False)  # customColor
                       .varint(1)  # customEffects
                       .varint(0)  # PotionEffect id
                       .raw(nested_potion_settings(depth))
                       .boolean(False)  # customName
                       .bytes())
    item = (Writer()
            .varint(1)  # amount
            .varint(1)  # material id, the frame never gets past the component patch
            .varint(1)  # added components
            .varint(0)  # removed components
            .varint(COMPONENT_IDS["potion_contents"])
            .byte_array(potion_contents)  # untrusted components are length prefixed
            .bytes())
    return Writer().short(36).raw(item).bytes()


def alloc_bomb_payload(declared: int) -> bytes:
    """An edit book packet that declares ``declared`` pages but sends almost no input."""
    return (Writer()
            .varint(0)  # slot
            .varint(declared)  # page count, far more than the input can back
            .raw(b"\x00" * 10)
            .bytes())


def disconnect_reason(packet: str, reader: Reader) -> str:
    """Extracts the translate key, LoginDisconnect uses json and Disconnect uses nbt."""
    if packet == "LoginDisconnectPacket":
        component = json.loads(reader.string())
    else:
        component = reader.nbt()
    if isinstance(component, dict) and "translate" in component:
        return str(component["translate"])
    return f"<no translate key: {component}>"


# --------------------------------------------------------------------------------------
# Session
# --------------------------------------------------------------------------------------


class Session:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.connection = Connection(args.host, args.port, args.timeout)
        self.registry_data: list[bytes] = []
        self.keep_alives = 0

    def close(self) -> None:
        self.connection.close()

    def log(self, message: str) -> None:
        print(message, flush=True)

    def recv(self) -> tuple[str, Reader, bytes]:
        packet, reader, body = self.connection.recv()
        if packet in ("DisconnectPacket", "LoginDisconnectPacket"):
            reason = disconnect_reason(packet, reader)
            name = "LoginDisconnect" if packet == "LoginDisconnectPacket" else "Disconnect"
            self.log(f"recv {name} reason={reason}")
            raise Disconnected(name, reason)
        self.log(f"recv {packet}")
        return packet, reader, body

    def handshake(self, protocol: int) -> None:
        self.connection.send("ClientHandshakePacket",
                             handshake_payload(self.args.host, self.args.port, protocol))
        self.connection.state = LOGIN
        self.log(f"sent Handshake protocol={protocol} intent=login")

    def login(self, protocol: int = PROTOCOL_VERSION) -> None:
        """Drives handshake and login up to LoginAcknowledged."""
        self.handshake(protocol)
        self.connection.send("ClientLoginStartPacket", login_start_payload(self.args.username))
        self.log(f"sent LoginStart username={self.args.username}")
        while True:
            packet, reader, _ = self.recv()
            if packet == "SetCompressionPacket":
                threshold = reader.varint()
                self.connection.compression_threshold = threshold
                self.log(f"     compression threshold={threshold}")
            elif packet == "EncryptionRequestPacket":
                raise SystemExit("EncryptionRequest received: the server is not in offline mode")
            elif packet == "LoginSuccessPacket":
                player_uuid = reader.uuid()
                username = reader.string()
                self.log(f"     LoginSuccess username={username} uuid={player_uuid}")
                self.connection.send("ClientLoginAcknowledgedPacket", b"")
                self.connection.state = CONFIGURATION
                self.log("sent LoginAcknowledged")
                return

    def configuration(self) -> None:
        """Answers the configuration phase until the server finishes it."""
        while True:
            packet, reader, body = self.recv()
            if packet == "SelectKnownPacksPacket":
                entries = [(reader.string(), reader.string(), reader.string())
                           for _ in range(reader.varint())]
                self.connection.send("ClientSelectKnownPacksPacket", known_packs_payload(entries))
                self.log(f"sent KnownPacks entries={len(entries)}")
            elif packet == "RegistryDataPacket":
                self.registry_data.append(body)
            elif packet == "KeepAlivePacket":
                self.answer_keep_alive(reader)
            elif packet == "FinishConfigurationPacket":
                self.connection.send("ClientFinishConfigurationPacket", b"")
                self.connection.state = PLAY
                self.log("sent FinishConfiguration")
                return

    def answer_keep_alive(self, reader: Reader) -> None:
        self.connection.send("ClientKeepAlivePacket", Writer().long(reader.long()).bytes())
        self.keep_alives += 1
        self.log(f"sent KeepAlive #{self.keep_alives} at {time.strftime('%H:%M:%S')}")

    def enter_play(self, protocol: int = PROTOCOL_VERSION) -> None:
        self.login(protocol)
        self.configuration()
        self.log("state=play")

    def pump(self, seconds: float) -> None:
        """Reads packets for the given duration, answering keep alives."""
        deadline = time.monotonic() + seconds
        self.connection.socket.settimeout(0.5)
        try:
            while time.monotonic() < deadline:
                try:
                    packet, reader, _ = self.recv()
                except socket.timeout:
                    continue
                if packet == "KeepAlivePacket":
                    self.answer_keep_alive(reader)
        finally:
            self.connection.socket.settimeout(self.args.timeout)


# --------------------------------------------------------------------------------------
# Subcommands
# --------------------------------------------------------------------------------------


def cmd_login(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play(args.protocol)
        if args.hold:
            session.log(f"holding the connection for {args.hold}s")
            session.pump(args.hold)
        session.log(f"keep_alives={session.keep_alives}")
        return 0
    finally:
        session.close()


def cmd_premature_finish(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.login()
        # The server is still waiting for the known packs answer, so it has not written
        # FinishConfiguration yet, see docs/DESIGN.md section 11.1.
        session.connection.send("ClientFinishConfigurationPacket", b"")
        # The probe stays in the configuration state on purpose: the server never wrote
        # FinishConfiguration, so it answers from its own configuration registry.
        session.log("sent FinishConfiguration (premature, server has not finished configuration)")
        try:
            session.pump(args.timeout)
        except Disconnected as disconnected:
            return 0 if disconnected.reason == "engine:protocol-error" else 1
        except ClosedByServer:
            session.log("connection closed without a disconnect packet")
            return 1
        session.log("no disconnect received")
        return 1
    finally:
        session.close()


def cmd_bad_version(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        try:
            session.handshake(args.protocol)
            session.pump(args.timeout)
        except Disconnected as disconnected:
            if disconnected.packet == "LoginDisconnect" and disconnected.reason == "engine:unsupported-version":
                return 0
            return 1
        except ClosedByServer:
            session.log("connection closed without a disconnect packet")
            return 1
        session.log("the server accepted an unsupported protocol version")
        return 1
    finally:
        session.close()


def cmd_zero_frame(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play()
        session.connection.send_raw(b"\x00")
        session.log("sent a zero length frame (single 0x00 byte)")
        try:
            session.pump(args.timeout)
        except Disconnected as disconnected:
            return 0 if disconnected.reason == "engine:protocol-error" else 1
        except ClosedByServer:
            session.log("connection closed without a disconnect packet")
            return 1
        session.log("no disconnect received")
        return 1
    finally:
        session.close()


def cmd_alloc_bomb(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play()
        session.connection.send("ClientEditBookPacket", alloc_bomb_payload(args.declared))
        session.log(f"sent EditBook declaring {args.declared} pages with 10 bytes of payload")
        try:
            session.pump(args.timeout)
        except Disconnected as disconnected:
            return 0 if disconnected.reason == "engine:protocol-error" else 1
        except ClosedByServer:
            session.log("connection closed by the server")
            return 0
        session.log("no disconnect received")
        return 1
    finally:
        session.close()


def cmd_deep_recursion(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play()
        session.connection.send("ClientCreativeInventoryActionPacket", deep_recursion_payload(args.depth))
        session.log(f"sent CreativeInventoryAction with {args.depth} nested CustomPotionEffect.Settings")
        try:
            session.pump(args.timeout)
        except Disconnected as disconnected:
            return 0 if disconnected.reason == "engine:protocol-error" else 1
        except ClosedByServer:
            session.log("connection closed by the server")
            return 0
        session.log("no disconnect received")
        return 1
    finally:
        session.close()


def cmd_echo(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play()
        payload = args.payload.encode("utf-8")
        session.connection.send("ClientPluginMessagePacket", plugin_message_payload("game:main", payload))
        session.log(f"sent CustomPayload game:main bytes={len(payload)}")
        deadline = time.monotonic() + args.timeout
        session.connection.socket.settimeout(0.5)
        while time.monotonic() < deadline:
            try:
                packet, reader, _ = session.recv()
            except socket.timeout:
                continue
            if packet == "KeepAlivePacket":
                session.answer_keep_alive(reader)
            elif packet == "PluginMessagePacket":
                channel = reader.string()
                data = reader.raw(reader.remaining())
                if channel != "game:main":
                    continue
                session.log(f"     echo channel={channel} payload={data.decode('utf-8', 'replace')}")
                if data == payload:
                    return 0
                session.log("the echoed payload does not match")
                return 1
        session.log("no echo received")
        return 1
    finally:
        session.close()


def cmd_record_registry(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play()
        Path(args.out).write_bytes(b"".join(session.registry_data))
        total = sum(len(packet) for packet in session.registry_data)
        session.log(f"registry_data_packets={len(session.registry_data)} bytes={total} out={args.out}")
        return 0 if session.registry_data else 1
    finally:
        session.close()


# --------------------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    parser.add_argument("--username", default="probe")
    parser.add_argument("--timeout", type=float, default=10.0, help="socket timeout in seconds")
    subparsers = parser.add_subparsers(dest="command", required=True)

    login = subparsers.add_parser("login", help="log in and enter the play state")
    login.add_argument("--hold", type=float, default=0.0, help="stay connected for N seconds")
    login.add_argument("--protocol", type=int, default=PROTOCOL_VERSION)
    login.set_defaults(run=cmd_login)

    premature = subparsers.add_parser("premature-finish", help="finish configuration before the server does")
    premature.set_defaults(run=cmd_premature_finish)

    bad_version = subparsers.add_parser("bad-version", help="handshake with an unsupported protocol version")
    bad_version.add_argument("--protocol", type=int, required=True)
    bad_version.set_defaults(run=cmd_bad_version)

    zero_frame = subparsers.add_parser("zero-frame", help="send a zero length frame in the play state")
    zero_frame.set_defaults(run=cmd_zero_frame)

    alloc = subparsers.add_parser("alloc-bomb", help="declare a huge collection length with almost no payload")
    alloc.add_argument("--declared", type=int, default=65535)
    alloc.set_defaults(run=cmd_alloc_bomb)

    deep = subparsers.add_parser("deep-recursion", help="send a deeply nested item component")
    deep.add_argument("--depth", type=int, default=200)
    deep.set_defaults(run=cmd_deep_recursion)

    echo = subparsers.add_parser("echo", help="round trip a payload over game:main")
    echo.add_argument("--payload", required=True)
    echo.set_defaults(run=cmd_echo)

    record = subparsers.add_parser("record-registry", help="dump every RegistryData packet")
    record.add_argument("--out", required=True)
    record.set_defaults(run=cmd_record_registry)

    return parser


def main() -> int:
    args = build_parser().parse_args()
    print(f"probe target={args.host}:{args.port} protocol={PROTOCOL_VERSION} command={args.command}", flush=True)
    try:
        code = args.run(args)
    except Disconnected as disconnected:
        print(f"unexpected disconnect: {disconnected}", flush=True)
        code = 1
    except ClosedByServer as closed:
        print(f"unexpected close: {closed}", flush=True)
        code = 1
    except (OSError, EOFError, ValueError) as error:
        print(f"probe error: {error}", flush=True)
        code = 2
    print(f"exit code: {code}", flush=True)
    return code


if __name__ == "__main__":
    sys.exit(main())
