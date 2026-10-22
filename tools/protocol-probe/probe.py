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
    compare-registry SAMPLE RECORDED [--ignore REGISTRY/ENTRY]
                                  compare two such dumps per registry and entry, no server needed
    handshake                     log in and report the S2C_HELLO the server answered with
    no-hello                      log in and never send C2S_HELLO, the client without the mod
    bad-schema --schema N         send C2S_HELLO announcing a schema_version of N
    duplicate-hello               send a second C2S_HELLO on a connection that already handshook
    view-distance [--from N] [--to N]
                                  log in at view distance N, lower it and check the chunks held

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
PROTOCOL_SCHEMA = REPO_ROOT / "protocol/src/main/java/io/github/bearisbug/synaxis/protocol/ProtocolSchema.java"

# The host reserved handshake channel and its two message ids, see docs/DESIGN-M2.md API-M2-002
HANDSHAKE_CHANNEL = "synaxis:handshake"
C2S_HELLO = 0x01
S2C_HELLO = 0x81



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


def client_settings_payload(view_distance: int) -> bytes:
    """ClientSettingsPacket with the values of ClientSettings.defaultSettings but the view distance."""
    return (Writer()
            .string("en_us")  # locale
            .raw(bytes([view_distance]))
            .varint(0)  # chat mode FULL
            .boolean(True)  # chat colors
            .raw(b"\x7f")  # every skin part
            .varint(1)  # main hand RIGHT
            .boolean(True)  # text filtering
            .boolean(True)  # server listings
            .varint(0)  # particles ALL
            .bytes())


def hello_body(mod_version: int, schema_version: int) -> bytes:
    """C2S_HELLO{msg_id=0x01, mod_version, schema_version}, see docs/DESIGN-M2.md API-M2-002."""
    return Writer().varint(C2S_HELLO).varint(mod_version).varint(schema_version).bytes()


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
        # Set once this session has handshaken, so that the login phase form of it is not followed
        # by a second C2S_HELLO in the configuration phase (which is a protocol error).
        self.handshook = False
        # The view distance this session announces in the configuration phase, none by default
        self.view_distance: int | None = None

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

    def read_payload(self, reader: Reader) -> bool:
        """Handles a custom payload on the handshake channel, the one channel this probe knows.

        Returns whether it was that channel; the caller keeps the reader either way, so a payload on
        another channel is simply not looked at.
        """
        channel = reader.string()
        data = reader.raw(reader.remaining())
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

    def configuration(self) -> None:
        """Answers the configuration phase until the server finishes it."""
        if self.hello and not self.handshook:
            # Sent first thing in the phase: the server starts its bounded wait when the connection
            # gets here, and answers on the same channel (API-M2-011).
            self.send_hello()
        if self.view_distance is not None:
            # A vanilla client sends its settings first thing in the phase; the first chunk ring
            # is built from them
            self.connection.send("ClientSettingsPacket", client_settings_payload(self.view_distance))
            self.log(f"sent ClientSettings view_distance={self.view_distance}")
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


def cmd_view_distance(args: argparse.Namespace) -> int:
    """Logs in at one view distance, lowers it and checks the chunks it is left holding (REQ-M2-014).

    The probe keeps the books a client keeps: a ChunkData adds its column, an UnloadChunk removes
    it, and the last UpdateViewPosition is the center. It lowers the view distance once
    ``--switch-after`` chunk batches have arrived, while most of the first ring is still queued on
    the server, then acknowledges every batch until no chunk packet has come for ``--settle``
    seconds. What it holds must then be the square of radius ``--to`` + 1 around the center: the
    engine sends one column past the client's view distance (Player#effectiveViewDistance), so
    ``--to`` has to be below the view distance of the server's instance.
    """
    session = Session(args)
    session.view_distance = args.from_distance
    held: set[tuple[int, int]] = set()
    unloaded: set[tuple[int, int]] = set()
    center: tuple[int, int] | None = None
    batches = 0
    sent_after_unload = 0
    switched = False
    try:
        session.enter_play()
        last_chunk_packet = time.monotonic()
        deadline = time.monotonic() + args.wait
        session.connection.socket.settimeout(0.5)
        while time.monotonic() < deadline:
            if switched and time.monotonic() - last_chunk_packet >= args.settle:
                break
            try:
                packet, reader, _ = session.recv()
            except socket.timeout:
                continue
            if packet == "ChunkDataPacket":
                column = (reader.int32(), reader.int32())
                if column in unloaded:
                    sent_after_unload += 1
                held.add(column)
                last_chunk_packet = time.monotonic()
            elif packet == "UnloadChunkPacket":
                # Written z first, the client reads the two as one long
                chunk_z, chunk_x = reader.int32(), reader.int32()
                held.discard((chunk_x, chunk_z))
                unloaded.add((chunk_x, chunk_z))
                last_chunk_packet = time.monotonic()
            elif packet == "UpdateViewPositionPacket":
                center = (reader.varint(), reader.varint())
            elif packet == "ChunkBatchFinishedPacket":
                batches += 1
                session.connection.send("ClientChunkBatchReceivedPacket", Writer().float32(64.0).bytes())
                if not switched and batches >= args.switch_after:
                    session.connection.send("ClientSettingsPacket", client_settings_payload(args.to_distance))
                    session.log(f"sent ClientSettings view_distance={args.to_distance} after {batches} batches "
                                f"holding {len(held)} chunks")
                    switched = True
            elif packet == "KeepAlivePacket":
                session.answer_keep_alive(reader)
            elif packet == "PluginMessagePacket":
                session.read_payload(reader)
    finally:
        session.close()
    if not switched:
        session.log(f"never lowered the view distance: batches={batches} center={center}")
        return 1
    if center is None:
        session.log(f"lowered the view distance but the server never sent UpdateViewPosition: batches={batches}")
        return 1
    radius = args.to_distance + 1
    ring = {(center[0] + dx, center[1] + dz)
            for dx in range(-radius, radius + 1) for dz in range(-radius, radius + 1)}
    outside = sorted(held - ring)
    missing = sorted(ring - held)
    session.log(f"center={center} radius={radius} held={len(held)} ring={len(ring)} outside={len(outside)} "
                f"missing={len(missing)} sent_after_unload={sent_after_unload} batches={batches}")
    if outside:
        session.log(f"held outside the ring, first ones: {outside[:8]}")
    if missing:
        session.log(f"missing from the ring, first ones: {missing[:8]}")
    return 0 if not outside and not missing else 1


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


def split_registry_dump(data: bytes) -> list[tuple[str, list[tuple[str, bytes]]]]:
    """Splits a ``record-registry`` dump into its registries and their entries.

    The dump is the bodies of the RegistryData packets back to back, each starting with its packet
    id: ``cmd_record_registry`` writes it so, and so does ``RegistryDataByteParityIntegrationTest``
    for the sample. An entry is its id, a has-data flag and, when the flag is set, its data as
    network NBT; every entry keeps the exact bytes it was sent as.
    """
    packet_id = REGISTRIES[_SERVER_REGISTRY[CONFIGURATION]].index("RegistryDataPacket")
    reader = Reader(data)
    registries = []
    while reader.remaining():
        start = reader.stream.tell()
        found = reader.varint()
        if found != packet_id:
            raise ValueError(f"byte {start}: packet id 0x{found:02x}, a RegistryData packet is 0x{packet_id:02x}")
        name = reader.string()
        entries = []
        for _ in range(reader.varint()):
            entry_start = reader.stream.tell()
            entry = reader.string()
            if reader.boolean():
                reader.nbt()
            entries.append((entry, data[entry_start:reader.stream.tell()]))
        registries.append((name, entries))
    return registries


def split_ignored(item: str) -> tuple[str, str]:
    """Splits ``<registry>/<entry>``: both are namespaced and a registry name may hold a slash
    (``minecraft:worldgen/biome``), an entry's own namespace starts after the last one before its
    colon."""
    colon = item.rfind(":")
    slash = item.rfind("/", 0, colon)
    if colon < 0 or slash < 0 or ":" not in item[:slash]:
        raise SystemExit(f"--ignore {item}: expected <registry>/<entry>, as in minecraft:dimension_type/synaxis:match")
    return item[:slash], item[slash + 1:]


def cmd_compare_registry(args: argparse.Namespace) -> int:
    sample = split_registry_dump(Path(args.sample).read_bytes())
    recorded = split_registry_dump(Path(args.recorded).read_bytes())
    ignored = {split_ignored(item) for item in args.ignore}
    print(f"sample={args.sample} registries={len(sample)} entries={sum(len(e) for _, e in sample)}", flush=True)
    print(f"recorded={args.recorded} registries={len(recorded)} entries={sum(len(e) for _, e in recorded)}",
          flush=True)

    differences = 0

    def differs(what: str) -> None:
        nonlocal differences
        differences += 1
        print(f"differs {what}", flush=True)

    sample_names = [name for name, _ in sample]
    recorded_names = [name for name, _ in recorded]
    for name in sample_names:
        if name not in recorded_names:
            differs(f"{name}: registry only in the sample")
    for name in recorded_names:
        if name not in sample_names:
            differs(f"{name}: registry only in the recording")
    common = [name for name in sample_names if name in recorded_names]
    if common != [name for name in recorded_names if name in sample_names]:
        differs(f"registry order: sample {common} recording {[n for n in recorded_names if n in sample_names]}")

    for registry, entry in sorted(ignored):
        in_sample = any(name == registry and any(e == entry for e, _ in entries) for name, entries in sample)
        in_recorded = any(name == registry and any(e == entry for e, _ in entries) for name, entries in recorded)
        print(f"ignored {registry}/{entry}: sample={'present' if in_sample else 'absent'} "
              f"recording={'present' if in_recorded else 'absent'}", flush=True)

    same = 0
    recorded_by_name = dict(recorded)
    for name, sample_entries in sample:
        if name not in recorded_by_name:
            continue
        left = [(entry, raw) for entry, raw in sample_entries if (name, entry) not in ignored]
        right = [(entry, raw) for entry, raw in recorded_by_name[name] if (name, entry) not in ignored]
        if left == right:
            same += 1
            print(f"same {name} entries={len(left)} bytes={sum(len(raw) for _, raw in left)}", flush=True)
            continue
        left_raw, right_raw = dict(left), dict(right)
        for entry, raw in left:
            if entry not in right_raw:
                differs(f"{name}/{entry}: only in the sample")
            elif raw != right_raw[entry]:
                differs(f"{name}/{entry}: {len(raw)} bytes in the sample, {len(right_raw[entry])} in the recording")
        for entry, _ in right:
            if entry not in left_raw:
                differs(f"{name}/{entry}: only in the recording")
        left_order = [entry for entry, _ in left if entry in right_raw]
        right_order = [entry for entry, _ in right if entry in left_raw]
        if left_order != right_order:
            differs(f"{name}: entries in another order")
    print(f"compare-registry registries={len(sample)} same={same} differences={differences} "
          f"ignored={len(ignored)}", flush=True)
    return 0 if differences == 0 else 1


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

    slow = subparsers.add_parser("slow-reader",
                                 help="stop reading after login and see whether the server lets go of the socket")
    slow.add_argument("--payloads", type=int, default=256, help="echo payloads to send, none of them read back")
    slow.add_argument("--bytes", type=int, default=30000, help="size of each payload, incompressible")
    slow.add_argument("--channel", default="synaxis:main", help="the echo channel (core mode only)")
    slow.add_argument("--wait", type=float, default=30.0,
                      help="seconds to hold the socket without reading, past the keep alive kick")
    slow.set_defaults(run=cmd_slow_reader)

    view = subparsers.add_parser("view-distance",
                                 help="log in at one view distance, lower it and check the chunks held")
    view.add_argument("--from", dest="from_distance", type=int, default=12, help="the view distance to log in at")
    view.add_argument("--to", dest="to_distance", type=int, default=4, help="the view distance to lower it to")
    view.add_argument("--switch-after", type=int, default=1, help="chunk batches to receive before lowering it")
    view.add_argument("--settle", type=float, default=3.0,
                      help="seconds without a chunk packet after which the books are closed")
    view.add_argument("--wait", type=float, default=60.0, help="seconds before giving up")
    view.set_defaults(run=cmd_view_distance)

    record = subparsers.add_parser("record-registry", help="dump every RegistryData packet")
    record.add_argument("--out", required=True)
    record.set_defaults(run=cmd_record_registry)

    compare = subparsers.add_parser("compare-registry",
                                    help="compare two record-registry dumps registry by registry and entry by entry")
    compare.add_argument("sample", help="the dump to compare against, e.g. src/test/resources/protocol/"
                                        "registry-data-26.2.bin")
    compare.add_argument("recorded", help="the dump record-registry wrote")
    compare.add_argument("--ignore", action="append", default=[], metavar="REGISTRY/ENTRY",
                         help="leave this entry out on both sides, e.g. minecraft:dimension_type/synaxis:match; "
                              "repeatable")
    compare.set_defaults(run=cmd_compare_registry)

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
