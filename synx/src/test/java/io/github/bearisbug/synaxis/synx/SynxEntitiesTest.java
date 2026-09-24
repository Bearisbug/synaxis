package io.github.bearisbug.synaxis.synx;

import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Segment ③ (API-M3-001): its bytes depend on the NBT content only, and a bad one reads as malformed. */
public class SynxEntitiesTest {

    /**
     * {@code "Aa"} and {@code "BB"} share a hash code, so the map behind an adventure compound keeps
     * them in insertion order. Equal content has to encode to equal bytes all the same, at any depth.
     */
    @Test
    public void equalNbtBuiltInAnotherOrderEncodesToTheSameBytes() {
        final CompoundBinaryTag first = compound(false);
        final CompoundBinaryTag second = compound(true);
        assertEquals(first, second);
        assertNotEquals(List.copyOf(first.keySet()), List.copyOf(second.keySet()),
                "the two compounds must iterate differently for this test to mean anything");

        final SynxBlockEntity firstChest = new SynxBlockEntity(0, 1, "", first);
        final SynxBlockEntity secondChest = new SynxBlockEntity(0, 1, "", second);
        final byte[] encoded = SynxEntities.encode(List.of(firstChest), List.of(first));
        assertArrayEquals(encoded, SynxEntities.encode(List.of(secondChest), List.of(second)));

        final SynxEntities.Tables read = SynxEntities.decode(encoded);
        assertEquals(first, read.blockEntities().getFirst().nbt());
        assertEquals(List.of(first), read.staticEntities());
    }

    @Test
    public void malformedTablesAreRefusedAsIllegalArguments() {
        final byte[] entity = SynxEntities.encode(List.of(), List.of(compound(false)));
        final byte[][] malformed = {
                ints(Integer.MAX_VALUE),
                ints(-1),
                ints(0),
                ints(0, 1),
                ints(0, 1, Integer.MAX_VALUE),
                Arrays.copyOf(entity, entity.length - 3),
        };
        for (byte[] raw : malformed) {
            assertThrows(IllegalArgumentException.class, () -> SynxEntities.decode(raw), Arrays.toString(raw));
        }
    }

    /** {@code Aa} holds a compound, {@code BB} a list of it; {@code bbFirst} puts {@code BB} in first, at every level. */
    private static CompoundBinaryTag compound(boolean bbFirst) {
        final CompoundBinaryTag.Builder inner = CompoundBinaryTag.builder();
        final CompoundBinaryTag.Builder outer = CompoundBinaryTag.builder();
        if (bbFirst) inner.put("BB", IntBinaryTag.intBinaryTag(2));
        inner.put("Aa", IntBinaryTag.intBinaryTag(1));
        if (!bbFirst) inner.put("BB", IntBinaryTag.intBinaryTag(2));
        final CompoundBinaryTag nested = inner.build();
        final ListBinaryTag list = ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(nested));
        if (bbFirst) outer.put("BB", list);
        outer.put("Aa", nested);
        if (!bbFirst) outer.put("BB", list);
        return outer.build();
    }

    private static byte[] ints(int... values) {
        final ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }
}
