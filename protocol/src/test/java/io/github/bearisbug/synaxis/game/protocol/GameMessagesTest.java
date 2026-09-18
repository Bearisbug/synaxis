package io.github.bearisbug.synaxis.game.protocol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The game channel envelope of {@code API-M2-001} and the decoder behaviour of
 * {@code docs/TEST.md} TC-GAME-018: trailing unknown bytes are skipped, an unknown {@code msg_id}
 * is reported as such so the host can drop and warn, and every other malformed shape is a
 * {@link MalformedMessageException} that the host maps to {@code synaxis:protocol-error}.
 */
class GameMessagesTest {

    private static MsgBuf body(int msgId) {
        final MsgBuf buf = MsgBuf.writing();
        buf.writeVarInt(msgId);
        return buf;
    }

    private static GameMessage roundTrip(GameMessage message) {
        final MsgBuf writer = MsgBuf.writing();
        message.write(writer);
        return GameMessages.read(MsgBuf.reading(writer.toByteArray()));
    }

    @Test
    void everySliceMessageRoundTrips() {
        final List<GameMessage> messages = List.of(
                new C2SCamera(1.5f, -2.5f, 24f, 90f),
                new C2SSelect(List.of(1, 2)),
                new C2SMoveOrder(3, 3, 1, 10.5f, 64f, -8.25f, 0),
                new S2CUnitSpawn(1, 42, 0, 1, 100, 100, S2CUnitSpawn.FLAG_COMMANDABLE),
                new S2CUnitState(7, List.of(new S2CUnitState.Entry(1, UnitState.MOVING, 80))),
                new S2CUnitDespawn(List.of(1, 2, 3)),
                new S2COrderResult(3, OrderResultCode.OK, 10.5f, 64f, -8.25f));
        for (final GameMessage message : messages) assertEquals(message, roundTrip(message));
    }

    @Test
    void trailingUnknownBytesAreSkipped() {
        final MsgBuf writer = MsgBuf.writing();
        new C2SCamera(1.5f, -2.5f, 24f, 90f).write(writer);
        writer.writeBytes(new byte[8]);

        final MsgBuf reader = MsgBuf.reading(writer.toByteArray());
        assertEquals(new C2SCamera(1.5f, -2.5f, 24f, 90f), GameMessages.read(reader));
        assertEquals(8, reader.remaining());
    }

    @Test
    void anUnknownMsgIdIsReportedWithoutBeingMalformed() {
        final UnknownMessageException thrown = assertThrows(UnknownMessageException.class,
                () -> GameMessages.read(MsgBuf.reading(body(0x7E).toByteArray())));
        assertEquals(0x7E, thrown.msgId());
    }

    @Test
    void aTruncatedSelectIsMalformed() {
        final MsgBuf buf = body(C2SSelect.MSG_ID);
        buf.writeVarInt(8);
        buf.writeVarInt(1);
        buf.writeVarInt(2);
        assertThrows(MalformedMessageException.class, () -> GameMessages.read(MsgBuf.reading(buf.toByteArray())));
    }

    @Test
    void aSelectOverEightUnitsIsMalformed() {
        final MsgBuf buf = body(C2SSelect.MSG_ID);
        buf.writeVarInt(9);
        for (int i = 0; i < 9; i++) buf.writeVarInt(i + 1);
        assertThrows(MalformedMessageException.class, () -> GameMessages.read(MsgBuf.reading(buf.toByteArray())));
        assertThrows(MalformedMessageException.class,
                () -> new C2SSelect(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9)));
    }

    @Test
    void aSnapshotOverFiveHundredAndTwelveEntriesIsMalformed() {
        final MsgBuf buf = body(S2CUnitState.MSG_ID);
        buf.writeVarInt(1);
        buf.writeVarInt(513);
        for (int i = 0; i < 513; i++) {
            buf.writeVarInt(i + 1);
            buf.writeUnsignedByte(0);
            buf.writeVarInt(100);
        }
        assertThrows(MalformedMessageException.class, () -> GameMessages.read(MsgBuf.reading(buf.toByteArray())));
    }

    @Test
    void aNegativeCountIsMalformed() {
        final MsgBuf buf = body(S2CUnitDespawn.MSG_ID);
        buf.writeVarInt(-1);
        assertThrows(MalformedMessageException.class, () -> GameMessages.read(MsgBuf.reading(buf.toByteArray())));
    }

    @Test
    void anUnknownUnitStateIsMalformed() {
        final MsgBuf buf = body(S2CUnitState.MSG_ID);
        buf.writeVarInt(1);
        buf.writeVarInt(1);
        buf.writeVarInt(1);
        buf.writeUnsignedByte(2);
        buf.writeVarInt(100);
        assertThrows(MalformedMessageException.class, () -> GameMessages.read(MsgBuf.reading(buf.toByteArray())));
    }

    @Test
    void anUnknownOrderResultCodeIsMalformed() {
        final MsgBuf buf = body(S2COrderResult.MSG_ID);
        buf.writeVarInt(1);
        buf.writeUnsignedByte(7);
        buf.writeFloat(0f);
        buf.writeFloat(0f);
        buf.writeFloat(0f);
        assertThrows(MalformedMessageException.class, () -> GameMessages.read(MsgBuf.reading(buf.toByteArray())));
    }

    @Test
    void aFieldOutsideItsRangeIsMalformed() {
        assertThrows(MalformedMessageException.class, () -> new S2CUnitSpawn(0, 1, 0, 1, 1, 1, 0));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitSpawn(1, -1, 0, 1, 1, 1, 0));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitSpawn(1, 1, -1, 1, 1, 1, 0));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitSpawn(1, 1, 0, 256, 1, 1, 0));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitSpawn(1, 1, 0, 1, -1, 100, 0));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitSpawn(1, 1, 0, 1, 1, -1, 0));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitSpawn(1, 1, 0, 1, 1, 1, 256));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitState.Entry(0, UnitState.IDLE, 1));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitState.Entry(1, null, 1));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitState.Entry(1, UnitState.IDLE, -1));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitState(-1, List.of()));
        assertThrows(MalformedMessageException.class, () -> new C2SMoveOrder(-1, 0, 1, 0f, 0f, 0f, 0));
        assertThrows(MalformedMessageException.class, () -> new C2SMoveOrder(0, -1, 1, 0f, 0f, 0f, 0));
        assertThrows(MalformedMessageException.class, () -> new C2SMoveOrder(0, 0, 0, 0f, 0f, 0f, 0));
        assertThrows(MalformedMessageException.class, () -> new C2SMoveOrder(0, 0, 1, 0f, 0f, 0f, 256));
        assertThrows(MalformedMessageException.class, () -> new S2COrderResult(-1, OrderResultCode.OK, 0f, 0f, 0f));
        assertThrows(MalformedMessageException.class, () -> new S2COrderResult(0, null, 0f, 0f, 0f));
        assertThrows(MalformedMessageException.class, () -> new C2SSelect(List.of(0)));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitDespawn(List.of(0)));
    }

    @Test
    void aListOverItsCapIsMalformedOnTheEncodeSideToo() {
        final List<Integer> tooMany = new ArrayList<>();
        for (int i = 0; i < S2CUnitState.MAX_ENTRIES + 1; i++) tooMany.add(i + 1);
        assertThrows(MalformedMessageException.class, () -> new S2CUnitDespawn(tooMany));

        final List<S2CUnitState.Entry> entries = new ArrayList<>();
        for (final int unitId : tooMany) entries.add(new S2CUnitState.Entry(unitId, UnitState.IDLE, 1));
        assertThrows(MalformedMessageException.class, () -> new S2CUnitState(1, entries));
    }

    @Test
    void aNonFiniteCoordinateIsMalformed() {
        final MsgBuf buf = body(C2SCamera.MSG_ID);
        buf.writeBytes(new byte[]{0x7F, (byte) 0xC0, 0, 0}); // NaN
        buf.writeFloat(0f);
        buf.writeFloat(0f);
        buf.writeFloat(0f);
        assertThrows(MalformedMessageException.class, () -> GameMessages.read(MsgBuf.reading(buf.toByteArray())));
    }

    @Test
    void anEmptyPayloadIsMalformed() {
        assertThrows(MalformedMessageException.class, () -> GameMessages.read(MsgBuf.reading(new byte[0])));
    }
}
