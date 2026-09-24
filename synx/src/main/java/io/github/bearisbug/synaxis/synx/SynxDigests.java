package io.github.bearisbug.synaxis.synx;

import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.registry.DynamicRegistry;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.RegistryKey;
import io.github.bearisbug.synaxis.world.biome.Biome;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** The hashes of API-M3-001 and API-M3-002 that are not a plain SHA-256 over one byte range. */
public final class SynxDigests {
    private SynxDigests() {
    }

    /** One region's {@code region_content_hash}, the input of {@link #contentHash}. */
    public record RegionHash(int regionX, int regionZ, byte[] hash) {
        public RegionHash {
            if (hash.length != 32) throw new IllegalArgumentException("region content hashes are 32 bytes");
            hash = hash.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }
    }

    /**
     * {@code registry_digest}: SHA-256 of the UTF-8 text {@code "biome <count>\n"}, then each biome
     * key in registry id order followed by {@code "\n"}, then {@code "block_states <count>\n"}.
     * Segment ① names biomes by the id the configuration phase gave them, and blocks by state id.
     */
    public static byte[] registryDigest(Registries registries) {
        final DynamicRegistry<Biome> biomes = registries.biome();
        final StringBuilder text = new StringBuilder("biome ").append(biomes.size()).append('\n');
        for (int id = 0; id < biomes.size(); id++) {
            final RegistryKey<Biome> key = Objects.requireNonNull(biomes.getKey(id), "biome id without a key");
            text.append(key.key().asString()).append('\n');
        }
        text.append("block_states ").append(Block.statesCount()).append('\n');
        return sha256(utf8(text.toString()));
    }

    /**
     * {@code content_hash} of a map release: SHA-256 over the regions sorted by {@code (rx, rz)},
     * each contributing {@code rx i32, rz i32} little endian and its 32 byte {@code region_content_hash}.
     */
    public static byte[] contentHash(List<RegionHash> regions) {
        final List<RegionHash> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(RegionHash::regionX).thenComparingInt(RegionHash::regionZ));
        final ByteBuffer buffer = ByteBuffer.allocate(sorted.size() * 40).order(ByteOrder.LITTLE_ENDIAN);
        for (RegionHash region : sorted) {
            buffer.putInt(region.regionX()).putInt(region.regionZ()).put(region.hash);
        }
        return sha256(buffer.array());
    }

    public static byte[] sha256(byte[] bytes) {
        final MessageDigest digest = sha256();
        digest.update(bytes);
        return digest.digest();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JDK ships SHA-256", e);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** Lower or upper case hex; anything else throws {@link IllegalArgumentException}. */
    public static byte[] unhex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
