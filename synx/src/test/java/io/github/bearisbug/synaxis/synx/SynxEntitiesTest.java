package io.github.bearisbug.synaxis.synx;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.testing.Env;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Segment ③ (API-M3-001): its bytes depend on the NBT content only, a bad one reads as malformed, and a
 * shard spawns what it can place of its static entities.
 */
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

    /**
     * {@code docs/DESIGN-M3.md} API-M3-005 静态实体: an entity without a Pos of three numbers or a Rotation
     * of two is skipped with a WARN and the chunk loads; a vehicle spawns without its passengers, with one
     * WARN for its type; the WARN for a type the shard does not spawn names where it was; and a static
     * entity never synchronizes its position on a period.
     */
    @Test
    public void staticEntitiesAShardCannotPlaceAreSkippedWithAWarn() {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            final int chunkX = 10, chunkZ = 5;
            final double x = chunkX * 16 + 8.5, y = SynxTestMap.FEATURE_Y, z = chunkZ * 16 + 8.5;
            final ListBinaryTag passengers = ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND,
                    List.of(SynxTestMap.entity("minecraft:text_display", x, y + 1, z, 0f, 0f).build()));
            final List<CompoundBinaryTag> statics = List.of(
                    SynxTestMap.entity("minecraft:text_display", x - 6, y, z, 0f, 0f).build(),
                    SynxTestMap.entity("minecraft:item_frame", x - 4, y, z, 0f, 0f).build().remove("Rotation"),
                    SynxTestMap.entity("minecraft:armor_stand", x - 2, y, z, 0f, 0f).put("Pos", ListBinaryTag.listBinaryTag(
                            BinaryTagTypes.DOUBLE, List.of(DoubleBinaryTag.doubleBinaryTag(x - 2), DoubleBinaryTag.doubleBinaryTag(y))))
                            .build(),
                    SynxTestMap.entity("minecraft:block_display", x, y, z, 0f, 0f).put("Passengers", passengers).build(),
                    SynxTestMap.entity("minecraft:block_display", x + 2, y, z, 0f, 0f).put("Passengers", passengers).build(),
                    SynxTestMap.minecart(x + 4, y, z));
            final SynxChunkRecord plain = SynxTestMap.get().record(chunkX, chunkZ);
            final MemoryOverlay overlay = new MemoryOverlay();
            overlay.put(MemoryOverlay.hit(new SynxChunkRecord(chunkX, chunkZ, plain.packet(), plain.navigation(),
                    plain.blockEntities(), statics, plain.columns()), 0, 0, 256));
            final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, overlay));
            final SynxChunk chunk;
            final List<String> warnings;
            try (LogCapture logs = LogCapture.of(SynxStaticEntities.class)) {
                chunk = (SynxChunk) instance.loadChunk(chunkX, chunkZ).join();
                warnings = logs.messagesAt(Level.WARN);
            }
            assertEquals(SynxChunk.Mode.HANDLE, chunk.mode());
            final Set<Entity> entities = instance.getChunkEntities(chunk);
            assertEquals(List.of("minecraft:block_display", "minecraft:block_display", "minecraft:text_display"),
                    entities.stream().map(entity -> entity.getEntityType().key().asString()).sorted().toList());
            for (Entity entity : entities) assertEquals(Integer.MAX_VALUE, entity.getSynchronizationTicks());
            assertEquals(4, warnings.size(), warnings.toString());
            assertEquals("Static entity minecraft:item_frame has no Pos of 3 or Rotation of 2 numbers, skipped", warnings.get(0));
            assertEquals("Static entity minecraft:armor_stand has no Pos of 3 or Rotation of 2 numbers, skipped", warnings.get(1));
            assertTrue(warnings.get(2).startsWith("Static entity minecraft:block_display at " + new Pos(x, y, z, 0f, 0f)
                    + " carries passengers"), warnings.get(2));
            assertTrue(warnings.get(3).startsWith("Static entity type minecraft:minecart at " + new Pos(x + 4, y, z, 0f, 0f)
                    + " is not one the shard spawns"), warnings.get(3));
        } finally {
            env.process().stop();
        }
    }

    /**
     * {@code docs/DESIGN-M3.md} API-M3-005 静态实体: an entity whose Pos lies in another chunk than the one
     * that holds it is skipped with a WARN; spawned there, it would not go with its chunk and would come
     * again on every reload.
     */
    @Test
    public void aStaticEntityOutsideTheChunkThatHoldsItIsSkippedWithAWarn() {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            final int chunkX = 10, chunkZ = 5;
            final double x = chunkX * 16 + 8.5, y = SynxTestMap.FEATURE_Y, z = chunkZ * 16 + 8.5;
            final List<CompoundBinaryTag> statics = List.of(
                    SynxTestMap.entity("minecraft:text_display", x, y, z, 0f, 0f).build(),
                    SynxTestMap.entity("minecraft:text_display", x + 16, y, z, 0f, 0f).build());
            final SynxChunkRecord plain = SynxTestMap.get().record(chunkX, chunkZ);
            final MemoryOverlay overlay = new MemoryOverlay();
            overlay.put(MemoryOverlay.hit(new SynxChunkRecord(chunkX, chunkZ, plain.packet(), plain.navigation(),
                    plain.blockEntities(), statics, plain.columns()), 0, 0, 256));
            final Instance instance = SynxWorlds.instance(env.process(), SynxWorlds.loader(SynxWorlds.WRITABLE, overlay));
            final SynxChunk chunk;
            final List<String> warnings;
            try (LogCapture logs = LogCapture.of(SynxStaticEntities.class)) {
                chunk = (SynxChunk) instance.loadChunk(chunkX, chunkZ).join();
                warnings = logs.messagesAt(Level.WARN);
            }
            final Set<Entity> entities = instance.getChunkEntities(chunk);
            assertEquals(1, entities.size(), entities.toString());
            assertEquals(new Pos(x, y, z, 0f, 0f), entities.iterator().next().getPosition());
            assertEquals(List.of("Static entity minecraft:text_display at " + new Pos(x + 16, y, z, 0f, 0f)
                    + " lies outside chunk 10,5 that holds it, skipped"), warnings);
            assertEquals(1, instance.getEntities().size(), "nothing spawned into 11,5 either");
        } finally {
            env.process().stop();
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
