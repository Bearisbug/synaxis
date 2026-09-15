package dev.gameengine.network;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import dev.gameengine.JvmFlags;
import dev.gameengine.item.component.CustomData;
import dev.gameengine.network.packet.PacketReading;
import dev.gameengine.network.packet.PacketVanilla;
import dev.gameengine.network.packet.client.common.ClientCustomClickActionPacket;
import org.junit.jupiter.api.Test;

import static dev.gameengine.network.BinaryTagTypeImpl.TAG_BYTE_ARRAY;
import static dev.gameengine.network.BinaryTagTypeImpl.TAG_COMPOUND;
import static dev.gameengine.network.BinaryTagTypeImpl.TAG_END;
import static dev.gameengine.network.BinaryTagTypeImpl.TAG_LIST;
import static dev.gameengine.network.NetworkBuffer.BYTE;
import static dev.gameengine.network.NetworkBuffer.INT;
import static dev.gameengine.network.NetworkBuffer.KEY;
import static dev.gameengine.network.NetworkBuffer.NBT;
import static dev.gameengine.network.NetworkBuffer.NBT_COMPOUND;
import static dev.gameengine.network.NetworkBuffer.RAW_BYTES;
import static dev.gameengine.network.NetworkBuffer.STRING_IO_UTF8;
import static dev.gameengine.network.NetworkBuffer.UNTRUSTED_NBT;
import static dev.gameengine.network.NetworkBuffer.UNTRUSTED_NBT_COMPOUND;
import static dev.gameengine.network.NetworkBuffer.VAR_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An encoded binary tag is bounded by the packet it arrives in, but it does not decode to anything
 * like its encoded size: a list of empty compounds costs one byte each on the wire and about fifty
 * in objects. Client provided reads are charged for that expansion, server side reads are not.
 */
class UntrustedBinaryTagTest {

    /** Approximate decoded cost of one empty compound inside a list, see BinaryTagTypeImpl.ReadLimiter. */
    private static final int COST_PER_EMPTY_COMPOUND = 52;
    private static final int OVER_BUDGET_ENTRIES = JvmFlags.NBT_MAX_BYTES / COST_PER_EMPTY_COMPOUND + 1_000;
    private static final int UNDER_BUDGET_ENTRIES = JvmFlags.NBT_MAX_BYTES / COST_PER_EMPTY_COMPOUND / 2;

    /** A list of {@code entries} empty compounds, one encoded byte per entry. */
    private static void writeEmptyCompoundList(NetworkBuffer buffer, int entries) {
        buffer.write(BYTE, TAG_LIST);
        buffer.write(BYTE, TAG_COMPOUND);
        buffer.write(INT, entries);
        buffer.write(RAW_BYTES, new byte[entries]); // Each compound is a lone TAG_End
    }

    private static byte[] emptyCompoundList(int entries) {
        return NetworkBuffer.makeArray(buffer -> writeEmptyCompoundList(buffer, entries));
    }

    /** The same list, wrapped in a compound so it can be read as one. */
    private static byte[] compoundOfEmptyCompounds(int entries) {
        return NetworkBuffer.makeArray(buffer -> {
            buffer.write(BYTE, TAG_COMPOUND);
            buffer.write(BYTE, TAG_LIST);
            buffer.write(STRING_IO_UTF8, "a");
            buffer.write(BYTE, TAG_COMPOUND);
            buffer.write(INT, entries);
            buffer.write(RAW_BYTES, new byte[entries]);
            buffer.write(BYTE, TAG_END);
        });
    }

    private static NetworkBuffer wrap(byte[] bytes) {
        return NetworkBuffer.wrap(bytes, 0, bytes.length);
    }

    @Test
    void expansionBeyondTheBudgetIsRejected() {
        final byte[] bytes = emptyCompoundList(OVER_BUDGET_ENTRIES);
        assertTrue(bytes.length < JvmFlags.MAX_PACKET_SIZE,
                "the encoded tag fits in one packet despite blowing the decode budget");

        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> wrap(bytes).read(UNTRUSTED_NBT));
        assertTrue(error.getMessage().contains("NBT is too large"), error::getMessage);
    }

    @Test
    void expansionWithinTheBudgetIsAccepted() {
        final byte[] bytes = emptyCompoundList(UNDER_BUDGET_ENTRIES);
        assertEquals(UNDER_BUDGET_ENTRIES, ((ListBinaryTag) wrap(bytes).read(UNTRUSTED_NBT)).size());
    }

    @Test
    void serverSideReadsAreNotCharged() {
        final byte[] bytes = emptyCompoundList(OVER_BUDGET_ENTRIES);
        assertEquals(OVER_BUDGET_ENTRIES, ((ListBinaryTag) wrap(bytes).read(NBT)).size());
    }

    @Test
    void largeArraysAreChargedForTheirElements() {
        final CompoundBinaryTag tag = CompoundBinaryTag.builder()
                .putByteArray("data", new byte[JvmFlags.NBT_MAX_BYTES + 1_000])
                .build();
        final byte[] bytes = NetworkBuffer.makeArray(NBT_COMPOUND, tag);

        assertEquals(tag, wrap(bytes).read(NBT_COMPOUND));
        assertThrows(IllegalArgumentException.class, () -> wrap(bytes).read(UNTRUSTED_NBT_COMPOUND));
    }

    @Test
    void negativeSizesAreRejected() {
        final byte[] list = NetworkBuffer.makeArray(buffer -> {
            buffer.write(BYTE, TAG_LIST);
            buffer.write(BYTE, TAG_COMPOUND);
            buffer.write(INT, -1);
        });
        assertThrows(IllegalArgumentException.class, () -> wrap(list).read(NBT));

        final byte[] array = NetworkBuffer.makeArray(buffer -> {
            buffer.write(BYTE, TAG_BYTE_ARRAY);
            buffer.write(INT, -1);
        });
        assertThrows(IllegalArgumentException.class, () -> wrap(array).read(NBT));
    }

    /** The custom data component is written by the client, so it must carry the limit into the item path. */
    @Test
    void customDataIsLimited() {
        final byte[] bomb = compoundOfEmptyCompounds(OVER_BUDGET_ENTRIES);
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> wrap(bomb).read(CustomData.NETWORK_TYPE));
        assertTrue(error.getMessage().contains("NBT is too large"), error::getMessage);

        final byte[] small = compoundOfEmptyCompounds(UNDER_BUDGET_ENTRIES);
        assertEquals(UNDER_BUDGET_ENTRIES, wrap(small).read(CustomData.NETWORK_TYPE).nbt().getList("a").size());
    }

    /** The custom click action payload is the one client packet carrying raw binary tags. */
    @Test
    void customClickActionPayloadIsLimited() {
        final byte[] payload = emptyCompoundList(OVER_BUDGET_ENTRIES);
        assertTrue(payload.length <= 65_536, "still within the packet's own length prefix limit");

        final int packetId = PacketVanilla.CLIENT_PACKET_PARSER.play()
                .packetInfo(ClientCustomClickActionPacket.class).id();
        final byte[] frame = NetworkBuffer.makeArray(buffer -> {
            buffer.write(VAR_INT, packetId);
            buffer.write(KEY, Key.key("game", "probe"));
            buffer.write(VAR_INT, payload.length);
            buffer.write(RAW_BYTES, payload);
        });
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(frame.length + 8);
        buffer.write(VAR_INT, frame.length);
        buffer.write(RAW_BYTES, frame);

        assertThrows(RuntimeException.class,
                () -> PacketReading.readClients(buffer, ConnectionState.PLAY, false));
    }

    /** A tag the server writes still reads back unchanged through the client facing type. */
    @Test
    void ordinaryPayloadsStillRoundTrip() {
        final ClientCustomClickActionPacket packet = new ClientCustomClickActionPacket(
                Key.key("game", "probe"), ListBinaryTag.empty());
        final byte[] bytes = NetworkBuffer.makeArray(ClientCustomClickActionPacket.SERIALIZER, packet);
        assertEquals(packet, wrap(bytes).read(ClientCustomClickActionPacket.SERIALIZER));
    }
}
