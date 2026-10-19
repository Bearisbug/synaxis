package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.baker.BakeSource;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.SourceChunk;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.testing.Env;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Segment ③ and the NBT it holds against the caps of M3-S ({@code docs/DESIGN-M3.md} {@code API-M3-001}
 * 长度上限): what a vanilla save holds reads back, and what would not read back is not written.
 */
public class SynxBlockEntityLimitsTest {

    private static final SynxManifest.Bounds BOUNDS = new SynxManifest.Bounds(0, 0, 1, 1);
    private static final int CHEST_X = 8, CHEST_Y = 41, CHEST_Z = 8;

    /** A chest of 27 written books of 30 pages of 256 characters: over 200 KB of NBT, over 131,082 as adventure-nbt counts. */
    static CompoundBinaryTag chestOfBooks() {
        final List<CompoundBinaryTag> items = new ArrayList<>();
        for (int slot = 0; slot < 27; slot++) {
            final List<CompoundBinaryTag> pages = new ArrayList<>();
            for (int page = 0; page < 30; page++) {
                final String text = (slot + "/" + page + " ").repeat(64).substring(0, 256);
                pages.add(CompoundBinaryTag.builder().putString("raw", text).build());
            }
            final CompoundBinaryTag content = CompoundBinaryTag.builder()
                    .put("title", CompoundBinaryTag.builder().putString("raw", "Book " + slot).build())
                    .putString("author", "Synaxis")
                    .put("pages", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.copyOf(pages)))
                    .build();
            items.add(CompoundBinaryTag.builder().putByte("Slot", (byte) slot).putString("id", "minecraft:written_book")
                    .putInt("count", 1)
                    .put("components", CompoundBinaryTag.builder().put("minecraft:written_book_content", content).build())
                    .build());
        }
        return CompoundBinaryTag.builder().put("Items", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND,
                List.copyOf(items))).build();
    }

    /** Stone to y 40 on 2 × 2 chunks, the chest of books on top in chunk 0,0. */
    private static final class BooksSource implements BakeSource {
        private final GeneratorSource terrain = new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, CHEST_Y, Block.STONE), 0, SynxTestMap.HEIGHT, BOUNDS), Registries.vanilla());

        @Override
        public SourceChunk read(int chunkX, int chunkZ) {
            final SourceChunk chunk = terrain.read(chunkX, chunkZ);
            if (chunkX != 0 || chunkZ != 0) return chunk;
            final Palette section = chunk.blocks()[CHEST_Y >> 4];
            section.set(CHEST_X, CHEST_Y & 15, CHEST_Z, Block.CHEST.stateId());
            final List<SynxBlockEntity> blockEntities = new ArrayList<>(chunk.blockEntities());
            blockEntities.add(new SynxBlockEntity(CHEST_Y >> 4, SynxBlockEntity.index(CHEST_X, CHEST_Y, CHEST_Z), "",
                    chestOfBooks().putString("id", "minecraft:chest")));
            return new SourceChunk(chunkX, chunkZ, chunk.blocks(), chunk.biomes(), blockEntities, chunk.entities(),
                    chunk.lightOn(), chunk.sourceSky(), chunk.sourceBlock(), chunk.markerCells());
        }

        @Override
        public String kind() {
            return terrain.kind();
        }

        @Override
        public int minY() {
            return terrain.minY();
        }

        @Override
        public int height() {
            return terrain.height();
        }

        @Override
        public SynxManifest.Bounds bounds() {
            return BOUNDS;
        }

        @Override
        public @Nullable Integer dataVersion() {
            return null;
        }

        @Override
        public @Nullable Double lightCoverage() {
            return null;
        }

        @Override
        public void checkGate(int threads) {
        }

        @Override
        public void close() {
        }
    }

    private record Mounted(OverlayStore store, SynxChunkLoader loader, Instance instance) {
    }

    private static Mounted mount(Env env, Baker.Result baked, Path overlay) throws Exception {
        final SynxManifest manifest = Objects.requireNonNull(baked.manifest());
        final Path buildDir = Objects.requireNonNull(baked.directory());
        final OverlayStore store = OverlayStore.open(overlay, manifest, new SynxSettings(manifest.protocolVersion(),
                Baker.COMPRESSION_THRESHOLD, Baker.COMPRESSION_LEVEL, manifest.world().minY(), manifest.world().height(),
                manifest.registryDigest(), manifest.lightEngineVersion(), manifest.bakerVersion(), false),
                new OverlayOptions(16, 10));
        final SynxChunkLoader loader = new SynxChunkLoader(SynxWorlds.mapped(buildDir, manifest, BOUNDS), BOUNDS,
                SynxWorlds.WRITABLE, store, store);
        return new Mounted(store, loader, SynxWorlds.instance(env.process(), loader));
    }

    /** §19 「装满成书的箱子照常往返」. */
    @Test
    public void aChestFullOfWrittenBooksRoundTrips(@TempDir Path dir) throws Exception {
        final Env env = Env.create(UnaryOperator.identity());
        try {
            roundTrip(env, dir);
        } finally {
            env.close();
        }
    }

    private static void roundTrip(Env env, Path dir) throws Exception {
        final Baker.Result baked = Baker.bake(new Baker.Request("books", new BooksSource(), dir.resolve("maps"), 1,
                dir.resolve("logs"), Registries.vanilla(), _ -> null, Set.of(LightEngine.VERSION), 1));
        assertEquals(Baker.EXIT_PUBLISHED, baked.exitCode(), baked.message());
        final Block expected = Block.CHEST.withNbt(chestOfBooks());

        final Mounted first = mount(env, baked, dir.resolve("overlay"));
        final SynxChunk chunk = (SynxChunk) first.instance().loadChunk(0, 0).join();
        assertEquals(SynxChunk.Mode.HANDLE, chunk.mode(), "not failed");
        assertTrue(SynxChunk.sameBlock(expected, first.instance().getBlock(CHEST_X, CHEST_Y, CHEST_Z)), "the chest as baked");
        first.instance().setBlock(1, CHEST_Y, 1, Block.GOLD_BLOCK);
        assertEquals(SynxChunk.Mode.OVERLAY, chunk.mode(), "materialized");
        first.store().finalFlush(Duration.ofSeconds(2), false).get(5, TimeUnit.SECONDS);
        assertEquals(0, first.loader().stats().failed());

        final Mounted second = mount(env, baked, dir.resolve("overlay"));
        assertNotNull(second.store().find(0, 0), "the chunk is in the overlay");
        final SynxChunk again = (SynxChunk) second.instance().loadChunk(0, 0).join();
        assertEquals(SynxChunk.Mode.HANDLE, again.mode(), "from the overlay record, not failed");
        assertEquals(Block.GOLD_BLOCK, second.instance().getBlock(1, CHEST_Y, 1));
        assertTrue(SynxChunk.sameBlock(expected, second.instance().getBlock(CHEST_X, CHEST_Y, CHEST_Z)),
                "the chest after the overlay and a restart");
        assertEquals(0, second.loader().stats().failed());
    }

    /**
     * §19 「读不回的段③ 写不进区域文件」: a block entity with 160 strings of 60,000 characters, about 9.6 MB
     * of segment ③, within its 16 MiB, but about 19.2 MB as adventure-nbt counts: the writer refuses it,
     * naming the reason adventure-nbt gives and where the block entity is in its chunk, so the baker fails
     * the region instead of writing a chunk a shard would not read.
     */
    @Test
    public void aSegmentThreeThatDoesNotReadBackIsNotWritten(@TempDir Path dir) {
        final List<StringBinaryTag> lines = new ArrayList<>();
        for (int i = 0; i < 160; i++) lines.add(StringBinaryTag.stringBinaryTag(String.valueOf((char) ('a' + i % 26)).repeat(60_000)));
        final CompoundBinaryTag nbt = CompoundBinaryTag.builder().putString("id", "minecraft:chest")
                .put("Lines", ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING, List.copyOf(lines))).build();
        final SynxChunkRecord flat = SynxVectors.vector("one-chunk-flat").chunks().getFirst().toRecord(SynxVectors.settings());
        final SynxChunkRecord record = new SynxChunkRecord(flat.chunkX(), flat.chunkZ(), flat.packet(), flat.navigation(),
                List.of(new SynxBlockEntity(4, SynxBlockEntity.index(1, 64, 1), "", nbt)), List.of(), flat.columns());
        final SynxRegionWriter writer = new SynxRegionWriter(0, 0, SynxVectors.settings());
        writer.add(record);
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> writer.encode(dir.resolve("region.part")));
        assertTrue(refused.getMessage().startsWith("chunk " + flat.chunkX() + "," + flat.chunkZ() + ": ")
                && refused.getMessage().contains("segment ③ does not read back: unreadable NBT in segment ③: "
                + "The read NBT was longer than the maximum allowed size of 16777216 bytes!")
                && refused.getMessage().endsWith(" (block entity at x 1, y 64, z 1 of the chunk)"), refused.getMessage());
    }
}
