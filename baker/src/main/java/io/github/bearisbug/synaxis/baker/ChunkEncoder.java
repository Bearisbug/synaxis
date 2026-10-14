package io.github.bearisbug.synaxis.baker;

import io.github.bearisbug.synaxis.baker.anvil.AnvilChunk;
import io.github.bearisbug.synaxis.game.protocol.FarViewPack;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.block.BlockHandler;
import io.github.bearisbug.synaxis.instance.heightmap.Heightmap;
import io.github.bearisbug.synaxis.instance.light.LightCompute;
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
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Turns one lit source chunk into its four segments (API-M3-001) and, in the same pass over its columns, its fine
 * far view samples (API-M3-014). A pure function of the chunk, its light and the settings: the region task and
 * the verification's sampled re-encode call it the same way.
 */
final class ChunkEncoder {
    /** Indirect block palettes hold at most this many states; a section with more can only be sent direct. */
    static final int MAX_INDIRECT_STATES = 1 << Palette.BLOCK_PALETTE_MAX_BITS;

    /**
     * What one chunk bakes into.
     *
     * @param record  the four segments
     * @param farView the fine far view samples of the chunk at {@link FarViewAssembler#BASE_LEVEL}, by z and then
     *                x; {@code biome} is the biome's registry id, not yet an index into a tile's table
     */
    record Encoded(SynxChunkRecord record, List<FarViewPack.Sample> farView) {
        Encoded {
            farView = List.copyOf(farView);
        }
    }

    private static final boolean[] NON_AIR;
    private static final boolean[] MOTION_BLOCKING;
    private static final boolean[] FLUID;
    /** The ground of a far view column: blocks motion and holds no fluid. */
    private static final boolean[] GROUND;

    static {
        final int states = Block.statesCount();
        NON_AIR = new boolean[states];
        MOTION_BLOCKING = new boolean[states];
        FLUID = new boolean[states];
        GROUND = new boolean[states];
        for (int state = 0; state < states; state++) {
            final Block block = Objects.requireNonNull(Block.fromStateId(state));
            NON_AIR[state] = !block.air();
            FLUID[state] = block.fluid();
            MOTION_BLOCKING[state] = block.blocksMotion() || block.fluid();
            GROUND[state] = block.blocksMotion() && !block.fluid();
        }
    }

    private ChunkEncoder() {
    }

    /**
     * Bakes one chunk.
     *
     * @param handlers the handler of each namespace segment ③ names; its tags decide what the client sees
     * @throws IllegalStateException if a section holds more block states than an indirect palette, or a record of
     *                               segment ③ names a handler namespace that has no handler
     */
    static Encoded encode(SourceChunk chunk, LightResult light, int minY, SynxSettings settings,
                          Registries registries, Function<String, @Nullable BlockHandler> handlers) {
        final int sections = chunk.blocks().length, minSection = minY >> 4;
        // y << 8 | z << 4 | x with y counted from the bottom of the world; s << 12 | SynxBlockEntity#index is the same cell
        final int[] states = new int[sections << 12];
        final List<ChunkData.Section> packetSections = new ArrayList<>(sections);
        final BitSet seen = new BitSet();
        for (int s = 0; s < sections; s++) {
            final Palette blocks = chunk.blocks()[s];
            final int base = s << 12;
            final int[] counts = new int[2];
            seen.clear();
            blocks.getAll((x, y, z, state) -> {
                states[base | y << 8 | z << 4 | x] = state;
                seen.set(state);
                if (NON_AIR[state]) counts[0]++;
                if (FLUID[state]) counts[1]++;
            });
            // REQ-M3-003: a baked section is single valued or indirect once SynxChunkPacket#encode makes it canonical
            final int distinct = seen.cardinality();
            if (distinct > MAX_INDIRECT_STATES) {
                final int sectionY = minSection + s;
                final int blockX = chunk.chunkX() * 16, blockY = sectionY * 16, blockZ = chunk.chunkZ() * 16;
                throw new IllegalStateException("chunk " + chunk.chunkX() + ", " + chunk.chunkZ() + " section " + sectionY
                        + " holds " + distinct + " block states, more than the " + MAX_INDIRECT_STATES
                        + " of an indirect palette (blocks " + blockX + ".." + (blockX + 15) + ", " + blockY + ".."
                        + (blockY + 15) + ", " + blockZ + ".." + (blockZ + 15) + ")");
            }
            packetSections.add(new ChunkData.Section((short) counts[0], (short) counts[1], blocks, chunk.biomes()[s]));
        }

        // One pass over every column: the two heightmaps, segment ②, segment ④ and the far view column.
        // Segment ② (the M2 nav layer): bit `column` of bytes 0..31 is walkable, then from NAVIGATION_SURFACE_Y one
        // i16 per column, the absolute y a unit stands at (one above the top, min_y for an empty column).
        // Segment ④: 5 B per column, the top non-air block as a y index (y - min_y, 0 for an empty column), its
        // state, and the sky light above it.
        final int height = sections * 16;
        final short[] worldSurface = new short[256];
        final short[] motionBlocking = new short[256];
        final FarViewPack.Sample[] farViewColumns = new FarViewPack.Sample[256];
        final ByteBuffer navigation = ByteBuffer.allocate(SynxFormat.NAVIGATION_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer columns = ByteBuffer.allocate(SynxFormat.COLUMN_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int column = 0; column < 256; column++) {
            final int x = column & 15, z = column >> 4;
            int surface = -1, motion = -1;
            for (int y = height - 1; y >= 0 && (surface < 0 || motion < 0); y--) {
                final int state = states[y << 8 | column];
                if (surface < 0 && NON_AIR[state]) surface = y;
                if (motion < 0 && MOTION_BLOCKING[state]) motion = y;
            }
            worldSurface[column] = (short) (surface + 1);
            motionBlocking[column] = (short) (motion + 1);
            farViewColumns[column] = farViewColumn(states, chunk.biomes(), column, motion, height);

            final boolean walkable = motion >= 0 && !FLUID[states[motion << 8 | column]];
            if (walkable) navigation.put(column >> 3, (byte) (navigation.get(column >> 3) | 1 << (column & 7)));
            navigation.putShort(SynxFormat.NAVIGATION_SURFACE_Y + column * 2, (short) (motion >= 0 ? minY + motion + 1 : minY));

            columns.putShort(column * 5, (short) Math.max(surface, 0));
            columns.putShort(column * 5 + 2, (short) (surface >= 0 ? states[surface << 8 | column] : 0));
            final int above = surface + 1;
            columns.put(column * 5 + 4, (byte) (above >= height ? 15
                    : light.skyLevel(chunk.chunkX() * 16 + x, minY + above, chunk.chunkZ() * 16 + z)));
        }
        final int bits = 32 - Integer.numberOfLeadingZeros(height);
        // The ChunkData constructor puts both maps in their encoding order (API-M3-001 段① 字节可复现)
        final Map<Heightmap.Type, long[]> heightmaps = Map.of(
                Heightmap.Type.WORLD_SURFACE, Heightmap.encode(worldSurface, bits),
                Heightmap.Type.MOTION_BLOCKING, Heightmap.encode(motionBlocking, bits));

        final List<SynxBlockEntity> blockEntities = new ArrayList<>(chunk.blockEntities());
        blockEntities.sort(SynxBlockEntity.ORDER);
        final Map<Integer, Block> packetBlockEntities = packetBlockEntities(chunk, states, minSection, blockEntities,
                handlers);

        final NetworkBuffer.Type<ChunkData.Section> type = ChunkData.Section.networkType(registries.biome().size());
        final byte[] data = NetworkBuffer.makeArray(buffer -> {
            for (ChunkData.Section section : packetSections) buffer.write(type, section);
        }, registries);
        final ChunkDataPacket packet = new ChunkDataPacket(chunk.chunkX(), chunk.chunkZ(),
                new ChunkData(heightmaps, data, packetBlockEntities), lightData(chunk, light, minSection));
        return new Encoded(new SynxChunkRecord(chunk.chunkX(), chunk.chunkZ(),
                SynxChunkPacket.encode(packet, settings, registries), navigation.array(), blockEntities, chunk.entities(),
                columns.array()), farViewSamples(farViewColumns));
    }

    /**
     * The block entities segment ① carries, by chunk block index: every record whose block has a block entity
     * type, without the record keys and with its handler. A record on a block without one (a data marker's or a
     * generator's handler on a plain block) stays in segment ③ only; the client has nothing to attach it to.
     *
     * @param states        the chunk's block states, laid out as in {@link #encode}
     * @param blockEntities the records of segment ③, in its order
     * @throws IllegalStateException if a record names a handler namespace that has no handler
     */
    private static Map<Integer, Block> packetBlockEntities(SourceChunk chunk, int[] states, int minSection,
                                                           List<SynxBlockEntity> blockEntities,
                                                           Function<String, @Nullable BlockHandler> handlers) {
        final Map<Integer, Block> packetBlockEntities = new HashMap<>();
        for (SynxBlockEntity blockEntity : blockEntities) {
            // Every record: the gate checks an Anvil source's markers, nothing before this checks a generator's handlers
            final BlockHandler handler = blockEntity.handler().isEmpty() ? null : handlers.apply(blockEntity.handler());
            if (!blockEntity.handler().isEmpty() && handler == null) {
                final int index = blockEntity.index();
                throw new IllegalStateException("chunk " + chunk.chunkX() + ", " + chunk.chunkZ() + ": the block at "
                        + (chunk.chunkX() * 16 + (index & 15)) + ", " + (blockEntity.sectionY() * 16 + (index >> 8))
                        + ", " + (chunk.chunkZ() * 16 + ((index >> 4) & 15)) + " has handler namespace '"
                        + blockEntity.handler() + "', which no BlockHandler service on the class path registers");
            }
            Block block = Objects.requireNonNull(Block.fromStateId(
                    states[(blockEntity.sectionY() - minSection) << 12 | blockEntity.index()]));
            if (block.blockEntityType() == null) continue;
            block = block.withNbt(SynxBlockEntity.withoutRecordKeys(blockEntity.nbt()));
            if (handler != null) block = block.withHandler(handler);
            packetBlockEntities.put(blockEntity.chunkBlockIndex(), block);
        }
        return packetBlockEntities;
    }

    /**
     * Rules 1 to 4 of {@code API-M3-014} 来源与采样规则 on one column: the top is the highest block that blocks
     * motion or holds fluid. Fluid on top runs down through the fluid touching it; the ground is then the first
     * block below that blocks motion without fluid, and every cell between it and the top counts as fluid. A
     * column without a top is empty. The biome is that of the top's cell, or of the world's highest cell for a
     * column without a top.
     *
     * @param states the chunk's block states, laid out as in {@link #encode}
     * @param top    the top's y index, -1 for an empty column
     */
    private static FarViewPack.Sample farViewColumn(int[] states, Palette[] biomes, int column, int top, int height) {
        final int x = column & 15, z = column >> 4;
        final int biomeY = top >= 0 ? top : height - 1;
        final int biome = biomes[biomeY >> 4].get(x >> 2, (biomeY & 15) >> 2, z >> 2);
        if (top < 0) return new FarViewPack.Sample(0, 0, 0, 0, 0, biome);
        final int topState = states[top << 8 | column];
        if (!FLUID[topState]) return new FarViewPack.Sample(topState, 0, top, 0, 0, biome);
        int y = top - 1;
        while (y >= 0 && FLUID[states[y << 8 | column]]) y--;
        final int lowestFluid = y + 1;
        while (y >= 0 && !GROUND[states[y << 8 | column]]) y--;
        if (y < 0) return new FarViewPack.Sample(0, topState, 0, lowestFluid, top, biome);
        return new FarViewPack.Sample(states[y << 8 | column], topState, y, y + 1, top, biome);
    }

    /**
     * Rule 5: each square of {@code 2^b × 2^b} columns gives the column with the highest top, ties going to the
     * first by z and then x.
     *
     * @param columns the 256 columns, {@code z × 16 + x}
     * @return the squares by z and then x
     */
    private static List<FarViewPack.Sample> farViewSamples(FarViewPack.Sample[] columns) {
        final int side = FarViewAssembler.CHUNK_SAMPLES, square = 16 / side;
        final List<FarViewPack.Sample> samples = new ArrayList<>(side * side);
        for (int sz = 0; sz < side; sz++) {
            for (int sx = 0; sx < side; sx++) {
                FarViewPack.Sample highest = null;
                for (int z = sz * square; z < (sz + 1) * square; z++) {
                    for (int x = sx * square; x < (sx + 1) * square; x++) {
                        final FarViewPack.Sample candidate = columns[z << 4 | x];
                        if (highest == null || candidate.top() > highest.top()) highest = candidate;
                    }
                }
                samples.add(highest);
            }
        }
        return samples;
    }

    /**
     * Sections the light reaches get their array; the others are marked empty, which the client reads as all 0.
     * Mask bit {@code i} is section {@code minSection - 1 + i}; the sections below and above the world are left out.
     */
    private static LightData lightData(SourceChunk chunk, LightResult light, int minSection) {
        final BitSet skyMask = new BitSet(), blockMask = new BitSet(), emptySky = new BitSet(), emptyBlock = new BitSet();
        final List<byte[]> sky = new ArrayList<>(), block = new ArrayList<>();
        for (int s = 0; s < chunk.blocks().length; s++) {
            add(light.sky(chunk.chunkX(), minSection + s, chunk.chunkZ()), s + 1, skyMask, emptySky, sky);
            add(light.block(chunk.chunkX(), minSection + s, chunk.chunkZ()), s + 1, blockMask, emptyBlock, block);
        }
        return new LightData(skyMask, blockMask, emptySky, emptyBlock, sky, block);
    }

    /** One section's light under mask bit {@code bit}: marked empty when it is all 0, else masked and added. */
    private static void add(byte[] nibbles, int bit, BitSet mask, BitSet empty, List<byte[]> arrays) {
        if (Arrays.equals(nibbles, LightCompute.EMPTY_CONTENT)) {
            empty.set(bit);
        } else {
            mask.set(bit);
            arrays.add(nibbles);
        }
    }

    /**
     * REQ-M3-021: nibbles where the computed light differs from the source's own, over the sections the source
     * has light for, without {@link SourceChunk#markerCells}. 0 unless the source marked the chunk {@code isLightOn}.
     */
    static long sourceLightDiff(SourceChunk chunk, LightResult light, int minY) {
        if (!chunk.lightOn()) return 0;
        final int minSection = minY >> 4;
        long diff = 0;
        for (int s = 0; s < chunk.blocks().length; s++) {
            diff += nibbleDiff(chunk.sourceSky() == null ? null : chunk.sourceSky()[s],
                    light.sky(chunk.chunkX(), minSection + s, chunk.chunkZ()), minSection + s, chunk.markerCells());
            diff += nibbleDiff(chunk.sourceBlock() == null ? null : chunk.sourceBlock()[s],
                    light.block(chunk.chunkX(), minSection + s, chunk.chunkZ()), minSection + s, chunk.markerCells());
        }
        return diff;
    }

    /** Nibble {@code n} of a light array is the light of block index {@code n}, low nibble first. */
    private static int nibbleDiff(byte @Nullable [] source, byte[] computed, int sectionY, Set<Long> skipped) {
        if (source == null) return 0;
        int diff = 0;
        for (int i = 0; i < source.length; i++) {
            final int xor = source[i] ^ computed[i];
            if ((xor & 0x0F) != 0 && !skipped.contains(AnvilChunk.cellKey(sectionY, i * 2))) diff++;
            if ((xor & 0xF0) != 0 && !skipped.contains(AnvilChunk.cellKey(sectionY, i * 2 + 1))) diff++;
        }
        return diff;
    }
}
