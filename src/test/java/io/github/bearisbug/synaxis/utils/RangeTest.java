package io.github.bearisbug.synaxis.utils;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RangeTest {

    @Test
    void anInvertedRangeNamesItsBounds() {
        assertEquals("min (5) must not exceed max (3)",
                assertThrows(IllegalArgumentException.class, () -> new Range.Int(5, 3)).getMessage());
        assertEquals("min (2.5) must not exceed max (1.5)",
                assertThrows(IllegalArgumentException.class, () -> new Range.Float(2.5f, 1.5f)).getMessage());
        assertEquals("min (2.5) must not exceed max (1.5)",
                assertThrows(IllegalArgumentException.class, () -> new Range.Double(2.5, 1.5)).getMessage());
    }

    @Test
    void decodingAnInvertedRangeNamesItsBounds() {
        final Result<Range.Int> result = Range.Int.CODEC.decode(Transcoder.NBT,
                CompoundBinaryTag.builder().putInt("min", 5).putInt("max", 3).build());
        final String message = assertInstanceOf(Result.Error.class, result).message();
        assertEquals("IllegalArgumentException: min (5) must not exceed max (3)", message);
    }
}
