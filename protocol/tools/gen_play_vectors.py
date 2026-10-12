#!/usr/bin/env python3
"""Freeze the ``synaxis:play`` vectors from the layout tables of ``docs/DESIGN-PLAY.md``.

The encoder below is written from ``API-PLAY-002`` (message layouts) and ``API-PLAY-003`` (widget, TLV and
value types), not from the Java records of ``:protocol``. ``PlayVectorTest`` decodes every file with the
records and writes it back, so the frozen bytes compare two encoders written apart.

Writes every ``.bin`` of the root and of ``edge/`` and ``manifest.txt`` (``play_version``, ``digest`` and a
readable dump per file) into the given directory, by default
``protocol/src/test/resources/play-vectors``. The ``component/`` subdirectory is not touched. To check the
frozen set without Gradle, write into an empty directory and compare::

    d=$(mktemp -d); <venv>/bin/python protocol/tools/gen_play_vectors.py "$d"
    diff -r -x component protocol/src/test/resources/play-vectors "$d"; rm -rf "$d"

When a vector changes, change it here, run the script without an argument, and update the expected record
in ``PlayVectorTest`` in the same change. Standard library only.
"""

from __future__ import annotations

import hashlib
import pathlib
import struct
import sys

PLAY_VERSION = 1

MAX = 2**31 - 1
MIN = -(2**31)
LONG_MAX = 2**63 - 1
LONG_MIN = -(2**63)

# msg_id (API-PLAY-002)
S2C_SCENE, S2C_HUD, S2C_ACTION_RESULT = 0x81, 0x82, 0x84
C2S_SCENE_READY, C2S_ACTION = 0x01, 0x02
# op of S2C_HUD
SET, REMOVE, CLEAR_OWNER = 1, 2, 3
# widget kinds and slots (API-PLAY-003)
TEXT, TIMER, SCOREBOARD, PANEL = 1, 2, 4, 6
TOP_LEFT, TOP_CENTER, TOP_RIGHT, LEFT, RIGHT, BOTTOM, CENTER = range(7)
# value types (API-PLAY-003)
T_VARINT, T_VARLONG, T_F32, T_STRING, T_COMPONENT, T_BOOL, T_VEC3F, T_ROWS, T_BUTTONS = range(1, 10)


def varint(value: int) -> bytes:
    """A VarInt: the low 32 bits in two's complement, seven bits per byte, low group first."""
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        if value < 0x80:
            out.append(value)
            return bytes(out)
        out.append((value & 0x7F) | 0x80)
        value >>= 7


def varlong(value: int) -> bytes:
    """A VarLong: as a VarInt over 64 bits."""
    value &= 0xFFFFFFFFFFFFFFFF
    out = bytearray()
    while True:
        if value < 0x80:
            out.append(value)
            return bytes(out)
        out.append((value & 0x7F) | 0x80)
        value >>= 7


def u8(value: int) -> bytes:
    return bytes([value])


def f32(value: float) -> bytes:
    return struct.pack(">f", value)


def string(value: str) -> bytes:
    """A string: VarInt byte length, then UTF-8."""
    data = value.encode("utf-8")
    return varint(len(data)) + data


def component(data: bytes) -> bytes:
    """A component: VarInt length, then bytes :protocol does not look into."""
    return varint(len(data)) + data


def text(value: str) -> bytes:
    """The network form of a plain text component: an NBT string tag (08, two byte length, UTF-8)."""
    data = value.encode("utf-8")
    return b"\x08" + struct.pack(">H", len(data)) + data


# --- props: (prop_id, value bytes with the type byte in front) ---------------------------------------------

def p_varint(prop_id: int, value: int) -> bytes:
    return varint(prop_id) + u8(T_VARINT) + varint(value)


def p_varlong(prop_id: int, value: int) -> bytes:
    return varint(prop_id) + u8(T_VARLONG) + varlong(value)


def p_f32(prop_id: int, value: float) -> bytes:
    return varint(prop_id) + u8(T_F32) + f32(value)


def p_string(prop_id: int, value: str) -> bytes:
    return varint(prop_id) + u8(T_STRING) + string(value)


def p_component(prop_id: int, data: bytes) -> bytes:
    return varint(prop_id) + u8(T_COMPONENT) + component(data)


def p_bool(prop_id: int, value: bool) -> bytes:
    return varint(prop_id) + u8(T_BOOL) + u8(1 if value else 0)


def p_vec3f(prop_id: int, x: float, y: float, z: float) -> bytes:
    return varint(prop_id) + u8(T_VEC3F) + f32(x) + f32(y) + f32(z)


def p_rows(prop_id: int, rows: list[tuple[bytes, str, bool]]) -> bytes:
    body = varint(len(rows))
    for label, value, highlight in rows:
        body += component(label) + string(value) + u8(1 if highlight else 0)
    return varint(prop_id) + u8(T_ROWS) + body


def p_buttons(prop_id: int, buttons: list[tuple[str, bytes]]) -> bytes:
    body = varint(len(buttons))
    for action_id, label in buttons:
        body += string(action_id) + component(label)
    return varint(prop_id) + u8(T_BUTTONS) + body


def widget(kind: int, slot: int, order: int, props: list[bytes]) -> bytes:
    """widget = kind u8, slot u8, order varint, props count × {prop_id varint, type u8, value}."""
    return u8(kind) + u8(slot) + varint(order) + varint(len(props)) + b"".join(props)


# --- messages (API-PLAY-002) --------------------------------------------------------------------------------

def s2c_scene(scene_seq: int, scene_id: str, title: bytes, camera: int, cursor: int,
              actions: list[tuple[int, str, bytes, int]]) -> bytes:
    body = varint(S2C_SCENE) + varint(scene_seq) + string(scene_id) + component(title) + u8(camera) + u8(cursor)
    body += varint(len(actions))
    for slot, action_id, label, cooldown_ms in actions:
        body += u8(slot) + string(action_id) + component(label) + varint(cooldown_ms)
    return body


def op_set(owner: str, widget_id: str, data: bytes) -> bytes:
    return u8(SET) + string(owner) + string(widget_id) + data


def op_remove(owner: str, widget_id: str) -> bytes:
    return u8(REMOVE) + string(owner) + string(widget_id)


def op_clear(owner: str) -> bytes:
    return u8(CLEAR_OWNER) + string(owner) + string("")


def s2c_hud(ops: list[bytes]) -> bytes:
    return varint(S2C_HUD) + varint(len(ops)) + b"".join(ops)


def s2c_action_result(action_seq: int, code: int, reason: bytes, cooldown_ms: int) -> bytes:
    return varint(S2C_ACTION_RESULT) + varint(action_seq) + u8(code) + component(reason) + varint(cooldown_ms)


def c2s_scene_ready(scene_seq: int) -> bytes:
    return varint(C2S_SCENE_READY) + varint(scene_seq)


def c2s_action(scene_seq: int, action_seq: int, action_id: str, target: bytes) -> bytes:
    return varint(C2S_ACTION) + varint(scene_seq) + varint(action_seq) + string(action_id) + target


def no_target() -> bytes:
    return u8(0)


def entity_target(entity_id: int) -> bytes:
    return u8(1) + varint(entity_id)


def block_target(x: int, y: int, z: int) -> bytes:
    return u8(2) + varint(x) + varint(y) + varint(z)


# --- the set ------------------------------------------------------------------------------------------------

def rows(count: int, highlight_odd: bool = True) -> list[tuple[bytes, str, bool]]:
    return [(text(f"p{i}"), str(i), highlight_odd and i % 2 == 1) for i in range(count)]


def canonical_hud() -> bytes:
    return s2c_hud([
        op_clear("rush"),
        op_set("rush", "hint", widget(TEXT, LEFT, 0, [p_component(1, text("Press 1 to queue")), p_varint(13, 0)])),
        op_set("rush", "timer", widget(TIMER, TOP_CENTER, 0, [
            p_component(1, text("Time left")), p_varint(2, 45_000), p_bool(3, True)])),
        op_set("rush", "score", widget(SCOREBOARD, TOP_RIGHT, 0, [
            p_component(1, text("Score")), p_rows(7, [(text("Steve"), "12", True), (text("Alex"), "7", False)])])),
        op_set("rush", "result", widget(PANEL, CENTER, 0, [
            p_component(1, text("Result")), p_rows(7, [(text("Steve"), "12", True)]),
            p_buttons(10, [("rush:back", text("Back to lobby"))])])),
        op_remove("rush", "capture"),
    ])


def edge_hud() -> bytes:
    owner = "edge:" + "o" * 59
    ops = [
        op_clear(owner),
        op_set(owner, "w" * 32, widget(TEXT, TOP_LEFT, MIN, [
            p_component(1, text("x")),
            p_varint(13, MAX),
            p_varint(0, 0),
            p_varint(14, -1),
            p_varlong(15, LONG_MIN),
            p_f32(16, -0.5),
            p_string(17, "s" * 64),
            p_component(18, b""),
            p_bool(19, False),
            p_vec3f(20, 1.5, -2.25, 1024.0),
            p_rows(21, [(text("r"), "v" * 32, False)]),
            p_buttons(22, [("edge:" + "b" * 59, text("b"))]),
            p_varlong(23, LONG_MAX),
            p_f32(24, struct.unpack(">f", b"\x7f\x7f\xff\xff")[0]),  # Float.MAX_VALUE
            p_bool(25, True),
            p_varint(MAX, 7),
        ])),
        op_set("rush", "score", widget(SCOREBOARD, TOP_RIGHT, MAX, [p_component(1, text("Score")), p_rows(7, rows(16))])),
        op_set("rush", "result", widget(PANEL, CENTER, 0, [
            p_component(1, text("Result")), p_rows(7, rows(12)),
            p_buttons(10, [(f"rush:b{i}", text(f"B{i}")) for i in range(4)])])),
        op_set("rush", "timer", widget(TIMER, BOTTOM, 0, [
            p_component(1, text("t")), p_varint(2, MAX), p_bool(3, False)])),
    ]
    ops += [op_remove("rush", f"w{i}") for i in range(27)]
    return s2c_hud(ops)


def hint(props: list[bytes], slot: int = LEFT) -> bytes:
    """One SET of rush/hint, a TEXT with the given props."""
    return s2c_hud([op_set("rush", "hint", widget(TEXT, slot, 0, props))])


VECTORS: list[tuple[str, str, bytes]] = [
    # canonical, one per message of version 1
    ("c2s_action.bin", "C2S_ACTION scene_seq=1 action_seq=1 rush:queue target none",
     c2s_action(1, 1, "rush:queue", no_target())),
    ("c2s_scene_ready.bin", "C2S_SCENE_READY scene_seq=1", c2s_scene_ready(1)),
    ("s2c_action_result.bin", "S2C_ACTION_RESULT action_seq=1 code=0 ok, no reason, cooldown 2000 ms",
     s2c_action_result(1, 0, b"", 2000)),
    ("s2c_hud.bin", "S2C_HUD 6 ops: CLEAR_OWNER rush; SET rush/hint TEXT; SET rush/timer TIMER 45000 ms running; "
                    "SET rush/score SCOREBOARD 2 rows; SET rush/result PANEL 1 row 1 button; REMOVE rush/capture",
     canonical_hud()),
    ("s2c_scene.bin", 'S2C_SCENE scene_seq=1 scene_id=rush:lobby title="Lobby" camera=0 cursor=0 '
                      'actions=[slot 0 rush:queue "Queue" 2000 ms]',
     s2c_scene(1, "rush:lobby", text("Lobby"), 0, 0, [(0, "rush:queue", text("Queue"), 2000)])),

    # accepted boundary vectors
    ("edge/c2s_action-entity.bin",
     "C2S_ACTION scene_seq=0 (no scene yet) action_seq=1, entity target MAX_INT; "
     "scene_seq and action_seq differ so that their order is pinned",
     c2s_action(0, 1, "x:y", entity_target(MAX))),
    ("edge/c2s_action.bin",
     "C2S_ACTION scene_seq=MAX_INT action_seq=MAX_INT, a 64 B action id, block target (MIN_INT, -64, MAX_INT)",
     c2s_action(MAX, MAX, "edge:" + "q" * 59, block_target(MIN, -64, MAX))),
    ("edge/c2s_scene_ready.bin", "C2S_SCENE_READY scene_seq=MAX_INT", c2s_scene_ready(MAX)),

    # refused: both ends must treat these as malformed
    ("edge/refused-c2s_action-target-3.bin", "refused: target 3, targets are 0..2",
     c2s_action(1, 1, "rush:queue", u8(3))),
    ("edge/refused-s2c_hud-17-props.bin", "refused: a TEXT with 17 props, over the cap of 16",
     hint([p_component(1, text("x")), p_varint(13, 0)] + [p_varint(30 + i, i) for i in range(15)])),
    ("edge/refused-s2c_hud-33-ops.bin", "refused: 33 ops, over the cap of 32",
     s2c_hud([op_remove("rush", f"w{i}") for i in range(33)])),
    ("edge/refused-s2c_hud-component-2049.bin", "refused: a 2,049 B component, over the cap of 2,048",
     hint([p_component(1, b"\x08" + struct.pack(">H", 2046) + b"a" * 2046)])),
    ("edge/refused-s2c_hud-declared-prop-wrong-type.bin",
     "refused: remaining_ms of a TIMER carried as varlong, its declared type is varint",
     s2c_hud([op_set("rush", "timer", widget(TIMER, TOP_CENTER, 0, [
         p_component(1, text("Time left")), p_varlong(2, 45_000)]))])),
    ("edge/refused-s2c_hud-op-4.bin", "refused: op 4, ops are 1..3",
     s2c_hud([u8(4) + string("rush") + string("hint")])),
    ("edge/refused-s2c_hud-panel-13-rows.bin", "refused: a PANEL with 13 rows, over its cap of 12",
     s2c_hud([op_set("rush", "result", widget(PANEL, CENTER, 0, [p_component(1, text("Result")), p_rows(7, rows(13, highlight_odd=False))]))])),
    ("edge/refused-s2c_hud-panel-5-buttons.bin", "refused: a buttons value of 5 buttons, over the cap of 4",
     s2c_hud([op_set("rush", "result", widget(PANEL, CENTER, 0, [
         p_component(1, text("Result")), p_buttons(10, [(f"rush:b{i}", text("B")) for i in range(5)])]))])),
    ("edge/refused-s2c_hud-panel-not-center.bin", "refused: a PANEL on TOP_LEFT, a PANEL is always CENTER",
     s2c_hud([op_set("rush", "result", widget(PANEL, TOP_LEFT, 0, [p_component(1, text("Result"))]))])),
    ("edge/refused-s2c_hud-scoreboard-17-rows.bin", "refused: a rows value of 17 rows, over the cap of 16",
     s2c_hud([op_set("rush", "score", widget(SCOREBOARD, TOP_RIGHT, 0, [p_component(1, text("Score")), p_rows(7, rows(17, highlight_odd=False))]))])),
    ("edge/refused-s2c_hud-slot-7.bin", "refused: slot 7, outside 0..6",
     hint([p_component(1, text("x"))], slot=7)),
    ("edge/refused-s2c_hud-value-type-10.bin", "refused: value type 10, no such type in v1 so the prop cannot be skipped",
     hint([p_component(1, text("x")), varint(14) + u8(10) + b"\x00"])),
    ("edge/refused-s2c_scene-7-actions.bin", "refused: 7 actions, over the cap of 6",
     s2c_scene(1, "rush:lobby", text("Lobby"), 0, 0, [(i, f"rush:a{i}", text("A"), 0) for i in range(7)])),

    ("edge/s2c_action_result.bin",
     "S2C_ACTION_RESULT action_seq=MAX_INT, code=255 kept as received (shown as rejected), a reason, cooldown 600,000 ms",
     s2c_action_result(MAX, 255, text("rush.reason"), 600_000)),
    ("edge/s2c_hud-unknown-kind.bin",
     "S2C_HUD one SET of kind 3 (BAR of v2) to a v1 decoder: accepted and kept, the client drops it",
     s2c_hud([op_set("rush", "capture", widget(3, BOTTOM, 0, [
         p_component(1, text("Capture A")), p_f32(4, 0.4), p_f32(5, 0.1), p_varlong(6, 0xFF00FF00)]))])),
    ("edge/s2c_hud-unknown-prop.bin",
     'S2C_HUD one SET of a TIMER carrying prop_id=99 of type varint (DESIGN-PLAY §19 "未知属性被跳过"): '
     "accepted, the prop kept",
     s2c_hud([op_set("rush", "timer", widget(TIMER, TOP_CENTER, 0, [
         p_component(1, text("Time left")), p_varint(2, 45_000), p_bool(3, True), p_varint(99, 7)]))])),
    ("edge/s2c_hud.bin",
     "S2C_HUD at its caps: 32 ops; a 64 B owner and a 32 B widget_id; a TEXT with 16 props, 14 of them undeclared "
     "and covering all nine value types (prop ids 0 and MAX_INT, a 64 B string, an empty component, "
     "Float.MAX_VALUE); a SCOREBOARD with 16 rows; a PANEL with 12 rows and 4 buttons; a stopped TIMER at "
     "MAX_INT ms; 27 REMOVE",
     edge_hud()),
    ("edge/s2c_scene.bin",
     "S2C_SCENE at its caps: scene_seq=MAX_INT, 64 B scene_id, a 2,048 B title, camera=255 and cursor=254 kept "
     "as received (they differ so that their order is pinned), six actions on slots 5..0 with 64 B ids and "
     "cooldown 600,000 ms",
     s2c_scene(MAX, "edge:" + "s" * 59, text("a" * 2045), 255, 254,
               [(slot, "edge:" + "abcdef"[slot] * 59, text(f"A{slot}"), 600_000) for slot in range(5, -1, -1)])),
]

HEADER = """\
# synaxis:play 的冻结向量 —— docs/DESIGN-PLAY.md API-PLAY-002、API-PLAY-003、§24，docs/TEST.md TC-PLAY-001～TC-PLAY-009。
#
# 根目录每条消息一个常规向量；edge/ 下是同名的边界向量（计数取满、VarInt 取满五字节）、另外几条可接受的边界向量
# （未知属性、未知种类、scene_seq=0 的动作），以及文件名以 refused- 开头、两端都必须按畸形拒收的向量。
# 服务端由 protocol 的 PlayVectorTest 逐字节对拍（那里的 record 是期望值的事实源，下面的说明是它的可读摘要），
# 客户端模组的 PlayFrozenVectorTest 经系统属性 synaxis.play-vectors.dir 读同一目录。component 一律用网络格式
# NBT 的字符串标签（08、两字节长度、UTF-8），:protocol 只把它当不透明字节。
#
# 本文件与全部 .bin 由 protocol/tools/gen_play_vectors.py 照设计文本生成，不经 :protocol 的 record。同一条消息里
# 相邻的同类型字段（如 camera 与 cursor、scene_seq 与 action_seq）至少在一个向量里取不同的值，否则读写顺序对调了也测不出来。
#
# digest：先取根目录的 .bin、再取 edge/ 的 .bin，两组各按文件名排序，逐个把相对路径（UTF-8，如 edge/s2c_hud.bin）
# 与文件内容依次喂进同一个 SHA-256。它与 play_version 绑在一起：向量变了就改 digest；布局、小部件种类或值类型变了，
# 还要在同一个改动里升 PlayProtocol.version() 与 play_version。
# component/ 子目录（组件解码的冻结字节）不进 digest、不绑 play_version。
"""


def ordered(vectors: list[tuple[str, str, bytes]]) -> list[tuple[str, str, bytes]]:
    """The root's files by name, then edge/'s by name: the order the digest is taken in."""
    return sorted(vectors, key=lambda v: (v[0].startswith("edge/"), v[0].removeprefix("edge/")))


def manifest(vectors: list[tuple[str, str, bytes]]) -> str:
    sha = hashlib.sha256()
    for path, _, data in vectors:
        sha.update(path.encode("utf-8"))
        sha.update(data)
    out = [HEADER, f"play_version={PLAY_VERSION}", f"digest={sha.hexdigest()}"]
    for path, description, data in vectors:
        shown = " ".join(f"{b:02X}" for b in data[:48]) + (" …" if len(data) > 48 else "")
        out += ["", f"## {path}  ({len(data)} 字节)", f"内容: {description}", f"字节:   {shown}"]
    return "\n".join(out) + "\n"


def main() -> None:
    default = pathlib.Path(__file__).resolve().parent.parent / "src/test/resources/play-vectors"
    out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else default
    (out / "edge").mkdir(parents=True, exist_ok=True)
    vectors = ordered(VECTORS)
    for path, _, data in vectors:
        (out / path).write_bytes(data)
    (out / "manifest.txt").write_text(manifest(vectors), encoding="utf-8")
    print(f"{len(vectors)} vectors and manifest.txt written to {out}")


if __name__ == "__main__":
    main()
