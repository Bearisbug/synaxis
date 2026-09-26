package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.baker.BakeSource;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.SourceChunk;
import io.github.bearisbug.synaxis.baker.SyntheticTerrain;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.tag.Tag;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.FloatBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The test map of the {@code :synx} integration tests ({@code docs/DESIGN-M3.md} §19): the synthetic
 * terrain of the baker, seed 7, baked by the real {@link Baker} over chunks 0..63 × 0..31, two region
 * files. The region of the tests is chunks 0..31 × 0..31 ({@link #REGION}); the second region file
 * holds the neighbour region. A few chunks carry what the tests need on top of the terrain, all of it
 * at y {@link #FEATURE_Y}, far above the highest column. Baked once per JVM into a temporary
 * directory that goes when the JVM ends.
 */
final class SynxTestMap {
    static final Registries REGISTRIES = Registries.vanilla();
    static final int HEIGHT = SyntheticTerrain.HEIGHT;
    static final SynxManifest.Bounds BAKED = new SynxManifest.Bounds(0, 0, 63, 31);
    /** The region the loader serves in the tests, {@code bounds} of API-M3-005. */
    static final SynxManifest.Bounds REGION = new SynxManifest.Bounds(0, 0, 31, 31);
    static final int FEATURE_Y = 200;
    /**
     * The y of the cave air every chunk has in its corner column, void air one block east of it: the
     * client and the baker count both as air, the engine's palette count does not, and real maps have
     * cave air in every cave.
     */
    static final int OTHER_AIR_Y = FEATURE_Y + 4;

    /** An oak sign, source NBT like a 26.2 save writes it, first front line {@code "Synaxis"}. */
    static final int SIGN_CHUNK_X = 5, SIGN_CHUNK_Z = 5;
    static final int SIGN_X = SIGN_CHUNK_X * 16 + 3, SIGN_Z = SIGN_CHUNK_Z * 16 + 3;
    /** A chest whose handler namespace the test process does not register. */
    static final int UNKNOWN_CHUNK_X = 6, UNKNOWN_CHUNK_Z = 5;
    static final int UNKNOWN_X = UNKNOWN_CHUNK_X * 16 + 4, UNKNOWN_Z = UNKNOWN_CHUNK_Z * 16 + 4;
    static final String UNKNOWN_HANDLER = "synaxis:unknown";
    /** A stone block with a ticking handler, inside the region. */
    static final int TICKER_CHUNK_X = 7, TICKER_CHUNK_Z = 5;
    static final int TICKER_X = TICKER_CHUNK_X * 16 + 5, TICKER_Z = TICKER_CHUNK_Z * 16 + 5;
    static final String TICKER_HANDLER = "synaxis:ticker";
    /** An item frame and a minecart, a type static entities do not cover. */
    static final int STATICS_CHUNK_X = 8, STATICS_CHUNK_Z = 5;
    /** One entity of each covered type, with every mapped field set. */
    static final int MAPPED_CHUNK_X = 9, MAPPED_CHUNK_Z = 5;
    /** 10 chunks east of the region: a static entity and a ticking handler block. */
    static final int NEIGHBOUR_CHUNK_X = 41, NEIGHBOUR_CHUNK_Z = 5;
    static final int NEIGHBOUR_TICKER_X = NEIGHBOUR_CHUNK_X * 16 + 5, NEIGHBOUR_TICKER_Z = NEIGHBOUR_CHUNK_Z * 16 + 5;

    private static volatile @Nullable SynxTestMap map;

    final Path buildDir;
    final SynxManifest manifest;

    private SynxTestMap(Path buildDir, SynxManifest manifest) {
        this.buildDir = buildDir;
        this.manifest = manifest;
    }

    /** The map, baked on first use. */
    static SynxTestMap get() {
        SynxTestMap current = map;
        if (current != null) return current;
        synchronized (SynxTestMap.class) {
            current = map;
            if (current == null) map = current = bake();
            return current;
        }
    }

    private static SynxTestMap bake() {
        try {
            final Path root = Files.createTempDirectory("synx-test-map");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(root)));
            final GeneratorSource terrain = new GeneratorSource(
                    new GeneratorPreset(new SyntheticTerrain(7), 0, HEIGHT, BAKED), REGISTRIES);
            final Map<String, BlockHandler> handlers = Map.of(
                    UNKNOWN_HANDLER, new BakeHandler(Key.key(UNKNOWN_HANDLER), List.of()),
                    TICKER_HANDLER, new BakeHandler(Key.key(TICKER_HANDLER), List.of()));
            final Baker.Result result = Baker.bake(new Baker.Request("synx-test", new FeatureSource(terrain),
                    root.resolve("maps"), 4, root.resolve("logs"), REGISTRIES, handlers::get,
                    Set.of(LightEngine.VERSION), 1));
            if (result.exitCode() != Baker.EXIT_PUBLISHED || result.directory() == null || result.manifest() == null) {
                throw new IllegalStateException("the test map did not bake: " + result.exitCode() + " " + result.message());
            }
            return new SynxTestMap(result.directory(), result.manifest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    SynxSettings settings() {
        return new SynxSettings(manifest.protocolVersion(), Baker.COMPRESSION_THRESHOLD, Baker.COMPRESSION_LEVEL,
                manifest.world().minY(), manifest.world().height(), manifest.registryDigest(),
                manifest.lightEngineVersion(), manifest.bakerVersion(), true);
    }

    /** The record of a chunk as the region file holds it. */
    SynxChunkRecord record(int chunkX, int chunkZ) {
        try (Arena arena = Arena.ofConfined()) {
            final SynxSlot slot = SynxRegion.open(regionFile(buildDir, chunkX, chunkZ), arena).slot(chunkX, chunkZ);
            if (!(slot instanceof SynxSlot.Present present)) throw new AssertionError(chunkX + "," + chunkZ + ": " + slot);
            return present.record();
        } catch (IOException | SynxException e) {
            throw new AssertionError(e);
        }
    }

    /** A copy of the release under {@code dir}, for a test that damages it. */
    Path copyTo(Path dir) throws IOException {
        final Path copy = dir.resolve(buildDir.getFileName());
        try (Stream<Path> files = Files.walk(buildDir)) {
            for (Path file : files.toList()) {
                final Path target = copy.resolve(buildDir.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else Files.copy(file, target);
            }
        }
        return copy;
    }

    static Path regionFile(Path buildDir, int chunkX, int chunkZ) {
        return buildDir.resolve("regions").resolve(SynxFormat.regionFileName(SynxFormat.region(chunkX),
                SynxFormat.region(chunkZ)));
    }

    static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The source NBT of the sign, keys a 26.2 save writes included. */
    static CompoundBinaryTag signNbt() {
        final ListBinaryTag lines = ListBinaryTag.listBinaryTag(BinaryTagTypes.STRING, List.of(
                StringBinaryTag.stringBinaryTag("Synaxis"), StringBinaryTag.stringBinaryTag(""),
                StringBinaryTag.stringBinaryTag(""), StringBinaryTag.stringBinaryTag("")));
        final CompoundBinaryTag front = CompoundBinaryTag.builder().put("messages", lines)
                .putString("color", "black").putBoolean("has_glowing_text", false).build();
        final CompoundBinaryTag back = CompoundBinaryTag.builder().put("messages", ListBinaryTag.listBinaryTag(
                        BinaryTagTypes.STRING, List.of(StringBinaryTag.stringBinaryTag(""),
                                StringBinaryTag.stringBinaryTag(""), StringBinaryTag.stringBinaryTag(""),
                                StringBinaryTag.stringBinaryTag(""))))
                .putString("color", "black").putBoolean("has_glowing_text", false).build();
        return CompoundBinaryTag.builder()
                .putString("id", "minecraft:sign")
                .putInt("x", SIGN_X).putInt("y", FEATURE_Y).putInt("z", SIGN_Z)
                .putBoolean("keepPacked", false)
                .putBoolean("is_waxed", false)
                .put("front_text", front)
                .put("back_text", back)
                .build();
    }

    static CompoundBinaryTag unknownChestNbt() {
        return CompoundBinaryTag.builder()
                .putString("id", "minecraft:chest")
                .putInt("x", UNKNOWN_X).putInt("y", FEATURE_Y).putInt("z", UNKNOWN_Z)
                .putString("LootTable", "minecraft:chests/simple_dungeon")
                .build();
    }

    static CompoundBinaryTag itemFrame(double x, double y, double z) {
        return entity("minecraft:item_frame", x, y, z, 90f, 0f)
                .putByte("Facing", (byte) 3)
                .put("Item", CompoundBinaryTag.builder().putString("id", "minecraft:diamond").putInt("count", 1).build())
                .putByte("ItemRotation", (byte) 2)
                .putBoolean("Invisible", false)
                .putBoolean("Fixed", true)
                .build();
    }

    static CompoundBinaryTag minecart(double x, double y, double z) {
        return entity("minecraft:minecart", x, y, z, 0f, 0f).build();
    }

    static CompoundBinaryTag.Builder entity(String id, double x, double y, double z, float yaw, float pitch) {
        return CompoundBinaryTag.builder()
                .putString("id", id)
                .put("Pos", ListBinaryTag.listBinaryTag(BinaryTagTypes.DOUBLE, List.of(
                        DoubleBinaryTag.doubleBinaryTag(x), DoubleBinaryTag.doubleBinaryTag(y),
                        DoubleBinaryTag.doubleBinaryTag(z))))
                .put("Rotation", ListBinaryTag.listBinaryTag(BinaryTagTypes.FLOAT, List.of(
                        FloatBinaryTag.floatBinaryTag(yaw), FloatBinaryTag.floatBinaryTag(pitch))));
    }

    /** One entity of each of the six covered types, every mapped field off its default. */
    static List<CompoundBinaryTag> mappedEntities() {
        final double x = MAPPED_CHUNK_X * 16 + 8.5, z = MAPPED_CHUNK_Z * 16 + 8.5;
        final CompoundBinaryTag diamond = CompoundBinaryTag.builder().putString("id", "minecraft:diamond")
                .putInt("count", 1).build();
        return List.of(
                entity("minecraft:glow_item_frame", x - 4, FEATURE_Y, z, 0f, 0f)
                        .putByte("Facing", (byte) 5).put("Item", diamond).putByte("ItemRotation", (byte) 7)
                        .putBoolean("Invisible", true).putBoolean("Fixed", true).build(),
                entity("minecraft:text_display", x - 2, FEATURE_Y, z, 45f, 10f)
                        .put("text", StringBinaryTag.stringBinaryTag("Welcome"))
                        .putString("billboard", "center").putInt("background", 0x7F00FF00).build(),
                entity("minecraft:item_display", x, FEATURE_Y, z, 0f, 0f).put("item", diamond).build(),
                entity("minecraft:block_display", x + 2, FEATURE_Y, z, 0f, 0f)
                        .put("block_state", CompoundBinaryTag.builder().putString("Name", "minecraft:oak_stairs")
                                .put("Properties", CompoundBinaryTag.builder().putString("facing", "east").build())
                                .build()).build(),
                entity("minecraft:armor_stand", x + 4, FEATURE_Y, z, 180f, 0f)
                        .putBoolean("Small", true).putBoolean("ShowArms", true).putBoolean("NoBasePlate", true)
                        .putBoolean("Invisible", true).putBoolean("Marker", true).build());
    }

    /** A handler of the bake: the baker only needs its key and client tags. */
    record BakeHandler(Key getKey, Collection<Tag<?>> getBlockEntityTags) implements BlockHandler {
    }

    /** The synthetic terrain plus the features of the tests. */
    private static final class FeatureSource implements BakeSource {
        private final GeneratorSource terrain;

        FeatureSource(GeneratorSource terrain) {
            this.terrain = terrain;
        }

        @Override
        public SourceChunk read(int chunkX, int chunkZ) {
            final SourceChunk chunk = terrain.read(chunkX, chunkZ);
            set(chunk, chunkX * 16, OTHER_AIR_Y, chunkZ * 16, Block.CAVE_AIR);
            set(chunk, chunkX * 16 + 1, OTHER_AIR_Y, chunkZ * 16, Block.VOID_AIR);
            final List<SynxBlockEntity> blockEntities = new ArrayList<>(chunk.blockEntities());
            final List<CompoundBinaryTag> entities = new ArrayList<>(chunk.entities());
            if (chunkX == SIGN_CHUNK_X && chunkZ == SIGN_CHUNK_Z) {
                set(chunk, SIGN_X, FEATURE_Y, SIGN_Z, Block.OAK_SIGN);
                blockEntities.add(blockEntity(SIGN_X, SIGN_Z, "", signNbt()));
            } else if (chunkX == UNKNOWN_CHUNK_X && chunkZ == UNKNOWN_CHUNK_Z) {
                set(chunk, UNKNOWN_X, FEATURE_Y, UNKNOWN_Z, Block.CHEST);
                blockEntities.add(blockEntity(UNKNOWN_X, UNKNOWN_Z, UNKNOWN_HANDLER, unknownChestNbt()));
            } else if (chunkX == TICKER_CHUNK_X && chunkZ == TICKER_CHUNK_Z) {
                set(chunk, TICKER_X, FEATURE_Y, TICKER_Z, Block.STONE);
                blockEntities.add(blockEntity(TICKER_X, TICKER_Z, TICKER_HANDLER, CompoundBinaryTag.empty()));
            } else if (chunkX == STATICS_CHUNK_X && chunkZ == STATICS_CHUNK_Z) {
                entities.add(itemFrame(chunkX * 16 + 2.5, FEATURE_Y, chunkZ * 16 + 2.5));
                entities.add(minecart(chunkX * 16 + 6.5, FEATURE_Y, chunkZ * 16 + 6.5));
            } else if (chunkX == MAPPED_CHUNK_X && chunkZ == MAPPED_CHUNK_Z) {
                entities.addAll(mappedEntities());
            } else if (chunkX == NEIGHBOUR_CHUNK_X && chunkZ == NEIGHBOUR_CHUNK_Z) {
                set(chunk, NEIGHBOUR_TICKER_X, FEATURE_Y, NEIGHBOUR_TICKER_Z, Block.STONE);
                blockEntities.add(blockEntity(NEIGHBOUR_TICKER_X, NEIGHBOUR_TICKER_Z, TICKER_HANDLER,
                        CompoundBinaryTag.empty()));
                entities.add(itemFrame(chunkX * 16 + 2.5, FEATURE_Y, chunkZ * 16 + 2.5));
            } else {
                return chunk;
            }
            return new SourceChunk(chunkX, chunkZ, chunk.blocks(), chunk.biomes(), blockEntities, entities,
                    chunk.lightOn(), chunk.sourceSky(), chunk.sourceBlock(), chunk.markerCells());
        }

        private static void set(SourceChunk chunk, int x, int y, int z, Block block) {
            final Palette section = chunk.blocks()[y >> 4];
            section.set(x & 15, y & 15, z & 15, block.stateId());
        }

        private static SynxBlockEntity blockEntity(int x, int z, String handler, CompoundBinaryTag nbt) {
            return new SynxBlockEntity(FEATURE_Y >> 4, SynxBlockEntity.index(x, FEATURE_Y, z), handler, nbt);
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
            return terrain.bounds();
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
}
