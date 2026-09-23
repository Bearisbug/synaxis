#!/usr/bin/env python3
"""Minimal Minecraft 26.2 (protocol 776) client used to smoke test synaxis.

Standard library only, no third party packages. It speaks no encryption, so the server it talks to
runs in offline mode: ``server-app`` with ``-Dsynaxis.app.auth=offline`` (the default is online).
The encrypted login is covered by the engine's Java tests and ``tools/loadtest/PacketFlood.java``.

Packet ids are never hardcoded: they are the index of the packet class inside the
registry lists of ``src/main/java/io/github/bearisbug/synaxis/network/packet/PacketVanilla.java``
(see ``PacketRegistryImpl``), and data component ids are the declaration order of
``src/main/java/io/github/bearisbug/synaxis/component/DataComponents.java``. Both files are parsed
at start-up, so the probe follows the repository instead of a remembered protocol dump.

Subcommands, see docs/TEST.md section 3:

    login [--hold N]              log in and stay in the play state for N seconds
    premature-finish              acknowledge configuration before the server finished it
    bad-version --protocol N      handshake with an unsupported protocol version
    zero-frame                    send a single 0x00 byte while in the play state
    alloc-bomb [--declared N]     edit book packet declaring N pages but sending ten bytes
    nbt-bomb [--mib N]            custom click action whose NBT decodes to about N MiB of objects
    deep-recursion [--depth N]    creative set slot packet with N nested potion effects; a
                                  non-creative player has the packet dropped before decoding
    echo --payload TEXT           round trip TEXT over the synaxis:main custom payload channel
    record-registry --out FILE    dump every received RegistryData packet to FILE
    order [--count N] [--group G] send move orders on rts:game and report the result codes
    select --units 1,2,3          send one C2S_SELECT and check it is not answered
    march [--units N]             keep N units walking, so a load test has moving units under it
    game-flood [--rate R]         hold a payload rate on rts:game for a few seconds
    handshake                     log in and report the S2C_HELLO the server answered with
    no-hello                      log in and never send C2S_HELLO, the client without the mod
    bad-schema --schema N         send C2S_HELLO announcing a schema_version of N
    duplicate-hello               send a second C2S_HELLO on a connection that already handshook

Every subcommand that reaches the configuration phase sends C2S_HELLO on synaxis:handshake first
(REQ-M2-008), which is what keeps the pre-handshake subcommands working against a server that
demands the client mod. The schema_version is read out of ProtocolSchema.java, like the packet ids.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import socket
import struct
import sys
import time
import zlib
from pathlib import Path

PROTOCOL_VERSION = 776
# Approximate decoded cost of one empty compound inside a list, see BinaryTagTypeImpl.ReadLimiter
COST_PER_EMPTY_COMPOUND = 52

REPO_ROOT = Path(__file__).resolve().parents[2]
PACKET_VANILLA = REPO_ROOT / "src/main/java/io/github/bearisbug/synaxis/network/packet/PacketVanilla.java"
DATA_COMPONENTS = REPO_ROOT / "src/main/java/io/github/bearisbug/synaxis/component/DataComponents.java"
PROTOCOL_SCHEMA = REPO_ROOT / "protocol/src/main/java/io/github/bearisbug/synaxis/game/protocol/ProtocolSchema.java"

# The host reserved handshake channel and its two message ids, see docs/DESIGN-M2.md API-M2-002
HANDSHAKE_CHANNEL = "synaxis:handshake"
C2S_HELLO = 0x01
S2C_HELLO = 0x81

# The RTS slice channel and the message ids of API-M2-003 to API-M2-005. msg_id is scoped to one
# channel, which is why these may collide with the handshake's.
GAME_CHANNEL = "rts:game"
C2S_CAMERA = 0x01
C2S_SELECT = 0x02
C2S_MOVE_ORDER = 0x03
S2C_UNIT_SPAWN = 0x81
S2C_UNIT_STATE = 0x82
S2C_UNIT_DESPAWN = 0x83
S2C_ORDER_RESULT = 0x84
# docs/DESIGN-M2.md section 9 order_result_code, in the order it is encoded in
ORDER_RESULT_CODES = ("ok", "stale", "rate-limited", "not-owner", "out-of-bounds", "busy", "no-path")
# docs/DESIGN-M2.md section 9 unit_state, in the order it is encoded in; removed is never sent
UNIT_STATES = ("idle", "moving")


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


def _load_schema_version() -> int:
    """Reads the wire schema version out of the protocol module, not out of a remembered number."""
    match = re.search(r"SCHEMA_VERSION\s*=\s*(\d+)", PROTOCOL_SCHEMA.read_text(encoding="utf-8"))
    if match is None:
        raise SystemExit(f"cannot find SCHEMA_VERSION in {PROTOCOL_SCHEMA}")
    return int(match.group(1))


REGISTRIES = _load_registries()
COMPONENT_IDS = _load_component_ids()
SCHEMA_VERSION = _load_schema_version()

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

    def float32(self, value: float) -> "Writer":
        self.buffer += struct.pack(">f", value)
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


def hello_body(mod_version: int, schema_version: int) -> bytes:
    """C2S_HELLO{msg_id=0x01, mod_version, schema_version}, see docs/DESIGN-M2.md API-M2-002."""
    return Writer().varint(C2S_HELLO).varint(mod_version).varint(schema_version).bytes()


def camera_body(anchor_x: float, anchor_z: float, zoom: float, camera_yaw: float) -> bytes:
    """C2S_CAMERA{msg_id=0x01, anchor_x, anchor_z, zoom, camera_yaw}, see API-M2-003."""
    return (Writer().varint(C2S_CAMERA).float32(anchor_x).float32(anchor_z)
            .float32(zoom).float32(camera_yaw).bytes())


def select_body(unit_ids: list[int]) -> bytes:
    """C2S_SELECT{msg_id=0x02, count, unit_ids}, see API-M2-004. The count is written as given, so
    a caller may go past the cap of eight on purpose."""
    writer = Writer().varint(C2S_SELECT).varint(len(unit_ids))
    for unit_id in unit_ids:
        writer.varint(unit_id)
    return writer.bytes()


def move_order_body(order_seq: int, order_group: int, unit_id: int,
                    target: tuple[float, float, float], mods: int = 0) -> bytes:
    """C2S_MOVE_ORDER{msg_id=0x03, order_seq, order_group, unit_id, target, mods}, see API-M2-005."""
    writer = Writer().varint(C2S_MOVE_ORDER).varint(order_seq).varint(order_group).varint(unit_id)
    for coordinate in target:
        writer.float32(coordinate)
    return writer.raw(bytes([mods])).bytes()


def read_order_result(data: bytes) -> dict | None:
    """Decodes S2C_ORDER_RESULT{msg_id=0x84, order_seq, code:u8, snapped_x, snapped_y, snapped_z}."""
    reader = Reader(data)
    if reader.varint() != S2C_ORDER_RESULT:
        return None
    order_seq = reader.varint()
    code = reader.u8()
    return {"order_seq": order_seq,
            "code": ORDER_RESULT_CODES[code] if code < len(ORDER_RESULT_CODES) else f"unknown({code})",
            "snapped": (reader.float32(), reader.float32(), reader.float32())}


def read_unit_message(data: bytes) -> dict | None:
    """Decodes the three downlink unit messages of API-M2-006, which carry no position at all.

    Position and facing travel on the engine's own entity packets and nowhere else, so a unit's
    place on the map is never in here; the client binds unit_id to entity_id with the spawn message
    and reads the position off that entity.
    """
    reader = Reader(data)
    msg_id = reader.varint()
    if msg_id == S2C_UNIT_SPAWN:
        return {"message": "S2C_UNIT_SPAWN", "unit_id": reader.varint(), "entity_id": reader.varint(),
                "archetype": reader.varint(), "team": reader.u8(), "hp": reader.varint(),
                "max_hp": reader.varint(), "flags": reader.u8()}
    if msg_id == S2C_UNIT_STATE:
        server_tick = reader.varint()
        entries = []
        for _ in range(reader.varint()):
            unit_id = reader.varint()
            state = reader.u8()
            entries.append({"unit_id": unit_id,
                            "unit_state": UNIT_STATES[state] if state < len(UNIT_STATES) else f"unknown({state})",
                            "hp": reader.varint()})
        return {"message": "S2C_UNIT_STATE", "server_tick": server_tick, "entries": entries}
    if msg_id == S2C_UNIT_DESPAWN:
        return {"message": "S2C_UNIT_DESPAWN", "unit_ids": [reader.varint() for _ in range(reader.varint())]}
    return None


def login_plugin_response_payload(message_id: int, data: bytes | None) -> bytes:
    """The login phase answer; a client that does not know the channel sends no data at all."""
    writer = Writer().varint(message_id).boolean(data is not None)
    return writer.raw(data).bytes() if data is not None else writer.bytes()


def read_server_hello(data: bytes) -> dict[str, int] | None:
    """Decodes S2C_HELLO{msg_id=0x81, schema_version, tick_rate:u8, server_tick}."""
    reader = Reader(data)
    if reader.varint() != S2C_HELLO:
        return None
    return {"schema_version": reader.varint(), "tick_rate": reader.u8(), "server_tick": reader.varint()}


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


def nbt_bomb_payload(mib: float) -> bytes:
    """A custom click action whose binary tag decodes to roughly ``mib`` MiB of objects.

    One list entry holding an empty compound is a single encoded byte and about 52 bytes of
    decoded objects, so the encoded payload stays inside the packet's own 64 KiB length prefix
    while the decoded estimate goes well past the server's limit.
    """
    entries = int(mib * 1024 * 1024 / COST_PER_EMPTY_COMPOUND)
    nbt = (Writer()
           .raw(b"\x09")  # TAG_List
           .raw(b"\x0a")  # of TAG_Compound
           .raw(struct.pack(">i", entries))
           .raw(b"\x00" * entries)  # every compound is a lone TAG_End
           .bytes())
    return Writer().string("game:probe").byte_array(nbt).bytes()


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
        # The handshake this session performs. Every subcommand sends one by default, which is what
        # keeps the pre-REQ-M2-008 subcommands able to reach the play state (API-M2-002).
        self.hello = True
        self.hello_mod_version = 1
        self.hello_schema_version = SCHEMA_VERSION
        self.server_hello: dict[str, int] | None = None
        # Every S2C_ORDER_RESULT this session has been answered with, in arrival order
        self.order_results: list[dict] = []
        # The interest set as the server describes it: what was spawned into it, what the last
        # snapshot said each unit is doing, and how many snapshots have arrived (API-M2-006)
        self.units: dict[int, dict] = {}
        self.unit_states: dict[int, str] = {}
        self.snapshots = 0
        self.despawned: list[int] = []
        # Set once this session has handshaken, so that the login phase form of it is not followed
        # by a second C2S_HELLO in the configuration phase (which is a protocol error).
        self.handshook = False

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
            elif packet == "LoginPluginRequestPacket":
                self.answer_login_plugin(reader)
            elif packet == "EncryptionRequestPacket":
                raise SystemExit("EncryptionRequest received: the probe needs an offline server, "
                                 "start server-app with -Dsynaxis.app.auth=offline")
            elif packet == "LoginSuccessPacket":
                player_uuid = reader.uuid()
                username = reader.string()
                self.log(f"     LoginSuccess username={username} uuid={player_uuid}")
                self.connection.send("ClientLoginAcknowledgedPacket", b"")
                self.connection.state = CONFIGURATION
                self.log("sent LoginAcknowledged")
                return

    def send_hello(self) -> None:
        payload = hello_body(self.hello_mod_version, self.hello_schema_version)
        self.connection.send("ClientPluginMessagePacket",
                             plugin_message_payload(HANDSHAKE_CHANNEL, payload))
        self.log(f"sent C2S_HELLO mod_version={self.hello_mod_version} "
                 f"schema_version={self.hello_schema_version}")

    def send_game(self, body: bytes) -> None:
        self.connection.send("ClientPluginMessagePacket", plugin_message_payload(GAME_CHANNEL, body))

    def read_payload(self, reader: Reader) -> bool:
        """Handles a custom payload on one of the two channels this probe knows.

        Returns whether it was one of them; the caller keeps the reader either way, so a payload on
        a third channel is simply not looked at.
        """
        channel = reader.string()
        data = reader.raw(reader.remaining())
        if channel == GAME_CHANNEL:
            result = read_order_result(data)
            if result is not None:
                self.order_results.append(result)
                self.log(f"     S2C_ORDER_RESULT order_seq={result['order_seq']} code={result['code']} "
                         f"snapped={result['snapped']}")
                return True
            unit = read_unit_message(data)
            if unit is None:
                self.log(f"     unexpected message on {channel}")
                return True
            self.read_unit(unit)
            return True
        if channel != HANDSHAKE_CHANNEL:
            return False
        answer = read_server_hello(data)
        if answer is None:
            self.log(f"     unexpected message on {channel}")
            return True
        self.server_hello = answer
        self.log(f"     S2C_HELLO schema_version={answer['schema_version']} "
                 f"tick_rate={answer['tick_rate']} server_tick={answer['server_tick']}")
        return True

    def read_unit(self, message: dict) -> None:
        """Keeps what the three unit messages say, which is this probe's view of its interest set."""
        if message["message"] == "S2C_UNIT_SPAWN":
            self.units[message["unit_id"]] = message
            self.log(f"     S2C_UNIT_SPAWN unit_id={message['unit_id']} entity_id={message['entity_id']} "
                     f"archetype={message['archetype']} team={message['team']} flags={message['flags']}")
        elif message["message"] == "S2C_UNIT_STATE":
            self.snapshots += 1
            for entry in message["entries"]:
                self.unit_states[entry["unit_id"]] = entry["unit_state"]
            moving = [entry["unit_id"] for entry in message["entries"] if entry["unit_state"] == "moving"]
            self.log(f"     S2C_UNIT_STATE server_tick={message['server_tick']} "
                     f"count={len(message['entries'])} moving={moving}")
        else:
            self.despawned.extend(message["unit_ids"])
            for unit_id in message["unit_ids"]:
                self.units.pop(unit_id, None)
                self.unit_states.pop(unit_id, None)
            self.log(f"     S2C_UNIT_DESPAWN unit_ids={message['unit_ids']}")

    def configuration(self) -> None:
        """Answers the configuration phase until the server finishes it."""
        if self.hello and not self.handshook:
            # Sent first thing in the phase: the server starts its bounded wait when the connection
            # gets here, and answers on the same channel (API-M2-011).
            self.send_hello()
        while True:
            packet, reader, body = self.recv()
            if packet == "PluginMessagePacket":
                self.read_payload(reader)
            elif packet == "SelectKnownPacksPacket":
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

    def answer_login_plugin(self, reader: Reader) -> None:
        """Answers the login phase form of the handshake (the fallback of ADR-M2-010).

        A client that does not know the channel answers with no data at all, which is exactly what
        this does when the session is set up to send no hello.
        """
        message_id = reader.varint()
        channel = reader.string()
        request = reader.raw(reader.remaining())
        known = channel == HANDSHAKE_CHANNEL and self.hello
        if channel == HANDSHAKE_CHANNEL:
            # The server asks first in this phase, so its S2C_HELLO is the request payload.
            answer = read_server_hello(request)
            if answer is not None:
                self.server_hello = answer
                self.log(f"     S2C_HELLO schema_version={answer['schema_version']} "
                         f"tick_rate={answer['tick_rate']} server_tick={answer['server_tick']}")
        data = hello_body(self.hello_mod_version, self.hello_schema_version) if known else None
        self.connection.send("ClientLoginPluginResponsePacket",
                             login_plugin_response_payload(message_id, data))
        self.handshook = self.handshook or known
        self.log(f"sent LoginPluginResponse channel={channel} understood={known}")

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
        self.pump_until(lambda: False, seconds)

    def drain(self) -> None:
        """Reads whatever has already arrived without waiting for more.

        The flood subcommand sends far faster than it reads, and a socket buffer that fills up
        blocks the server's writer rather than this probe; draining between bursts keeps the rate
        the server sees equal to the rate that was asked for.
        """
        self.connection.socket.settimeout(0.01)
        try:
            while True:
                try:
                    packet, reader, _ = self.recv()
                except socket.timeout:
                    return
                if packet == "KeepAlivePacket":
                    self.answer_keep_alive(reader)
                elif packet == "PluginMessagePacket":
                    self.read_payload(reader)
        finally:
            self.connection.socket.settimeout(self.args.timeout)

    def pump_until(self, ready, seconds: float) -> bool:
        """Reads packets until ``ready()`` holds or the time is up, answering keep alives."""
        deadline = time.monotonic() + seconds
        self.connection.socket.settimeout(0.5)
        try:
            while not ready() and time.monotonic() < deadline:
                try:
                    packet, reader, _ = self.recv()
                except socket.timeout:
                    continue
                if packet == "KeepAlivePacket":
                    self.answer_keep_alive(reader)
                elif packet == "PluginMessagePacket":
                    self.read_payload(reader)
            return ready()
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
            return 0 if disconnected.reason == "synaxis:protocol-error" else 1
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
            if disconnected.packet == "LoginDisconnect" and disconnected.reason == "synaxis:unsupported-version":
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
            return 0 if disconnected.reason == "synaxis:protocol-error" else 1
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
            return 0 if disconnected.reason == "synaxis:protocol-error" else 1
        except ClosedByServer:
            session.log("connection closed by the server")
            return 0
        session.log("no disconnect received")
        return 1
    finally:
        session.close()


def cmd_nbt_bomb(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play()
        payload = nbt_bomb_payload(args.mib)
        session.connection.send("ClientCustomClickActionPacket", payload)
        session.log(f"sent CustomClickAction with NBT decoding to about {args.mib} MiB "
                    f"({len(payload)} encoded bytes)")
        try:
            session.pump(args.timeout)
        except Disconnected as disconnected:
            return 0 if disconnected.reason == "synaxis:protocol-error" else 1
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
            return 0 if disconnected.reason == "synaxis:protocol-error" else 1
        except ClosedByServer:
            session.log("connection closed by the server")
            return 0
        # The probe logs in as a survival player, so the server drops the frame without decoding it.
        # Surviving is the expected outcome; what would fail here is a crash or a stalled connection.
        session.log("no disconnect received, the creative packet was skipped before decoding")
        return 0
    finally:
        session.close()


# API-M2-005: how many order packets one order_group may carry.
GROUP = 8


def cmd_order(args: argparse.Namespace) -> int:
    """Sends move orders and reports the result codes they came back with (API-M2-005).

    Every request is answered, so the check is twofold: as many answers as requests, and the
    histogram the caller expected. With `--group` the whole burst shares one order_group, which is
    what a single click on several units looks like and what the token bucket counts as one;
    `--group-size` cuts the burst into groups of that size instead, which is how a burst larger
    than the eight packets a group may carry is sent without being rate limited.

    `--select` puts a selection in front of the orders on the same connection, which is the only
    way to see that a selection carrying a unit the sender does not command still leaves it able to
    order the ones it does.
    """
    if args.group_size < 1:
        raise SystemExit("--group-size counts the orders sharing a group, so it is at least 1")
    session = Session(args)
    try:
        session.enter_play()
        if args.select:
            units = [int(unit) for unit in args.select.split(",")]
            session.send_game(select_body(units))
            session.log(f"sent C2S_SELECT count={len(units)} unit_ids={units}")
        target = (args.x, args.y, args.z)
        for index in range(args.count):
            order_seq = args.seq + index
            order_group = args.group if args.group is not None else args.seq + index // args.group_size
            session.send_game(move_order_body(order_seq, order_group, args.unit, target))
            session.log(f"sent C2S_MOVE_ORDER order_seq={order_seq} "
                        f"order_group={order_group} unit_id={args.unit}")
        session.pump_until(lambda: len(session.order_results) >= args.count, args.wait)
        histogram: dict[str, int] = {}
        for result in session.order_results:
            histogram[result["code"]] = histogram.get(result["code"], 0) + 1
        session.log(f"answers={len(session.order_results)}/{args.count} "
                    + " ".join(f"{code}={count}" for code, count in sorted(histogram.items())))
        if len(session.order_results) != args.count:
            session.log("every request is answered exactly once, and this one was not")
            return 1
        if args.expect and histogram.get(args.expect, 0) != args.expect_count:
            session.log(f"expected {args.expect_count} answers of {args.expect}")
            return 1
        if args.watch <= 0:
            return 0
        # The walk itself, as the snapshots describe it: the unit goes moving and comes back idle
        # once it is there (section 11.4). Its position is not in here — that is on the entity
        # packets — so what is asserted is the state machine, not the coordinates.
        seen_moving = session.unit_states.get(args.unit) == "moving"

        def arrived() -> bool:
            nonlocal seen_moving
            state = session.unit_states.get(args.unit)
            seen_moving = seen_moving or state == "moving"
            return seen_moving and state == "idle"

        session.pump_until(arrived, args.watch)
        session.log(f"unit_id={args.unit} snapshots={session.snapshots} "
                    f"seen_moving={seen_moving} unit_state={session.unit_states.get(args.unit)}")
        if not seen_moving:
            session.log("the unit never reported itself moving")
            return 1
        if session.unit_states.get(args.unit) != "idle":
            session.log(f"the unit was still moving after {args.watch}s")
            return 1
        return 0
    finally:
        session.close()


def cmd_march(args: argparse.Namespace) -> int:
    """Keeps a number of units walking for a while, so that a load test has moving units under it.

    A walking unit puts an entity position packet on the wire every other tick for every player who
    can see it, which is downlink the core baselines were measured without. Producing it takes a
    connection that stays: the units of a connection that goes away are stopped (section 11.4). So
    this holds one, and every period it sends the whole set of units to the other of two targets,
    in groups of eight spaced far enough apart to stay under both the operation budget and the
    search queue.

    What it reports is the sampled count of units the snapshots called moving, which is the number
    the load test needs to stand behind: an order that was answered `ok` is not yet a unit walking.
    """
    session = Session(args)
    try:
        session.enter_play()
        targets = ((args.ax, args.az), (args.bx, args.bz))
        deadline = time.monotonic() + args.seconds
        samples: list[int] = []
        order_seq = 0
        period = 0
        while time.monotonic() < deadline:
            x, z = targets[period % 2]
            for first in range(1, args.units + 1, GROUP):
                for unit_id in range(first, min(first + GROUP, args.units + 1)):
                    order_seq += 1
                    session.send_game(move_order_body(order_seq, period * args.units + first,
                                                      unit_id, (x, args.y, z)))
                session.pump(args.spacing)
            until = min(time.monotonic() + args.period, deadline)
            while time.monotonic() < until:
                session.pump(1.0)
                samples.append(sum(1 for state in session.unit_states.values() if state == "moving"))
            period += 1
        histogram: dict[str, int] = {}
        for result in session.order_results:
            histogram[result["code"]] = histogram.get(result["code"], 0) + 1
        moving = sorted(samples)
        session.log(f"periods={period} orders={order_seq} "
                    + " ".join(f"{code}={count}" for code, count in sorted(histogram.items())))
        session.log(f"moving samples={len(moving)} min={moving[0]} median={moving[len(moving) // 2]} "
                    f"max={moving[-1]}")
        if moving[len(moving) // 2] < args.expect_moving:
            session.log(f"half the samples were under {args.expect_moving} units moving")
            return 1
        return 0
    finally:
        session.close()


def cmd_units(args: argparse.Namespace) -> int:
    """Reports the interest set the server describes over the three downlink unit messages.

    The interest set is the engine's viewer set projected onto units (API-M2-006), so what arrives
    here is whatever the server decided this connection can see from where its player stands.
    """
    session = Session(args)
    try:
        session.enter_play()
        session.pump_until(lambda: len(session.units) >= args.expect_min and session.snapshots > 0, args.wait)
        commandable = sorted(unit_id for unit_id, unit in session.units.items() if unit["flags"] & 0x01)
        session.log(f"units={len(session.units)} commandable={commandable} "
                    f"snapshots={session.snapshots} despawned={session.despawned}")
        session.log(f"states={dict(sorted(session.unit_states.items()))}")
        if len(session.units) < args.expect_min:
            session.log(f"expected at least {args.expect_min} units in the interest set")
            return 1
        if session.snapshots == 0:
            session.log("no snapshot arrived at all")
            return 1
        return 0
    finally:
        session.close()


def cmd_select(args: argparse.Namespace) -> int:
    """Sends one C2S_SELECT and checks what it costs (API-M2-004).

    A selection is never answered, so the outcome is either silence or, when the count is past the
    cap of eight, the protocol error of section 14.
    """
    session = Session(args)
    try:
        session.enter_play()
        units = [int(unit) for unit in args.units.split(",")] if args.units else []
        session.send_game(select_body(units))
        session.log(f"sent C2S_SELECT count={len(units)} unit_ids={units}")
        try:
            session.pump(args.wait)
        except Disconnected as disconnected:
            if args.expect == "protocol-error" and disconnected.reason == "synaxis:protocol-error":
                return 0
            session.log(f"unexpected disconnect: {disconnected.reason}")
            return 1
        except ClosedByServer:
            session.log("connection closed without a disconnect packet")
            return 1
        if args.expect == "protocol-error":
            session.log("a count past the cap was accepted")
            return 1
        if session.order_results:
            session.log("a selection was answered, which it must not be")
            return 1
        session.log("still connected, nothing answered")
        return 0
    finally:
        session.close()


def cmd_game_flood(args: argparse.Namespace) -> int:
    """Holds a rate on the game channel for a number of seconds (section 14, section 15).

    It is the difference between the two limits that this proves: a rate over the flood threshold
    ends the connection, and one under it never does however long it is held.

    The rate that is reached is reported next to the verdict, and it is not the rate that was
    asked for: reading between the bursts costs time, so a window may run past its second and the
    server, which counts in fixed one second buckets, then sees fewer payloads than were sent. A
    gate that never fired tells the reader nothing on its own — whether it is the gate or the probe
    that fell short is the slowest window's answer to give.
    """
    session = Session(args)
    try:
        session.enter_play()
        session.log(f"flooding {args.rate} payloads a second for {args.seconds}s")
        windows: list[float] = []
        try:
            for second in range(args.seconds):
                started = time.monotonic()
                for index in range(args.rate):
                    session.send_game(camera_body(8.0, 8.0, 24.0, 0.0))
                    if index % 32 == 0:
                        session.drain()
                left = 1.0 - (time.monotonic() - started)
                if left > 0:
                    session.pump(left)
                windows.append(args.rate / (time.monotonic() - started))
            session.pump(args.wait)
        except Disconnected as disconnected:
            if args.expect == "flood" and disconnected.reason == "synaxis:game-flood":
                return 0
            session.log(f"unexpected disconnect: {disconnected.reason}")
            return 1
        except ClosedByServer:
            session.log("connection closed without a disconnect packet")
            return 1
        session.log(f"asked for {args.rate} payloads a second, slowest window {min(windows):.0f}")
        if args.expect == "flood":
            session.log("the flood gate never fired")
            return 1
        session.log(f"still connected, keep_alives={session.keep_alives}")
        return 0
    finally:
        session.close()


def cmd_echo(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play()
        payload = args.payload.encode("utf-8")
        session.connection.send("ClientPluginMessagePacket", plugin_message_payload(args.channel, payload))
        session.log(f"sent CustomPayload {args.channel} bytes={len(payload)}")
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
                if channel != args.channel:
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


def cmd_slow_reader(args: argparse.Namespace) -> int:
    """Stops reading after entering play and reports whether the server let go of the socket.

    What fills the server's socket buffers is its own echo: ``--payloads`` incompressible payloads
    on the echo channel, each answered byte for byte and none of them read here, so the server's
    write thread ends up blocked inside a socket write. Only the core mode has that channel
    (API-M2-010 step 5). What ends the connection on the server is its keep alive, which this
    probe never answers because it never reads. Before the closer of PlayerSocketConnection a
    connection dropped that way kept its socket, both socket threads and everything queued for it
    for as long as this side kept the TCP connection up.

    Whether the socket was let go is decided without reading a byte, because reading would
    unblock the server and let it close on its own: two small frames a moment apart. A server that
    closed its side answers the first with a reset, and the second send fails.
    """
    session = Session(args)
    try:
        session.enter_play()
        noise = os.urandom(args.bytes)
        for _ in range(args.payloads):
            session.connection.send("ClientPluginMessagePacket", plugin_message_payload(args.channel, noise))
        session.log(f"sent {args.payloads} payloads of {args.bytes} bytes on {args.channel}, reading nothing back")
        session.log(f"holding the socket without reading for {args.wait}s")
        started = time.monotonic()
        time.sleep(args.wait)
        ping = Writer().varint(client_id(PLAY, "ClientPingRequestPacket")).long(0).bytes()
        frame = Writer().byte_array(Writer().varint(0).raw(ping).bytes()).bytes()
        try:
            session.connection.send_raw(frame)
            time.sleep(0.5)
            session.connection.send_raw(frame)
        except OSError as error:
            session.log(f"the server closed the socket within {time.monotonic() - started:.0f}s ({error})")
            return 0
        session.log(f"the server is still holding the socket after {args.wait}s: "
                    "its write thread is blocked on a peer that does not read")
        return 1
    finally:
        session.close()


def cmd_handshake(args: argparse.Namespace) -> int:
    session = Session(args)
    session.hello_mod_version = args.mod_version
    try:
        session.enter_play()
        # The answer may still be in flight: the server interprets a configuration phase payload on
        # its next tick, which can land after it has already finished the phase.
        session.pump_until(lambda: session.server_hello is not None, args.timeout)
        answer = session.server_hello
        if answer is None:
            session.log("no S2C_HELLO received")
            return 1
        if answer["schema_version"] != SCHEMA_VERSION:
            session.log(f"the server speaks schema_version={answer['schema_version']}, "
                        f"this repository speaks {SCHEMA_VERSION}")
            return 1
        session.log(f"handshake ok schema_version={answer['schema_version']} "
                    f"tick_rate={answer['tick_rate']} server_tick={answer['server_tick']}")
        return 0
    finally:
        session.close()


def _expect_disconnect(session: "Session", reason_key: str, seconds: float) -> int:
    """Drives the configuration phase expecting the server to refuse the handshake.

    Login is driven here as well: with the handshake in the login phase (the fallback of
    ADR-M2-010) the refusal is a LoginDisconnect that arrives before the phase ever starts. The
    refusal is also still awaited after the phase ends — a server that does not demand the mod (the
    core mode) finishes the phase, and one that does may interpret the offending payload on a tick
    that lands after it.
    """
    try:
        session.login()
        session.configuration()
        session.pump(seconds)
    except Disconnected as disconnected:
        return 0 if disconnected.reason == reason_key else 1
    except ClosedByServer:
        session.log("connection closed without a disconnect packet")
        return 1
    session.log(f"the server let the connection through instead of sending {reason_key}")
    return 1


def cmd_no_hello(args: argparse.Namespace) -> int:
    session = Session(args)
    session.hello = False
    try:
        session.log("staying silent, the client without the mod")
        return _expect_disconnect(session, "synaxis:client-mod-required", args.timeout)
    finally:
        session.close()


def cmd_bad_schema(args: argparse.Namespace) -> int:
    session = Session(args)
    session.hello_schema_version = args.schema
    try:
        return _expect_disconnect(session, "synaxis:protocol-version-mismatch", args.timeout)
    finally:
        session.close()


def cmd_duplicate_hello(args: argparse.Namespace) -> int:
    session = Session(args)
    try:
        session.enter_play()
        if not session.pump_until(lambda: session.server_hello is not None, args.timeout):
            session.log("no S2C_HELLO received, the first handshake did not go through")
            return 1
        session.send_hello()
        try:
            session.pump(args.timeout)
        except Disconnected as disconnected:
            return 0 if disconnected.reason == "synaxis:protocol-error" else 1
        except ClosedByServer:
            session.log("connection closed without a disconnect packet")
            return 1
        session.log("the server accepted a second C2S_HELLO")
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

    nbt = subparsers.add_parser("nbt-bomb", help="send NBT that decodes far past the server limit")
    nbt.add_argument("--mib", type=float, default=3.0,
                     help="approximate decoded size in MiB, capped near 3.2 by the packet length prefix")
    nbt.set_defaults(run=cmd_nbt_bomb)

    deep = subparsers.add_parser("deep-recursion", help="send a deeply nested item component")
    deep.add_argument("--depth", type=int, default=200)
    deep.set_defaults(run=cmd_deep_recursion)

    echo = subparsers.add_parser("echo", help="round trip a payload over a custom payload channel")
    echo.add_argument("--payload", required=True)
    echo.add_argument("--channel", default="synaxis:main",
                      help="the channel to send on and to expect the answer on (default: synaxis:main)")
    echo.set_defaults(run=cmd_echo)

    handshake = subparsers.add_parser("handshake", help="log in and report the S2C_HELLO answer")
    handshake.add_argument("--mod-version", type=int, default=1)
    handshake.set_defaults(run=cmd_handshake)

    no_hello = subparsers.add_parser("no-hello", help="log in without ever sending C2S_HELLO")
    no_hello.set_defaults(run=cmd_no_hello)

    bad_schema = subparsers.add_parser("bad-schema", help="send C2S_HELLO with a mismatched schema_version")
    bad_schema.add_argument("--schema", type=int, default=SCHEMA_VERSION + 1)
    bad_schema.set_defaults(run=cmd_bad_schema)

    duplicate = subparsers.add_parser("duplicate-hello", help="send a second C2S_HELLO on the same connection")
    duplicate.set_defaults(run=cmd_duplicate_hello)

    order = subparsers.add_parser("order", help="send move orders and report the result codes")
    order.add_argument("--count", type=int, default=1, help="how many orders to send")
    order.add_argument("--seq", type=int, default=1, help="the order_seq of the first one")
    order.add_argument("--group", type=int, default=None,
                       help="share one order_group, the shape of a single click on several units")
    order.add_argument("--group-size", type=int, default=1,
                       help="how many consecutive orders share an order_group (default: 1, one each)")
    order.add_argument("--unit", type=int, default=1, help="the unit_id to order about")
    order.add_argument("--select", default="",
                       help="comma separated unit_ids to select first, on the same connection")
    order.add_argument("--x", type=float, default=8.0)
    order.add_argument("--y", type=float, default=32.0)
    order.add_argument("--z", type=float, default=8.0)
    order.add_argument("--expect", default=None, help="the result code to count, see section 9")
    order.add_argument("--expect-count", type=int, default=0, help="how many answers must carry it")
    order.add_argument("--wait", type=float, default=5.0, help="seconds to wait for the answers")
    order.add_argument("--watch", type=float, default=0.0,
                       help="seconds to follow the unit in the snapshots until it is idle again")
    order.set_defaults(run=cmd_order)

    march = subparsers.add_parser("march", help="keep units walking for a while, under a load test")
    march.add_argument("--units", type=int, default=64, help="order unit_ids 1..N")
    march.add_argument("--seconds", type=float, default=300.0, help="how long to keep them walking")
    march.add_argument("--period", type=float, default=10.0, help="seconds between two rounds of orders")
    march.add_argument("--spacing", type=float, default=0.25,
                       help="seconds between two groups of orders, which is what keeps them inside "
                            "the operation budget and the search queue")
    march.add_argument("--ax", type=float, default=100.5)
    march.add_argument("--az", type=float, default=100.5)
    march.add_argument("--bx", type=float, default=40.5)
    march.add_argument("--bz", type=float, default=40.5)
    march.add_argument("--y", type=float, default=40.0)
    march.add_argument("--expect-moving", type=int, default=0,
                       help="how many units at least half the samples must report moving")
    march.set_defaults(run=cmd_march)

    units = subparsers.add_parser("units", help="report the units the server puts in this connection's view")
    units.add_argument("--wait", type=float, default=5.0, help="seconds to collect spawns and snapshots")
    units.add_argument("--expect-min", type=int, default=1, help="how many units must be in the view")
    units.set_defaults(run=cmd_units)

    select = subparsers.add_parser("select", help="send one C2S_SELECT")
    select.add_argument("--units", default="", help="comma separated unit_ids")
    select.add_argument("--expect", default="silence", choices=["silence", "protocol-error"])
    select.add_argument("--wait", type=float, default=2.0)
    select.set_defaults(run=cmd_select)

    flood = subparsers.add_parser("game-flood", help="hold a payload rate on the game channel")
    flood.add_argument("--rate", type=int, default=800, help="payloads a second")
    flood.add_argument("--seconds", type=int, default=3, help="how long to hold it")
    flood.add_argument("--expect", default="flood", choices=["flood", "alive"])
    flood.add_argument("--wait", type=float, default=2.0)
    flood.set_defaults(run=cmd_game_flood)

    slow = subparsers.add_parser("slow-reader",
                                 help="stop reading after login and see whether the server lets go of the socket")
    slow.add_argument("--payloads", type=int, default=256, help="echo payloads to send, none of them read back")
    slow.add_argument("--bytes", type=int, default=30000, help="size of each payload, incompressible")
    slow.add_argument("--channel", default="synaxis:main", help="the echo channel (core mode only)")
    slow.add_argument("--wait", type=float, default=30.0,
                      help="seconds to hold the socket without reading, past the keep alive kick")
    slow.set_defaults(run=cmd_slow_reader)

    record = subparsers.add_parser("record-registry", help="dump every RegistryData packet")
    record.add_argument("--out", required=True)
    record.set_defaults(run=cmd_record_registry)

    return parser


def main() -> int:
    args = build_parser().parse_args()
    print(f"probe target={args.host}:{args.port} protocol={PROTOCOL_VERSION} "
          f"schema_version={SCHEMA_VERSION} command={args.command}", flush=True)
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
