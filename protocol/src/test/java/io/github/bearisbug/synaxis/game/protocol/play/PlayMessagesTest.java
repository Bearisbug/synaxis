package io.github.bearisbug.synaxis.game.protocol.play;

import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.UnknownMessageException;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue.Button;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue.Row;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud.ClearOwner;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud.RemoveWidget;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud.SetWidget;
import io.github.bearisbug.synaxis.game.protocol.play.Widget.Prop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.github.bearisbug.synaxis.game.protocol.play.PlayVectorTest.text;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code synaxis:play} envelope and decoder of {@code docs/DESIGN-PLAY.md} {@code API-PLAY-002} and
 * {@code API-PLAY-003} ({@code docs/TEST.md} TC-PLAY-004 to TC-PLAY-008): every message round trips, trailing
 * bytes are skipped, the ids of later versions are unknown rather than malformed, the values the client falls
 * back on are kept, and every other shape the frozen vectors do not cover is a {@link MalformedMessageException}.
 */
class PlayMessagesTest {

    private static MsgBuf body(int msgId) {
        final MsgBuf buf = MsgBuf.writing();
        buf.writeVarInt(msgId);
        return buf;
    }

    private static PlayMessage decode(MsgBuf written) {
        return PlayMessages.read(MsgBuf.reading(written.toByteArray()));
    }

    private static PlayMessage roundTrip(PlayMessage message) {
        final MsgBuf writer = MsgBuf.writing();
        message.write(writer);
        return decode(writer);
    }

    private static void assertMalformed(String reason, Executable executable) {
        final MalformedMessageException thrown = assertThrows(MalformedMessageException.class, executable);
        assertTrue(thrown.getMessage().contains(reason), () -> "refused for " + thrown.getMessage() + ", not for " + reason);
    }

    /** A HUD batch of one SET, written by hand so that the widget can break the record's rules. */
    private static MsgBuf hudWithOneSet(int kind, int slot) {
        final MsgBuf buf = body(S2CHud.MSG_ID);
        buf.writeVarInt(1);
        buf.writeUnsignedByte(S2CHud.OP_SET);
        buf.writeString("probe");
        buf.writeString("w");
        buf.writeUnsignedByte(kind);
        buf.writeUnsignedByte(slot);
        buf.writeVarInt(0);
        return buf;
    }

    @Test
    void theChannelIsVersionOneOfSynaxisPlay() {
        assertEquals("synaxis:play", PlayProtocol.CHANNEL);
        assertEquals(1, PlayProtocol.version());
    }

    @Test
    void everyMessageOfVersionOneRoundTrips() {
        final List<PlayMessage> messages = List.of(
                new S2CScene(3, "probe:round", text("Round"), S2CScene.CAMERA_VANILLA, S2CScene.CURSOR_LOCKED, List.of()),
                new S2CHud(List.of(new ClearOwner("probe"), new RemoveWidget("probe", "hint"),
                        new SetWidget("probe", "capture", Widget.text(Widget.BOTTOM, 1, text("Capture A: 40%"), 0)))),
                new S2CActionResult(7, ActionResultCode.COOLDOWN, text("synaxis:action-cooldown"), 1500),
                new C2SSceneReady(3),
                new C2SAction(3, 7, "probe:back", new C2SAction.OfEntity(42)),
                new C2SAction(3, 8, "probe:queue", new C2SAction.OfBlock(-1, 64, 1)));
        for (final PlayMessage message : messages) assertEquals(message, roundTrip(message));
    }

    @Test
    void encodeGivesTheBytesWriteAppends() {
        for (final C2SAction.Target target : List.of(new C2SAction.None(), new C2SAction.OfEntity(42),
                new C2SAction.OfBlock(-1, 64, 1))) {
            final PlayMessage message = new C2SAction(3, 7, "probe:back", target);
            final MsgBuf writer = MsgBuf.writing();
            message.write(writer);
            assertArrayEquals(writer.toByteArray(), message.encode());
        }
    }

    @Test
    void trailingUnknownBytesAreSkipped() {
        final MsgBuf writer = MsgBuf.writing();
        new C2SSceneReady(2).write(writer);
        writer.writeBytes(new byte[5]);

        final MsgBuf reader = MsgBuf.reading(writer.toByteArray());
        assertEquals(new C2SSceneReady(2), PlayMessages.read(reader));
        assertEquals(5, reader.remaining());
    }

    /** S2C_GRANTS (W1), S2C_NOTICE and S2C_SCENE_LEAVE (W2) reach a version 1 client as unknown, not malformed. */
    @Test
    void theMessagesOfLaterVersionsAreUnknownRatherThanMalformed() {
        for (final int msgId : new int[]{0x80, 0x83, 0x85, 0x03}) {
            final UnknownMessageException thrown = assertThrows(UnknownMessageException.class,
                    () -> decode(body(msgId)));
            assertEquals(msgId, thrown.msgId());
            assertTrue(thrown.getMessage().contains("synaxis:play"), thrown.getMessage());
        }
    }

    @Test
    void theValuesTheClientFallsBackOnAreKeptAsReceived() {
        final S2CScene scene = new S2CScene(1, "x:y", text("t"), 1, 1, List.of());
        assertEquals(scene, roundTrip(scene), "camera 1 and cursor 1 belong to version 3");

        final S2CActionResult result = (S2CActionResult) roundTrip(new S2CActionResult(1, 6, EncodedComponent.NONE, 0));
        assertEquals(6, result.code());
        assertEquals(ActionResultCode.REJECTED, result.resultCode());
        assertTrue(result.reason().isEmpty());
        for (final ActionResultCode code : ActionResultCode.values()) {
            assertEquals(code, ActionResultCode.fromWireId(code.wireId()));
            assertEquals(code, new S2CActionResult(1, code, EncodedComponent.NONE, 0).resultCode());
        }
        assertEquals(List.of(0, 1, 2, 3, 4, 5),
                List.of(ActionResultCode.values()).stream().map(ActionResultCode::wireId).toList());
    }

    /**
     * DESIGN-PLAY §19 "未知属性被跳过": a TIMER written by hand with {@code prop_id=99} of type varint decodes,
     * the prop stays in the record and the declared ones read as usual.
     */
    @Test
    void anUndeclaredPropIsKeptAndTheDeclaredOnesStillRead() {
        final MsgBuf buf = hudWithOneSet(Widget.TIMER, Widget.TOP_CENTER);
        buf.writeVarInt(3);
        buf.writeVarInt(Widget.PROP_TEXT);
        buf.writeUnsignedByte(PropValue.TYPE_COMPONENT);
        text("Time left").write(buf);
        buf.writeVarInt(Widget.PROP_REMAINING_MS);
        buf.writeUnsignedByte(PropValue.TYPE_VAR_INT);
        buf.writeVarInt(45_000);
        buf.writeVarInt(99);
        buf.writeUnsignedByte(PropValue.TYPE_VAR_INT);
        buf.writeVarInt(7);
        final SetWidget set = (SetWidget) ((S2CHud) decode(buf)).ops().getFirst();

        assertEquals(new Widget(Widget.TIMER, Widget.TOP_CENTER, 0, List.of(
                new Prop(Widget.PROP_TEXT, new PropValue.Component(text("Time left"))),
                new Prop(Widget.PROP_REMAINING_MS, new PropValue.VarInt(45_000)),
                new Prop(99, new PropValue.VarInt(7)))), set.widget());
        assertEquals(Optional.of(new PropValue.VarInt(45_000)), set.widget().prop(Widget.PROP_REMAINING_MS));
        assertEquals(Optional.of(new PropValue.VarInt(7)), set.widget().prop(99));
        assertEquals(Optional.empty(), set.widget().prop(Widget.PROP_RUNNING));
    }

    @Test
    void theFactoriesFillTheDeclaredProps() {
        assertEquals(List.of(Widget.PROP_TEXT, Widget.PROP_TTL_MS),
                Widget.text(Widget.LEFT, 0, text("x"), 3000).props().stream().map(Prop::id).toList());
        assertEquals(List.of(Widget.PROP_TEXT, Widget.PROP_REMAINING_MS, Widget.PROP_RUNNING),
                Widget.timer(Widget.TOP_CENTER, 0, text("x"), 1, true).props().stream().map(Prop::id).toList());
        assertEquals(List.of(Widget.PROP_TEXT, Widget.PROP_ROWS),
                Widget.scoreboard(Widget.TOP_RIGHT, 0, text("x"), List.of()).props().stream().map(Prop::id).toList());
        final Widget panel = Widget.panel(text("x"), List.of(), List.of(new Button("probe:back", text("Back"))));
        assertEquals(List.of(Widget.PROP_TEXT, Widget.PROP_ROWS, Widget.PROP_BUTTONS), panel.props().stream().map(Prop::id).toList());
        assertEquals(Widget.CENTER, panel.slot());
        assertEquals(Widget.PANEL, panel.kind());
    }

    @Test
    void aComponentIsCopiedAndComparedByItsBytes() {
        final byte[] bytes = {8, 0, 1, 'x'};
        final EncodedComponent component = new EncodedComponent(bytes);
        bytes[3] = 'y';
        assertArrayEquals(new byte[]{8, 0, 1, 'x'}, component.bytes());
        component.bytes()[3] = 'z';
        assertEquals(text("x"), component);
        assertEquals(text("x").hashCode(), component.hashCode());
        assertNotEquals(text("y"), component);
        assertEquals("EncodedComponent[08000178]", component.toString());
        assertMalformed("component is 2049 bytes", () -> new EncodedComponent(new byte[2049]));
    }

    @Test
    void aNegativeOrTruncatedComponentIsMalformed() {
        final MsgBuf negative = body(S2CActionResult.MSG_ID);
        negative.writeVarInt(1);
        negative.writeUnsignedByte(0);
        negative.writeVarInt(-1);
        assertMalformed("component length -1", () -> decode(negative));

        final MsgBuf truncated = body(S2CActionResult.MSG_ID);
        truncated.writeVarInt(1);
        truncated.writeUnsignedByte(0);
        truncated.writeVarInt(10);
        truncated.writeBytes(new byte[3]);
        assertMalformed("message is truncated", () -> decode(truncated));
    }

    @Test
    void theSceneNumbersHaveTheirFloors() {
        final MsgBuf readyZero = body(C2SSceneReady.MSG_ID);
        readyZero.writeVarInt(0);
        assertMalformed("scene_seq must be >= 1: 0", () -> decode(readyZero));
        final MsgBuf readyNegative = body(C2SSceneReady.MSG_ID);
        readyNegative.writeVarInt(-1);
        assertMalformed("scene_seq must be >= 1: -1", () -> decode(readyNegative));
        assertMalformed("scene_seq must be >= 1: 0", () -> new S2CScene(0, "x:y", text("t"), 0, 0, List.of()));
        assertEquals(new C2SAction(0, 0, "x:y", new C2SAction.None()),
                roundTrip(new C2SAction(0, 0, "x:y", new C2SAction.None())), "0 is the scene of a connection that has none yet");
        assertMalformed("scene_seq must be >= 0: -1", () -> new C2SAction(-1, 0, "x:y", new C2SAction.None()));
        assertMalformed("action_seq must be >= 0: -1", () -> new C2SAction(0, -1, "x:y", new C2SAction.None()));
        assertMalformed("action_seq must be >= 0: -1", () -> new S2CActionResult(-1, 0, EncodedComponent.NONE, 0));
    }

    @Test
    void theIdsHaveTheirByteBudgets() {
        assertMalformed("scene_id is 0 bytes", () -> new S2CScene(1, "", text("t"), 0, 0, List.of()));
        assertMalformed("action_id is 0 bytes", () -> new C2SAction(1, 1, "", new C2SAction.None()));
        assertMalformed("owner is 0 bytes", () -> new ClearOwner(""));
        assertMalformed("owner is 65 bytes", () -> new RemoveWidget("o".repeat(65), "w"));
        assertMalformed("widget_id is 0 bytes", () -> new RemoveWidget("probe", ""));
        assertMalformed("widget_id is 33 bytes", () -> new RemoveWidget("probe", "w".repeat(33)));
        assertMalformed("button action_id is 0 bytes", () -> new Button("", text("b")));
        assertMalformed("string value is 65 bytes", () -> new PropValue.Str("s".repeat(65)));
        assertMalformed("row value is 33 bytes", () -> new Row(text("r"), "v".repeat(33), false));

        final MsgBuf longId = body(C2SAction.MSG_ID);
        longId.writeVarInt(1);
        longId.writeVarInt(1);
        longId.writeString("a".repeat(65));
        longId.writeUnsignedByte(0);
        assertMalformed("string length 65 is outside [0, 64]", () -> decode(longId));
    }

    @Test
    void aClearOwnerWithAWidgetIdIsMalformed() {
        final MsgBuf buf = body(S2CHud.MSG_ID);
        buf.writeVarInt(1);
        buf.writeUnsignedByte(S2CHud.OP_CLEAR_OWNER);
        buf.writeString("probe");
        buf.writeString("hint");
        assertMalformed("CLEAR_OWNER carries widget_id hint", () -> decode(buf));
        assertEquals("", new ClearOwner("probe").widgetId());
    }

    @Test
    void theActionTableHasSixDistinctSlots() {
        final S2CScene.Action first = new S2CScene.Action(0, "probe:a", text("A"), 0);
        assertMalformed("action slot 0 is taken twice",
                () -> new S2CScene(1, "x:y", text("t"), 0, 0, List.of(first, new S2CScene.Action(0, "probe:b", text("B"), 0))));
        assertMalformed("action slot 6 is outside [0, 5]", () -> new S2CScene.Action(6, "probe:a", text("A"), 0));
        assertMalformed("action slot -1 is outside [0, 5]", () -> new S2CScene.Action(-1, "probe:a", text("A"), 0));
        final List<S2CScene.Action> seven = new ArrayList<>();
        for (int slot = 0; slot < 7; slot++) seven.add(new S2CScene.Action(slot % 6, "probe:a", text("A"), 0));
        assertMalformed("7 actions are over the cap of 6", () -> new S2CScene(1, "x:y", text("t"), 0, 0, seven));
    }

    @Test
    void aCooldownOverTenMinutesIsMalformed() {
        assertMalformed("cooldown_ms 600001 is outside [0, 600000]", () -> new S2CScene.Action(0, "probe:a", text("A"), 600_001));
        assertMalformed("cooldown_ms -1 is outside [0, 600000]", () -> new S2CScene.Action(0, "probe:a", text("A"), -1));
        final MsgBuf buf = body(S2CActionResult.MSG_ID);
        buf.writeVarInt(1);
        buf.writeUnsignedByte(2);
        buf.writeVarInt(0);
        buf.writeVarInt(600_001);
        assertMalformed("cooldown_ms 600001 is outside [0, 600000]", () -> decode(buf));
    }

    @Test
    void theU8FieldsMustFit() {
        assertMalformed("camera is not a u8: 256", () -> new S2CScene(1, "x:y", text("t"), 256, 0, List.of()));
        assertMalformed("cursor is not a u8: -1", () -> new S2CScene(1, "x:y", text("t"), 0, -1, List.of()));
        assertMalformed("code is not a u8: 256", () -> new S2CActionResult(1, 256, EncodedComponent.NONE, 0));
        assertMalformed("kind is not a u8: 256", () -> new Widget(256, Widget.LEFT, 0, List.of()));
    }

    @Test
    void aBoolIsZeroOrOne() {
        final MsgBuf flag = hudWithOneSet(Widget.TIMER, Widget.TOP_CENTER);
        flag.writeVarInt(1);
        flag.writeVarInt(Widget.PROP_RUNNING);
        flag.writeUnsignedByte(PropValue.TYPE_BOOL);
        flag.writeUnsignedByte(2);
        assertMalformed("bool is neither 0 nor 1: 2", () -> decode(flag));

        final MsgBuf highlight = hudWithOneSet(Widget.SCOREBOARD, Widget.TOP_RIGHT);
        highlight.writeVarInt(1);
        highlight.writeVarInt(Widget.PROP_ROWS);
        highlight.writeUnsignedByte(PropValue.TYPE_ROWS);
        highlight.writeVarInt(1);
        text("r").write(highlight);
        highlight.writeString("1");
        highlight.writeUnsignedByte(7);
        assertMalformed("highlight is neither 0 nor 1: 7", () -> decode(highlight));
    }

    @Test
    void aNonFiniteFloatIsMalformed() {
        final MsgBuf nan = hudWithOneSet(3, Widget.BOTTOM);
        nan.writeVarInt(1);
        nan.writeVarInt(4);
        nan.writeUnsignedByte(PropValue.TYPE_F32);
        nan.writeBytes(new byte[]{0x7F, (byte) 0xC0, 0, 0});
        assertMalformed("f32 field is not finite: NaN", () -> decode(nan));
        assertMalformed("f32 value is not finite: Infinity", () -> new PropValue.F32(Float.POSITIVE_INFINITY));
        assertMalformed("vec3f value is not finite", () -> new PropValue.Vec3f(0f, Float.NaN, 0f));
    }

    @Test
    void theRecordsCheckWhatTheDecoderChecks() {
        final List<Prop> seventeen = new ArrayList<>();
        for (int i = 0; i < 17; i++) seventeen.add(new Prop(30 + i, new PropValue.VarInt(i)));
        assertMalformed("17 props are over the cap of 16", () -> new Widget(Widget.TEXT, Widget.LEFT, 0, seventeen));
        final List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 17; i++) rows.add(new Row(text("r"), "", false));
        assertMalformed("17 rows are over the cap of 16", () -> new PropValue.Rows(rows));
        assertMalformed("a PANEL has 13 rows, over the cap of 12", () -> Widget.panel(text("t"), rows.subList(0, 13), List.of()));
        final List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < 5; i++) buttons.add(new Button("probe:b", text("b")));
        assertMalformed("5 buttons are over the cap of 4", () -> new PropValue.Buttons(buttons));
        assertMalformed("a PANEL is on slot 5, not CENTER",
                () -> new Widget(Widget.PANEL, Widget.BOTTOM, 0, List.of(new Prop(Widget.PROP_TEXT, new PropValue.Component(text("t"))))));
        assertMalformed("prop 1 of kind 1 carries value type 4, not 5",
                () -> new Widget(Widget.TEXT, Widget.LEFT, 0, List.of(new Prop(Widget.PROP_TEXT, new PropValue.Str("plain")))));
        final List<S2CHud.Op> ops = new ArrayList<>();
        for (int i = 0; i < 33; i++) ops.add(new RemoveWidget("probe", "w" + i));
        assertMalformed("33 ops are over the cap of 32", () -> new S2CHud(ops));
    }

    /** A truncated widget in the middle of a batch: the count fits in the bytes left, the widget does not. */
    @Test
    void aTruncatedHudIsMalformed() {
        final MsgBuf buf = hudWithOneSet(Widget.TEXT, Widget.LEFT);
        buf.writeVarInt(2);
        buf.writeVarInt(Widget.PROP_TEXT);
        buf.writeUnsignedByte(PropValue.TYPE_COMPONENT);
        assertMalformed("message is truncated", () -> decode(buf));
    }
}
