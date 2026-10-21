package io.github.bearisbug.synaxis.utils;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import io.github.bearisbug.synaxis.codec.Codec;
import io.github.bearisbug.synaxis.codec.Result;
import io.github.bearisbug.synaxis.codec.Transcoder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WeightedListTest {

    @Test
    void aListWithoutWeightPicksNothing() {
        assertNull(WeightedList.of().pick(new Random(1)));
        assertNull(WeightedList.of(new WeightedList.Entry<>("a", 0)).pick(new Random(1)));
        assertEquals("WeightedList has no entry with positive weight", assertThrows(NullPointerException.class,
                () -> WeightedList.<String>of().pickOrThrow(new Random(1))).getMessage());
    }

    @Test
    void entriesArePickedByWeight() {
        final WeightedList<String> list = WeightedList.of(new WeightedList.Entry<>("a", 0), new WeightedList.Entry<>("b", 3));
        assertEquals("b", list.pickOrThrow(new Random(1)));
    }

    @Test
    void aNegativeWeightIsRejected() {
        final List<WeightedList.Entry<String>> entries = List.of(new WeightedList.Entry<>("a", 1), new WeightedList.Entry<>("b", -1));
        assertEquals("weight of entry 1 is negative: -1",
                assertThrows(IllegalArgumentException.class, () -> new WeightedList<>(entries)).getMessage());
    }

    @Test
    void aTotalWeightBeyondAnIntIsRejected() {
        final List<WeightedList.Entry<String>> entries = List.of(
                new WeightedList.Entry<>("a", Integer.MAX_VALUE), new WeightedList.Entry<>("b", 1));
        assertEquals("total weight overflows int",
                assertThrows(IllegalArgumentException.class, () -> new WeightedList<>(entries)).getMessage());
    }

    @Test
    void decodingANegativeWeightIsAnError() {
        final ListBinaryTag input = ListBinaryTag.from(List.of(
                CompoundBinaryTag.builder().putInt("data", 1).putInt("weight", -1).build()));
        final Result<WeightedList<Integer>> result = WeightedList.codec(Codec.INT).decode(Transcoder.NBT, input);
        assertEquals("IllegalArgumentException: weight of entry 0 is negative: -1",
                assertInstanceOf(Result.Error.class, result).message());
    }
}
