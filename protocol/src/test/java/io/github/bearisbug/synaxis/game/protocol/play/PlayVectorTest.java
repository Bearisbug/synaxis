package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue.Button;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue.Row;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud.ClearOwner;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud.Op;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud.RemoveWidget;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud.SetWidget;
import io.github.bearisbug.synaxis.game.protocol.play.Widget.Prop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frozen vectors of {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-002},
 * {@code API-PLAY-003} and §24; {@code docs/TEST.md} TC-PLAY-001 to TC-PLAY-005).
 * <p>
 * This class is where the expected value of every vector is written down. Each accepted vector decodes into
 * the record below and re-encodes byte for byte; each {@code edge/refused-} vector is malformed, with the
 * reason named here. The bytes come from {@code protocol/tools/gen_play_vectors.py}, written from the design
 * text rather than from these records, and neighbouring fields of one type differ in at least one vector, so
 * a record that drifted from the table, field order included, fails here. {@code manifest.txt} binds the digest
 * to {@link PlayProtocol#version()}; the client mod runs the same directory in {@code PlayFrozenVectorTest}.
 */
class PlayVectorTest {

    private static final int MAX = Integer.MAX_VALUE;
    private static final int MIN = Integer.MIN_VALUE;
    private static final String OWNER_64 = "edge:" + "o".repeat(59);

    /**
     * A plain text component in the vanilla network format: a bare NBT string tag. {@code :protocol} never
     * looks inside it; it only needs to be the bytes a server would send.
     */
    static EncodedComponent text(String text) {
        final byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        final byte[] tag = new byte[3 + utf8.length];
        tag[0] = 0x08;
        tag[1] = (byte) (utf8.length >>> 8);
        tag[2] = (byte) utf8.length;
        System.arraycopy(utf8, 0, tag, 3, utf8.length);
        return new EncodedComponent(tag);
    }

    /**
     * One accepted vector and the record it must decode into. The record is built in the test, after the
     * decoder has run, so that a rule of the records that breaks shows up as the decoder refusing the vector.
     */
    record Vector(String file, Supplier<PlayMessage> expected) {
        @Override
        public String toString() {
            return file;
        }
    }

    /**
     * One {@code refused-} vector and a fragment of the reason the decoder must give.
     */
    record Refused(String file, String reason) {
        @Override
        public String toString() {
            return file;
        }
    }

    static List<Vector> vectors() {
        return List.of(
                new Vector("s2c_scene.bin", () -> new S2CScene(1, "probe:lobby", text("Lobby"), 0, 0,
                        List.of(new S2CScene.Action(0, "probe:queue", text("Queue"), 2000)))),
                new Vector("s2c_hud.bin", () -> new S2CHud(List.of(
                        new ClearOwner("probe"),
                        new SetWidget("probe", "hint", Widget.text(Widget.LEFT, 0, text("Press 1 to queue"), 0)),
                        new SetWidget("probe", "timer", Widget.timer(Widget.TOP_CENTER, 0, text("Time left"), 45_000, true)),
                        new SetWidget("probe", "score", Widget.scoreboard(Widget.TOP_RIGHT, 0, text("Score"), List.of(
                                new Row(text("Steve"), "12", true), new Row(text("Alex"), "7", false)))),
                        new SetWidget("probe", "result", Widget.panel(text("Result"),
                                List.of(new Row(text("Steve"), "12", true)),
                                List.of(new Button("probe:back", text("Back to lobby"))))),
                        new RemoveWidget("probe", "capture")))),
                new Vector("s2c_action_result.bin", () -> new S2CActionResult(1, ActionResultCode.OK, EncodedComponent.NONE, 2000)),
                new Vector("c2s_scene_ready.bin", () -> new C2SSceneReady(1)),
                new Vector("c2s_action.bin", () -> new C2SAction(1, 1, "probe:queue", new C2SAction.None())),

                new Vector("edge/s2c_scene.bin", () -> new S2CScene(MAX, "edge:" + "s".repeat(59), text("a".repeat(2045)),
                        255, 254, edgeActions())),
                new Vector("edge/s2c_hud.bin", () -> new S2CHud(edgeOps())),
                new Vector("edge/s2c_hud-unknown-prop.bin", () -> new S2CHud(List.of(new SetWidget("probe", "timer",
                        new Widget(Widget.TIMER, Widget.TOP_CENTER, 0, List.of(
                                new Prop(Widget.PROP_TEXT, new PropValue.Component(text("Time left"))),
                                new Prop(Widget.PROP_REMAINING_MS, new PropValue.VarInt(45_000)),
                                new Prop(Widget.PROP_RUNNING, new PropValue.Bool(true)),
                                new Prop(99, new PropValue.VarInt(7)))))))),
                new Vector("edge/s2c_hud-unknown-kind.bin", () -> new S2CHud(List.of(new SetWidget("probe", "capture",
                        new Widget(3, Widget.BOTTOM, 0, List.of(
                                new Prop(1, new PropValue.Component(text("Capture A"))),
                                new Prop(4, new PropValue.F32(0.4f)),
                                new Prop(5, new PropValue.F32(0.1f)),
                                new Prop(6, new PropValue.VarLong(0xFF00FF00L)))))))),
                new Vector("edge/s2c_action_result.bin", () -> new S2CActionResult(MAX, 255, text("probe.reason"), 600_000)),
                new Vector("edge/c2s_scene_ready.bin", () -> new C2SSceneReady(MAX)),
                new Vector("edge/c2s_action.bin", () -> new C2SAction(MAX, MAX, "edge:" + "q".repeat(59),
                        new C2SAction.OfBlock(MIN, -64, MAX))),
                new Vector("edge/c2s_action-entity.bin", () -> new C2SAction(0, 1, "x:y", new C2SAction.OfEntity(MAX))));
    }

    static List<Refused> refused() {
        return List.of(
                new Refused("edge/refused-s2c_hud-33-ops.bin", "count 33 is outside [0, 32]"),
                new Refused("edge/refused-s2c_scene-7-actions.bin", "count 7 is outside [0, 6]"),
                new Refused("edge/refused-s2c_hud-17-props.bin", "count 17 is outside [0, 16]"),
                new Refused("edge/refused-s2c_hud-scoreboard-17-rows.bin", "count 17 is outside [0, 16]"),
                new Refused("edge/refused-s2c_hud-panel-13-rows.bin", "a PANEL has 13 rows, over the cap of 12"),
                new Refused("edge/refused-s2c_hud-panel-5-buttons.bin", "count 5 is outside [0, 4]"),
                new Refused("edge/refused-s2c_hud-panel-not-center.bin", "a PANEL is on slot 0, not CENTER"),
                new Refused("edge/refused-s2c_hud-slot-7.bin", "slot 7 is outside [0, 6]"),
                new Refused("edge/refused-s2c_hud-value-type-10.bin", "unknown value type: 10"),
                new Refused("edge/refused-s2c_hud-component-2049.bin", "component length 2049 is outside [0, 2048]"),
                new Refused("edge/refused-s2c_hud-declared-prop-wrong-type.bin", "prop 2 of kind 2 carries value type 2, not 1"),
                new Refused("edge/refused-s2c_hud-op-4.bin", "unknown op: 4"),
                new Refused("edge/refused-c2s_action-target-3.bin", "unknown target: 3"));
    }

    /** Six actions on slots 5 down to 0, every id and the cooldown at their caps. */
    static List<S2CScene.Action> edgeActions() {
        final List<S2CScene.Action> actions = new ArrayList<>();
        for (int slot = 5; slot >= 0; slot--) {
            actions.add(new S2CScene.Action(slot, "edge:" + String.valueOf("abcdef".charAt(slot)).repeat(59),
                    text("A" + slot), 600_000));
        }
        return actions;
    }

    /**
     * Thirty-two ops: a clear with a 64 byte owner, a TEXT with sixteen props of which fourteen are undeclared
     * and cover all nine value types, a full SCOREBOARD, a full PANEL, a stopped TIMER and 27 removes.
     */
    static List<Op> edgeOps() {
        final List<Op> ops = new ArrayList<>();
        ops.add(new ClearOwner(OWNER_64));
        ops.add(new SetWidget(OWNER_64, "w".repeat(32), new Widget(Widget.TEXT, Widget.TOP_LEFT, MIN, List.of(
                new Prop(Widget.PROP_TEXT, new PropValue.Component(text("x"))),
                new Prop(Widget.PROP_TTL_MS, new PropValue.VarInt(MAX)),
                new Prop(0, new PropValue.VarInt(0)),
                new Prop(14, new PropValue.VarInt(-1)),
                new Prop(15, new PropValue.VarLong(Long.MIN_VALUE)),
                new Prop(16, new PropValue.F32(-0.5f)),
                new Prop(17, new PropValue.Str("s".repeat(64))),
                new Prop(18, new PropValue.Component(EncodedComponent.NONE)),
                new Prop(19, new PropValue.Bool(false)),
                new Prop(20, new PropValue.Vec3f(1.5f, -2.25f, 1024f)),
                new Prop(21, new PropValue.Rows(List.of(new Row(text("r"), "v".repeat(32), false)))),
                new Prop(22, new PropValue.Buttons(List.of(new Button("edge:" + "b".repeat(59), text("b"))))),
                new Prop(23, new PropValue.VarLong(Long.MAX_VALUE)),
                new Prop(24, new PropValue.F32(Float.MAX_VALUE)),
                new Prop(25, new PropValue.Bool(true)),
                new Prop(MAX, new PropValue.VarInt(7))))));
        ops.add(new SetWidget("probe", "score", Widget.scoreboard(Widget.TOP_RIGHT, MAX, text("Score"), rows(16))));
        final List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < 4; i++) buttons.add(new Button("probe:b" + i, text("B" + i)));
        ops.add(new SetWidget("probe", "result", Widget.panel(text("Result"), rows(12), buttons)));
        ops.add(new SetWidget("probe", "timer", Widget.timer(Widget.BOTTOM, 0, text("t"), MAX, false)));
        for (int i = 0; i < 27; i++) ops.add(new RemoveWidget("probe", "w" + i));
        return ops;
    }

    private static List<Row> rows(int count) {
        final List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new Row(text("p" + i), String.valueOf(i), i % 2 == 1));
        return rows;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void aFrozenVectorDecodesAndReEncodesByteForByte(Vector vector) throws IOException {
        final byte[] frozen = Files.readAllBytes(directory().resolve(vector.file()));

        final MsgBuf reader = MsgBuf.reading(frozen);
        final PlayMessage decoded = PlayMessages.read(reader);
        final PlayMessage expected = vector.expected().get();
        assertEquals(expected, decoded, vector.file());
        assertEquals(0, reader.remaining(), vector.file() + " has bytes the decoder never reads");

        final MsgBuf writer = MsgBuf.writing();
        expected.write(writer);
        assertArrayEquals(frozen, writer.toByteArray(), vector.file());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    void aRefusedVectorIsMalformed(Refused vector) throws IOException {
        final byte[] frozen = Files.readAllBytes(directory().resolve(vector.file()));

        final MalformedMessageException thrown = assertThrows(MalformedMessageException.class,
                () -> PlayMessages.read(MsgBuf.reading(frozen)), vector.file());
        assertTrue(thrown.getMessage().contains(vector.reason()),
                () -> vector.file() + " was refused for " + thrown.getMessage() + ", not for " + vector.reason());
    }

    /** Every file of the set has its expectation above, and every message a canonical and a boundary vector. */
    @Test
    void everyFrozenFileIsInTheTablesAndEveryMessageHasTwoVectors() throws IOException {
        final TreeSet<String> listed = new TreeSet<>();
        for (final Vector vector : vectors()) listed.add(vector.file());
        for (final Refused vector : refused()) assertTrue(listed.add(vector.file()), vector.file());
        assertEquals(new TreeSet<>(allBinFiles()), listed);

        final List<String> canonical = binFiles(directory());
        assertEquals(List.of("c2s_action.bin", "c2s_scene_ready.bin", "s2c_action_result.bin", "s2c_hud.bin", "s2c_scene.bin"),
                canonical, "one canonical vector per message of version 1");
        assertTrue(binFiles(directory().resolve("edge")).containsAll(canonical), "a boundary vector per message");
    }

    @Test
    void theFrozenVectorsAreBoundToThePlayVersion() throws IOException {
        final List<String> manifest = Files.readAllLines(directory().resolve("manifest.txt"), StandardCharsets.UTF_8);

        assertEquals(String.valueOf(PlayProtocol.version()), value(manifest, "play_version"),
                "manifest.txt froze the vectors of another synaxis:play version");
        assertEquals(value(manifest, "digest"), digest(), """
                synaxis:play 的向量变了：按 docs/DESIGN-PLAY.md §24，在同一个改动里重新冻结 play-vectors/ 与 edge/ 下的 .bin、\
                把新的 digest 写进 manifest.txt；改的是布局、小部件种类或值类型时，还要把 PlayProtocol.version() 与 \
                play_version 一起加 1""");
    }

    private static String value(List<String> manifest, String key) {
        return manifest.stream()
                .filter(line -> line.startsWith(key + "="))
                .map(line -> line.substring(key.length() + 1).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("manifest.txt has no " + key));
    }

    /** The root's {@code .bin} files by name, then those of {@code edge/}: each one's relative path, then its bytes. */
    private static String digest() throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (final String file : allBinFiles()) {
            digest.update(file.getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(directory().resolve(file)));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> allBinFiles() throws IOException {
        final List<String> files = new ArrayList<>(binFiles(directory()));
        for (final String edge : binFiles(directory().resolve("edge"))) files.add("edge/" + edge);
        return files;
    }

    private static List<String> binFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".bin"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static Path directory() {
        final URL url = PlayVectorTest.class.getResource("/play-vectors/manifest.txt");
        if (url == null) throw new IllegalStateException("the synaxis:play vectors are missing from the test resources");
        try {
            return Path.of(url.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
