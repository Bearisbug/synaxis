package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.LightEngine;
import io.github.bearisbug.synaxis.instance.light.LightResult;
import io.github.bearisbug.synaxis.instance.light.WorldView;
import io.github.bearisbug.synaxis.network.packet.server.BufferedPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.synx.SynxChunk;
import io.github.bearisbug.synaxis.synx.SynxChunkPacket;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's light of a chunk of a map instance, as {@code docs/DESIGN-M3.md} §19 means it: what
 * {@link LightEngine#computeArea} gives over the blocks the instance holds right now, the chunk and
 * its eight neighbours read under their read-locks, a chunk that is not a {@code SynxChunk} counting
 * as missing, as the recomputation counts it. Shared by the shard tests and the light fixture.
 */
final class LightTruth {
    private LightTruth() {
    }

    /** The light of chunk (x, z) over the current blocks of its 3×3. */
    static LightResult of(Instance instance, int chunkX, int chunkZ) {
        final int minY = instance.getCachedDimensionType().minY();
        final int height = instance.getCachedDimensionType().height();
        final int sections = height / 16;
        final Map<Long, char[][]> blocks = new HashMap<>();
        for (int x = chunkX - 1; x <= chunkX + 1; x++) {
            for (int z = chunkZ - 1; z <= chunkZ + 1; z++) {
                if (!(instance.getChunk(x, z) instanceof SynxChunk chunk)) continue;
                final char[][] states = new char[sections][4096];
                chunk.lockReadLock();
                try {
                    for (int i = 0; i < sections * 4096; i++) {
                        final int y = minY + (i >> 8);
                        states[i >> 12][i & 4095] = (char) chunk.getBlock(x * 16 + (i & 15), y, z * 16 + ((i >> 4) & 15),
                                Block.Getter.Condition.TYPE).stateId();
                    }
                } finally {
                    chunk.unlockReadLock();
                }
                blocks.put(key(x, z), states);
            }
        }
        return LightEngine.computeArea(new WorldView() {
            @Override
            public int minY() {
                return minY;
            }

            @Override
            public int height() {
                return height;
            }

            @Override
            public boolean hasChunk(int x, int z) {
                return blocks.containsKey(key(x, z));
            }

            @Override
            public Block getBlock(int x, int y, int z) {
                final char[][] states = blocks.get(key(x >> 4, z >> 4));
                final int local = y - minY;
                return Block.fromStateId(states[local >> 4][(x & 15) | (z & 15) << 4 | (local & 15) << 8]);
            }
        }, chunkX, chunkZ, chunkX, chunkZ);
    }

    private static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    /** The light a new viewer of a map chunk gets: that of its full data packet. */
    static LightData sent(SynxChunk chunk, Registries registries) {
        final SendablePacket packet = chunk.getFullDataPacket();
        return switch (packet) {
            case BufferedPacket buffered -> {
                final byte[] bytes = new byte[(int) buffered.length()];
                buffered.buffer().copyTo(buffered.index(), bytes, 0, bytes.length);
                yield SynxChunkPacket.decode(bytes, registries).lightData();
            }
            case CachedPacket cached -> ((ChunkDataPacket) cached.packet()).lightData();
            default -> throw new AssertionError("not a chunk packet: " + packet);
        };
    }

    /**
     * The first nibble where {@code light} differs from the server's, as {@code <layer> section <n> x z y
     * sent <a> server <b>}; null when every nibble of every section is the same. An empty section is all 0.
     */
    static @Nullable String firstDifference(LightData light, LightResult truth, int chunkX, int chunkZ, int minSection,
                                            int sections) {
        for (int section = 0; section < sections; section++) {
            final String sky = difference("sky", section, truth.sky(chunkX, minSection + section, chunkZ),
                    array(light.skyMask(), light.emptySkyMask(), light.skyLight(), section));
            if (sky != null) return sky;
            final String block = difference("block", section, truth.block(chunkX, minSection + section, chunkZ),
                    array(light.blockMask(), light.emptyBlockMask(), light.blockLight(), section));
            if (block != null) return block;
        }
        return null;
    }

    static byte @Nullable [] array(BitSet mask, BitSet empty, List<byte[]> arrays, int section) {
        final int bit = section + 1;
        if (empty.get(bit)) return new byte[2048];
        if (!mask.get(bit)) return null;
        return arrays.get(mask.get(0, bit).cardinality());
    }

    private static @Nullable String difference(String layer, int section, byte[] expected, byte @Nullable [] actual) {
        if (actual == null) return layer + " section " + section + " is not in the light data";
        final byte[] sent = actual.length == 0 ? new byte[2048] : actual;
        if (Arrays.equals(expected, sent)) return null;
        for (int i = 0; i < 4096; i++) {
            final int e = (expected[i >> 1] >> ((i & 1) << 2)) & 15, a = (sent[i >> 1] >> ((i & 1) << 2)) & 15;
            if (e != a) {
                return layer + " section " + section + " x " + (i & 15) + " z " + ((i >> 4) & 15) + " y " + (i >> 8)
                        + " sent " + a + " server " + e;
            }
        }
        return null;
    }
}
