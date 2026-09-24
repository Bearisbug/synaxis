package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.instance.palette.Palette;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.ChunkData;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxBlockEntity;
import io.github.bearisbug.synaxis.synx.SynxChunkPacket;
import io.github.bearisbug.synaxis.synx.SynxChunkRecord;
import io.github.bearisbug.synaxis.synx.SynxFormat;
import io.github.bearisbug.synaxis.synx.SynxSettings;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Turns one lit source chunk into its four segments (API-M3-001). A pure function of the chunk, its light and
 * the settings: the region task and the verification's sampled re-encode call it the same way.
 */
final class ChunkEncoder {
    /** Indirect block palettes hold at most this many states; a section with more can only be sent direct. */
    static final int MAX_INDIRECT_STATES = 1 << Palette.BLOCK_PALETTE_MAX_BITS;

    private static final boolean[] NON_AIR;
    private static final boolean[] MOTION_BLOCKING;
    private static final boolean[] FLUID;
    private static final byte[] DARK = new byte[2048];
    /** The block entity record's own keys, which the client packet does not carry. */
    private static final List<String> RECORD_KEYS = List.of("id", "x", "y", "z", "keepPacked");

    static {
        final int states = Block.statesCount();
        NON_AIR = new boolean[states];
        MOTION_BLOCKING = new boolean[states];
        FLUID = new boolean[states];
        for (int state = 0; state < states; state++) {
            final Block block = Objects.requireNonNull(Block.fromStateId(state));
            NON_AIR[state] = !block.air();
            FLUID[state] = block.fluid();
            MOTION_BLOCKING[state] = block.blocksMotion() || block.fluid();
        }
    }

    private ChunkEncoder() {
    }

    /**
     * @param handlers the handler of each namespace segment ③ names; its tags decide what the client sees
     * @throws IllegalStateException if a section holds more block states than an indirect palette, or a handler
     *                               namespace has no handler
     */
    static SynxChunkRecord encode(SourceChunk chunk, LightResult light, int minY, SynxSettings settings,
                                  Registries registries, Function<String, @Nullable BlockHandler> handlers) {
        final int sections = chunk.blocks().length, minSection = minY >> 4;
        final int[][] states = new int[sections][];
        final List<ChunkData.Section> packetSections = new ArrayList<>(sections);
        for (int s = 0; s < sections; s++) {
            final Palette blocks = chunk.blocks()[s];
            requireIndirect(chunk, minSection + s, blocks);
            final int[] section = new int[4096];
            final int[] counts = new int[2];
            blocks.getAll((x, y, z, state) -> {
                section[y << 8 | z << 4 | x] = state;
                if (NON_AIR[state]) counts[0]++;
                if (FLUID[state]) counts[1]++;
            });
            states[s] = section;
            packetSections.add(new ChunkData.Section((short) counts[0], (short) counts[1], blocks, chunk.biomes()[s]));
        }

        // One pass over every column: the two heightmaps, segment ② and segment ④
        final int height = sections * 16;
        final short[] worldSurface = new short[256];
        final short[] motionBlocking = new short[256];
        final ByteBuffer navigation = ByteBuffer.allocate(SynxFormat.NAVIGATION_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer columns = ByteBuffer.allocate(SynxFormat.COLUMN_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int column = 0; column < 256; column++) {
            final int x = column & 15, z = column >> 4;
            int surface = -1, motion = -1;
            for (int y = height - 1; y >= 0 && (surface < 0 || motion < 0); y--) {
                final int state = states[y >> 4][(y & 15) << 8 | column];
                if (surface < 0 && NON_AIR[state]) surface = y;
                if (motion < 0 && MOTION_BLOCKING[state]) motion = y;
            }
            worldSurface[column] = (short) (surface + 1);
            motionBlocking[column] = (short) (motion + 1);

            final boolean walkable = motion >= 0 && !FLUID[states[motion >> 4][(motion & 15) << 8 | column]];
            if (walkable) navigation.put(column >> 3, (byte) (navigation.get(column >> 3) | 1 << (column & 7)));
            navigation.putShort(32 + column * 2, (short) (motion >= 0 ? minY + motion + 1 : minY));

            columns.putShort(column * 5, (short) Math.max(surface, 0));
            columns.putShort(column * 5 + 2, (short) (surface >= 0 ? states[surface >> 4][(surface & 15) << 8 | column] : 0));
            final int above = surface + 1;
            columns.put(column * 5 + 4, (byte) (above >= height ? 15
                    : light.skyLevel(chunk.chunkX() * 16 + x, minY + above, chunk.chunkZ() * 16 + z)));
        }
        final int bits = 32 - Integer.numberOfLeadingZeros(height);
        final Map<Heightmap.Type, long[]> heightmaps = new EnumMap<>(Heightmap.Type.class);
        heightmaps.put(Heightmap.Type.WORLD_SURFACE, Heightmap.encode(worldSurface, bits));
        heightmaps.put(Heightmap.Type.MOTION_BLOCKING, Heightmap.encode(motionBlocking, bits));

        final List<SynxBlockEntity> blockEntities = new ArrayList<>(chunk.blockEntities());
        blockEntities.sort(Comparator.comparingInt(SynxBlockEntity::sectionY).thenComparingInt(SynxBlockEntity::index));
        final Map<Integer, Block> packetBlockEntities = new TreeMap<>();
        for (SynxBlockEntity blockEntity : blockEntities) {
            final int s = blockEntity.sectionY() - minSection;
            Block block = Objects.requireNonNull(Block.fromStateId(states[s][blockEntity.index()]));
            if (block.blockEntityType() == null) continue;
            CompoundBinaryTag nbt = blockEntity.nbt();
            for (String key : RECORD_KEYS) nbt = nbt.remove(key);
            block = block.withNbt(nbt);
            if (!blockEntity.handler().isEmpty()) {
                final BlockHandler handler = handlers.apply(blockEntity.handler());
                if (handler == null) {
                    throw new IllegalStateException("chunk " + chunk.chunkX() + ", " + chunk.chunkZ() + ": handler "
                            + blockEntity.handler() + " is not registered");
                }
                block = block.withHandler(handler);
            }
            final int y = blockEntity.sectionY() * 16 + (blockEntity.index() >> 8);
            packetBlockEntities.put(CoordConversion.chunkBlockIndex(blockEntity.index() & 15, y,
                    (blockEntity.index() >> 4) & 15), block);
        }

        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(registries.biome().size());
        final byte[] data = NetworkBuffer.makeArray(buffer -> {
            for (ChunkData.Section section : packetSections) buffer.write(type, section);
        }, registries);
        final ChunkDataPacket packet = new ChunkDataPacket(chunk.chunkX(), chunk.chunkZ(),
                new ChunkData(heightmaps, data, packetBlockEntities), lightData(chunk, light, minSection, sections));
        return new SynxChunkRecord(chunk.chunkX(), chunk.chunkZ(), SynxChunkPacket.encode(packet, settings, registries),
                navigation.array(), blockEntities, chunk.entities(), columns.array());
    }

    /**
     * Sections the light reaches get their array; the others are marked empty, which the client reads as all 0.
     * Mask bit {@code i} is section {@code minSection - 1 + i}; the sections below and above the world are left out.
     */
    private static LightData lightData(SourceChunk chunk, LightResult light, int minSection, int sections) {
        final BitSet skyMask = new BitSet(), blockMask = new BitSet(), emptySky = new BitSet(), emptyBlock = new BitSet();
        final List<byte[]> sky = new ArrayList<>(), block = new ArrayList<>();
        for (int s = 0; s < sections; s++) {
            final byte[] skyLight = light.sky(chunk.chunkX(), minSection + s, chunk.chunkZ());
            if (Arrays.equals(skyLight, DARK)) {
                emptySky.set(s + 1);
            } else {
                skyMask.set(s + 1);
                sky.add(skyLight);
            }
            final byte[] blockLight = light.block(chunk.chunkX(), minSection + s, chunk.chunkZ());
            if (Arrays.equals(blockLight, DARK)) {
                emptyBlock.set(s + 1);
            } else {
                blockMask.set(s + 1);
                block.add(blockLight);
            }
        }
        return new LightData(skyMask, blockMask, emptySky, emptyBlock, sky, block);
    }

    /**
     * REQ-M3-003: a baked section is single valued or indirect. A source palette of 8 bits or fewer already is;
     * a direct one (a generator's {@code setAll}) becomes indirect when it is made canonical, unless it holds
     * more states than an indirect palette can.
     */
    private static void requireIndirect(SourceChunk chunk, int sectionY, Palette blocks) {
        if (blocks.bitsPerEntry() <= Palette.BLOCK_PALETTE_MAX_BITS) return;
        final BitSet seen = new BitSet();
        blocks.getAll((_, _, _, state) -> seen.set(state));
        final int distinct = seen.cardinality();
        if (distinct > MAX_INDIRECT_STATES) {
            throw new IllegalStateException("chunk " + chunk.chunkX() + ", " + chunk.chunkZ() + " section " + sectionY
                    + " holds " + distinct + " block states, more than the " + MAX_INDIRECT_STATES
                    + " of an indirect palette");
        }
    }

    /**
     * REQ-M3-021: nibbles where the computed light differs from the source's own, over the sections the source
     * has light for. 0 unless the source marked the chunk {@code isLightOn}.
     */
    static long sourceLightDiff(SourceChunk chunk, LightResult light, int minY) {
        if (!chunk.lightOn()) return 0;
        final int minSection = minY >> 4;
        long diff = 0;
        for (int s = 0; s < chunk.blocks().length; s++) {
            diff += nibbleDiff(chunk.sourceSky() == null ? null : chunk.sourceSky()[s],
                    light.sky(chunk.chunkX(), minSection + s, chunk.chunkZ()));
            diff += nibbleDiff(chunk.sourceBlock() == null ? null : chunk.sourceBlock()[s],
                    light.block(chunk.chunkX(), minSection + s, chunk.chunkZ()));
        }
        return diff;
    }

    private static int nibbleDiff(byte @Nullable [] source, byte[] computed) {
        if (source == null) return 0;
        int diff = 0;
        for (int i = 0; i < source.length; i++) {
            final int xor = source[i] ^ computed[i];
            if ((xor & 0x0F) != 0) diff++;
            if ((xor & 0xF0) != 0) diff++;
        }
        return diff;
    }
}
