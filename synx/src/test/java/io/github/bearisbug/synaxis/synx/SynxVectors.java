package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.Registries;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.DoubleBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

/**
 * The inputs the frozen vectors of {@code docs/DESIGN-M3.md} §24 are written from. Everything here
 * is a pure function of its arguments: {@link SynxVectorGenerator} writes the files from it, and the
 * tests rebuild the same inputs to hold the reader and the writer to those files.
 * <p>
 * Palettes are filled back to front and one section per chunk goes in as a direct palette, so the
 * vectors only reproduce if the writer puts every section into its canonical form first.
 */
final class SynxVectors {
    static final Path DIR = Path.of("src/test/resources/vectors");
    static final Registries REGISTRIES = Registries.vanilla();
    static final String MAP_ID = "vectors";

    private static final int HEIGHT = 256;
    private static final int GROUND = 64;

    record ChunkInput(ChunkDataPacket packet, byte[] navigation, List<SynxBlockEntity> blockEntities,
                      List<CompoundBinaryTag> staticEntities, byte[] columns) {
        SynxChunkRecord toRecord(SynxSettings settings) {
            return new SynxChunkRecord(packet.chunkX(), packet.chunkZ(),
                    SynxChunkPacket.encode(packet, settings, REGISTRIES),
                    navigation, blockEntities, staticEntities, columns);
        }
    }

    record Vector(String name, int regionX, int regionZ, List<ChunkInput> chunks) {
        Path file() {
            return DIR.resolve(name + ".synx");
        }

        /** Encodes the region into a new staging file under {@code dir}. */
        SynxRegionWriter.Encoded encode(SynxSettings settings, Path dir) throws IOException {
            final SynxRegionWriter writer = new SynxRegionWriter(regionX, regionZ, settings);
            for (ChunkInput chunk : chunks) writer.add(chunk.toRecord(settings));
            return writer.encode(Files.createTempFile(dir, name, ".part"));
        }

        /** Encodes the region and returns the bytes of the finished file, written under {@code dir}. */
        byte[] bytes(SynxSettings settings, byte[] contentHash, Path dir) throws IOException {
            return SynxVectors.bytes(encode(settings, dir), contentHash, dir);
        }
    }

    private SynxVectors() {
    }

    static SynxSettings settings() {
        return settings(-1);
    }

    static SynxSettings settings(int compressionLevel) {
        return new SynxSettings(776, 256, compressionLevel, 0, HEIGHT,
                SynxDigests.registryDigest(REGISTRIES), 1, 1, true);
    }

    static SynxExpectations expectations() {
        return SynxExpectations.of(REGISTRIES, 256, 0, HEIGHT, 1);
    }

    /** The four regular vectors, in the order the build's regions are listed. */
    static List<Vector> vectors() {
        return List.of(
                new Vector("empty", -1, -1, List.of()),
                new Vector("one-chunk-flat", 0, 0, List.of(chunk(5, 7, false, false))),
                new Vector("two-by-two-lit", -1, 0, List.of(
                        chunk(-2, 0, true, false), chunk(-1, 0, true, false),
                        chunk(-2, 1, true, false), chunk(-1, 1, true, false))),
                new Vector("block-entities", 0, -1, List.of(chunk(3, -27, true, true))));
    }

    static Vector vector(String name) {
        return vectors().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
    }

    static SynxManifest.Bounds bounds() {
        return new SynxManifest.Bounds(-32, -32, 31, 31);
    }

    /** The manifest of a build made of the four regular vectors. */
    static SynxManifest manifest(List<SynxRegionWriter.Encoded> encoded, byte[] contentHash) {
        final List<Vector> vectors = vectors();
        final List<SynxManifest.Region> regions = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            final Vector vector = vectors.get(i);
            final SynxRegionWriter.Encoded region = encoded.get(i);
            regions.add(new SynxManifest.Region(vector.regionX(), vector.regionZ(),
                    region.fileSize(), region.payloadSha256(), region.chunkCount()));
        }
        final SynxSettings settings = settings();
        return new SynxManifest(MAP_ID, SynxManifest.buildId(MAP_ID, contentHash, 776), contentHash, "26.2", 776,
                settings.registryDigest(), 1, 1, new SynxManifest.World(0, HEIGHT), bounds(), regions,
                new SynxManifest.Source("generator", null, null),
                new SynxManifest.Bake("2026-09-25T00:00:00Z", 1_234, 5_678, 1, "25"),
                new SynxManifest.Verify(4, 0, null));
    }

    /** Finishes {@code encoded} into a new file under {@code dir} and reads it back. */
    static byte[] bytes(SynxRegionWriter.Encoded encoded, byte[] contentHash, Path dir) throws IOException {
        final Path file = Files.createTempDirectory(dir, "region").resolve("r.synx");
        encoded.write(file, contentHash);
        return Files.readAllBytes(file);
    }

    static byte[] contentHash(List<SynxRegionWriter.Encoded> encoded) {
        final List<Vector> vectors = vectors();
        final List<SynxDigests.RegionHash> hashes = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            hashes.add(new SynxDigests.RegionHash(vectors.get(i).regionX(), vectors.get(i).regionZ(),
                    encoded.get(i).regionContentHash()));
        }
        return SynxDigests.contentHash(hashes);
    }

    /**
     * SHA-256 of what this JVM's {@link Deflater} makes of a fixed 64 KiB input at the vectors'
     * level. The written bytes of a vector can only be expected to match on a JVM whose fingerprint
     * matches the one frozen in {@code manifest.txt}; the uncompressed content is expected to match
     * everywhere.
     */
    static String zlibFingerprint() {
        final byte[] input = new byte[64 * 1024];
        int state = 12345;
        for (int i = 0; i < input.length; i++) {
            state = state * 1103515245 + 12345;
            input[i] = (byte) ((i & 0x300) == 0 ? state >>> 24 : i >>> 5);
        }
        final Deflater deflater = new Deflater(settings().compressionLevel());
        try {
            deflater.setInput(input);
            deflater.finish();
            final byte[] output = new byte[input.length * 2];
            final int length = deflater.deflate(output);
            return SynxDigests.hex(SynxDigests.sha256(Arrays.copyOf(output, length)));
        } finally {
            deflater.end();
        }
    }

    // Chunk content

    static ChunkInput chunk(int chunkX, int chunkZ, boolean lit, boolean withBlockEntities) {
        final Map<Integer, Block> packetBlockEntities = new TreeMap<>();
        final List<SynxBlockEntity> blockEntities = new ArrayList<>();
        final List<CompoundBinaryTag> staticEntities = new ArrayList<>();
        if (withBlockEntities) {
            final CompoundBinaryTag chest = CompoundBinaryTag.builder()
                    .putString("id", "minecraft:chest")
                    .putString("LootTable", "minecraft:chests/simple_dungeon")
                    .build();
            final CompoundBinaryTag sign = CompoundBinaryTag.builder()
                    .putString("id", "minecraft:sign")
                    .put("front_text", CompoundBinaryTag.builder().put("messages", ListBinaryTag.listBinaryTag(
                            BinaryTagTypes.STRING, List.of(StringBinaryTag.stringBinaryTag("\"Synx\""),
                                    StringBinaryTag.stringBinaryTag("\"\""), StringBinaryTag.stringBinaryTag("\"\""),
                                    StringBinaryTag.stringBinaryTag("\"\"")))).build())
                    .build();
            final CompoundBinaryTag spawner = CompoundBinaryTag.builder()
                    .putString("id", "minecraft:mob_spawner")
                    .putShort("Delay", (short) 20)
                    .build();
            packetBlockEntities.put(CoordConversion.chunkBlockIndex(1, GROUND, 1), Block.CHEST.withNbt(chest));
            packetBlockEntities.put(CoordConversion.chunkBlockIndex(2, GROUND, 2), Block.OAK_SIGN.withNbt(sign));
            packetBlockEntities.put(CoordConversion.chunkBlockIndex(5, 10, 5), Block.SPAWNER.withNbt(spawner));
            // Listed out of order on purpose, the writer sorts them
            blockEntities.add(new SynxBlockEntity(0, SynxBlockEntity.index(5, 10, 5), "", spawner));
            blockEntities.add(new SynxBlockEntity(4, SynxBlockEntity.index(2, GROUND, 2), "", sign));
            blockEntities.add(new SynxBlockEntity(4, SynxBlockEntity.index(1, GROUND, 1), "synaxis:loot_chest", chest));
            staticEntities.add(entity("minecraft:armor_stand", chunkX * 16 + 4.5, GROUND, chunkZ * 16 + 4.5));
            staticEntities.add(entity("minecraft:item_frame", chunkX * 16 + 8.5, GROUND + 1.5, chunkZ * 16 + 0.03125));
        }
        final ChunkDataPacket packet = new ChunkDataPacket(chunkX, chunkZ,
                new ChunkData(heightmaps(chunkX, chunkZ), sections(chunkX, chunkZ, packetBlockEntities), packetBlockEntities),
                lit ? light() : unlit());
        return new ChunkInput(packet, navigation(chunkX, chunkZ), blockEntities, staticEntities, columns(chunkX, chunkZ));
    }

    private static CompoundBinaryTag entity(String id, double x, double y, double z) {
        return CompoundBinaryTag.builder()
                .putString("id", id)
                .put("Pos", ListBinaryTag.listBinaryTag(BinaryTagTypes.DOUBLE, List.of(
                        DoubleBinaryTag.doubleBinaryTag(x), DoubleBinaryTag.doubleBinaryTag(y),
                        DoubleBinaryTag.doubleBinaryTag(z))))
                .build();
    }

    /** The block of the column content, before block entities are placed. */
    static Block block(int chunkX, int chunkZ, int x, int y, int z) {
        final int worldX = chunkX * 16 + x;
        final int worldZ = chunkZ * 16 + z;
        if (y >= GROUND) {
            // A few pillars above the ground, so some upper sections are neither empty nor full
            return y < GROUND + 5 && Math.floorMod(worldX * 3 + worldZ * 5, 23) == 0 ? Block.OAK_LOG : Block.AIR;
        }
        if (y == GROUND - 1) return Math.floorMod(worldX + worldZ, 7) == 0 ? Block.WATER : Block.GRASS_BLOCK;
        if (y >= GROUND - 4) return Block.DIRT;
        if (Math.floorMod(worldX * 31 + y * 17 + worldZ * 13, 97) == 0) return Block.IRON_ORE;
        return y < 16 ? Block.DEEPSLATE : Block.STONE;
    }

    static List<ChunkData.Section> sectionList(int chunkX, int chunkZ, Map<Integer, Block> blockEntities) {
        final List<ChunkData.Section> sections = new ArrayList<>();
        for (int sectionY = 0; sectionY < HEIGHT / 16; sectionY++) {
            final int baseY = sectionY * 16;
            final Palette blocks = Palette.blocks();
            final int[] states = new int[4096];
            int blockCount = 0;
            int liquidCount = 0;
            for (int i = 0; i < 4096; i++) {
                final int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                Block block = block(chunkX, chunkZ, x, baseY + y, z);
                final Block entity = blockEntities.get(CoordConversion.chunkBlockIndex(x, baseY + y, z));
                if (entity != null) block = entity;
                states[i] = block.stateId();
                if (!block.air()) blockCount++;
                if (block.compare(Block.WATER)) liquidCount++;
            }
            if (sectionY == 2) {
                // One direct palette per chunk: setAll with more than one value stores it direct
                blocks.setAll((x, y, z) -> states[(y << 8) | (z << 4) | x]);
            } else {
                // Back to front, so the palette order is the reverse of the canonical one
                for (int i = 4095; i >= 0; i--) blocks.set(i & 15, i >> 8, (i >> 4) & 15, states[i]);
            }
            final Palette biomes = Palette.biomes();
            for (int i = 63; i >= 0; i--) {
                final int biome = Math.floorMod(chunkX + chunkZ + (i >> 4), 3) + (sectionY < 4 ? 3 : 0);
                biomes.set(i & 3, i >> 4, (i >> 2) & 3, biome);
            }
            sections.add(new ChunkData.Section((short) blockCount, (short) liquidCount, blocks, biomes));
        }
        return sections;
    }

    private static byte[] sections(int chunkX, int chunkZ, Map<Integer, Block> blockEntities) {
        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(REGISTRIES.biome().size());
        final List<ChunkData.Section> sections = sectionList(chunkX, chunkZ, blockEntities);
        return NetworkBuffer.makeArray(buffer -> {
            for (ChunkData.Section section : sections) buffer.write(type, section);
        }, REGISTRIES);
    }

    private static Map<Heightmap.Type, long[]> heightmaps(int chunkX, int chunkZ) {
        final short[] surface = new short[256];
        final short[] motion = new short[256];
        for (int i = 0; i < 256; i++) {
            final int x = i & 15, z = i >> 4;
            int top = GROUND;
            while (top < GROUND + 5 && !block(chunkX, chunkZ, x, top, z).air()) top++;
            surface[i] = (short) top;
            motion[i] = (short) top;
        }
        final int bits = 32 - Integer.numberOfLeadingZeros(HEIGHT);
        final Map<Heightmap.Type, long[]> heightmaps = new EnumMap<>(Heightmap.Type.class);
        // Put in reverse ordinal order, the packet encodes them by ordinal
        heightmaps.put(Heightmap.Type.WORLD_SURFACE, Heightmap.encode(surface, bits));
        heightmaps.put(Heightmap.Type.MOTION_BLOCKING, Heightmap.encode(motion, bits));
        return heightmaps;
    }

    private static LightData unlit() {
        return new LightData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of());
    }

    /** Sky 15 from the section at the ground up, none below; block light in the section under the ground. */
    private static LightData light() {
        final int lightSections = HEIGHT / 16 + 2;
        final BitSet skyMask = new BitSet(), emptySky = new BitSet(), blockMask = new BitSet(), emptyBlock = new BitSet();
        final List<byte[]> sky = new ArrayList<>();
        final List<byte[]> block = new ArrayList<>();
        for (int i = 0; i < lightSections; i++) {
            final int sectionY = i - 1;
            if (sectionY >= GROUND / 16) {
                skyMask.set(i);
                final byte[] array = new byte[2048];
                Arrays.fill(array, (byte) 0xFF);
                if (sectionY == GROUND / 16) {
                    // The pillars shade the lowest layer a little
                    for (int b = 0; b < 128; b++) array[b] = (byte) 0xEE;
                }
                sky.add(array);
            } else {
                emptySky.set(i);
            }
            if (sectionY == GROUND / 16 - 1) {
                blockMask.set(i);
                final byte[] array = new byte[2048];
                for (int b = 0; b < 2048; b += 97) array[b] = (byte) (b % 15 | (b % 13) << 4);
                block.add(array);
            } else {
                emptyBlock.set(i);
            }
        }
        return new LightData(skyMask, blockMask, emptySky, emptyBlock, sky, block);
    }

    private static byte[] navigation(int chunkX, int chunkZ) {
        final ByteBuffer buffer = ByteBuffer.allocate(SynxFormat.NAVIGATION_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 256; i++) {
            final int x = i & 15, z = i >> 4;
            final boolean walkable = !block(chunkX, chunkZ, x, GROUND - 1, z).compare(Block.WATER);
            if (walkable) buffer.put(i >> 3, (byte) (buffer.get(i >> 3) | 1 << (i & 7)));
            buffer.putShort(32 + i * 2, (short) (walkable ? GROUND : GROUND - 1));
        }
        return buffer.array();
    }

    private static byte[] columns(int chunkX, int chunkZ) {
        final ByteBuffer buffer = ByteBuffer.allocate(SynxFormat.COLUMN_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 256; i++) {
            final int x = i & 15, z = i >> 4;
            final Block top = block(chunkX, chunkZ, x, GROUND - 1, z);
            buffer.putShort(i * 5, (short) (GROUND - 1));
            buffer.putShort(i * 5 + 2, (short) top.stateId());
            buffer.put(i * 5 + 4, (byte) 15);
        }
        return buffer.array();
    }
}
