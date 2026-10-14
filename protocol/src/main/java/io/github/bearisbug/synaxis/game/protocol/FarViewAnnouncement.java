package io.github.bearisbug.synaxis.game.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The far view announcement of {@code docs/DESIGN-M3.md} {@code API-M3-015}: which height field
 * packs ({@link FarViewPack}) the client downloads and bakes into its far view, sent by a map shard in
 * the configuration phase on {@value #CHANNEL}, at most once per connection.
 * <p>
 * It is built from the primitives of {@link MsgBuf} but is no M2 message: no {@code msg_id}
 * envelope, not part of {@link ProtocolSchema#SCHEMA_VERSION}; it has a {@code format_version} of its
 * own, the first field. A payload the client cannot decode, an unknown {@code format_version}
 * included, is taken as no announcement.
 *
 * @param buildId     the {@code build_id} of the release the shard mounts, at most
 *                    {@value #MAX_BUILD_ID_BYTES} bytes
 * @param baseLevel   b of the packs, 1 or 2
 * @param coarseLevel c of the packs, {@value FarViewPack#COARSE_LEVEL}
 * @param minY        {@code world.min_y} of the release
 * @param height      {@code world.height} of the release
 * @param tiles       1 to {@value #MAX_TILES} tiles strictly ascending by x and then z, at least one of
 *                    them {@link #LAYER_FULL}
 */
public record FarViewAnnouncement(String buildId, int baseLevel, int coarseLevel, int minY, int height,
                                  List<Tile> tiles) {

    /** The configuration channel the announcement travels on, one whole string for the translation key gate. */
    public static final String CHANNEL = "synaxis:farview";
    /** The layout this record reads and writes. */
    public static final int FORMAT_VERSION = 1;
    /** The cap on the encoded {@code build_id}. */
    public static final int MAX_BUILD_ID_BYTES = 96;
    /** The cap on {@code tiles}. */
    public static final int MAX_TILES = 64;
    /** The cap on an encoded {@code url}. */
    public static final int MAX_URL_BYTES = 1024;
    /** The cap on {@code bytes} of a tile, 40 MiB: no baker writes a pack over 34,176,304 bytes. */
    public static final long MAX_OBJECT_BYTES = 41_943_040;
    /** The length of a SHA-256. */
    public static final int SHA256_BYTES = 32;
    /** {@code layer} of a tile that meets the bounds of the shard: the whole pack. */
    public static final int LAYER_FULL = 0;
    /** {@code layer} of a tile within 4,096 blocks of the bounds: its coarse prefix alone. */
    public static final int LAYER_COARSE = 1;

    /**
     * One object to download: the whole pack of a tile or its coarse prefix.
     *
     * @param tileX  the tile's x, {@code chunk_x >> 8} ({@link FarViewPack#tile}), as in the pack's header
     *               and file name
     * @param tileZ  the tile's z, the same way
     * @param layer  {@link #LAYER_FULL} or {@link #LAYER_COARSE}
     * @param bytes  the size of the object, at least 1 and at most {@value #MAX_OBJECT_BYTES}
     * @param sha256 the SHA-256 of the object, which names it
     * @param url    where to get it, at most {@value #MAX_URL_BYTES} bytes
     */
    public record Tile(int tileX, int tileZ, int layer, long bytes, byte[] sha256, String url) {

        /**
         * Checks the layer, the size, the digest and the url, and copies the digest.
         */
        public Tile {
            if (layer != LAYER_FULL && layer != LAYER_COARSE) {
                throw new MalformedMessageException("tile " + tileX + "," + tileZ + ": layer " + layer + " is neither 0 nor 1");
            }
            if (bytes < 1) throw new MalformedMessageException("tile " + tileX + "," + tileZ + ": bytes " + bytes + " is below 1");
            if (bytes > MAX_OBJECT_BYTES) {
                throw new MalformedMessageException("tile " + tileX + "," + tileZ + ": bytes " + bytes
                        + " is over the cap of " + MAX_OBJECT_BYTES);
            }
            if (sha256.length != SHA256_BYTES) {
                throw new MalformedMessageException("tile " + tileX + "," + tileZ + ": sha256 is " + sha256.length + " bytes");
            }
            sha256 = sha256.clone();
            final int urlBytes = url.getBytes(StandardCharsets.UTF_8).length;
            if (urlBytes > MAX_URL_BYTES) {
                throw new MalformedMessageException("tile " + tileX + "," + tileZ + ": url is " + urlBytes
                        + " bytes, over the cap of " + MAX_URL_BYTES);
            }
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Tile tile && tileX == tile.tileX && tileZ == tile.tileZ && layer == tile.layer
                    && bytes == tile.bytes && Arrays.equals(sha256, tile.sha256) && url.equals(tile.url);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tileX, tileZ, layer, bytes, url) * 31 + Arrays.hashCode(sha256);
        }

        @Override
        public String toString() {
            return "Tile[tileX=" + tileX + ", tileZ=" + tileZ + ", layer=" + layer + ", bytes=" + bytes + ", sha256="
                    + HexFormat.of().formatHex(sha256) + ", url=" + url + "]";
        }
    }

    /**
     * Copies the tiles and checks every field the client would take as no announcement.
     */
    public FarViewAnnouncement {
        final int buildIdBytes = buildId.getBytes(StandardCharsets.UTF_8).length;
        if (buildIdBytes > MAX_BUILD_ID_BYTES) {
            throw new MalformedMessageException("build_id is " + buildIdBytes + " bytes, over the cap of " + MAX_BUILD_ID_BYTES);
        }
        if (baseLevel != 1 && baseLevel != 2) throw new MalformedMessageException("base_level " + baseLevel + " is not 1 or 2");
        if (coarseLevel != FarViewPack.COARSE_LEVEL) {
            throw new MalformedMessageException("coarse_level " + coarseLevel + " is not " + FarViewPack.COARSE_LEVEL);
        }
        tiles = List.copyOf(tiles);
        if (tiles.isEmpty() || tiles.size() > MAX_TILES) {
            throw new MalformedMessageException("count " + tiles.size() + " is outside [1, " + MAX_TILES + "]");
        }
        for (int i = 1; i < tiles.size(); i++) {
            final Tile before = tiles.get(i - 1), tile = tiles.get(i);
            final int order = before.tileX() != tile.tileX() ? Integer.compare(before.tileX(), tile.tileX())
                    : Integer.compare(before.tileZ(), tile.tileZ());
            if (order == 0) throw new MalformedMessageException("tile " + tile.tileX() + "," + tile.tileZ() + " is announced twice");
            if (order > 0) {
                throw new MalformedMessageException("tile " + tile.tileX() + "," + tile.tileZ() + " is not after "
                        + before.tileX() + "," + before.tileZ());
            }
        }
        if (tiles.stream().noneMatch(tile -> tile.layer() == LAYER_FULL)) {
            throw new MalformedMessageException("no tile has layer 0, the shard announces no tile of its own");
        }
    }

    /**
     * Encodes the announcement.
     *
     * @param buf the writing buffer
     */
    public void write(MsgBuf buf) {
        buf.writeVarInt(FORMAT_VERSION);
        buf.writeString(buildId);
        buf.writeUnsignedByte(baseLevel);
        buf.writeUnsignedByte(coarseLevel);
        buf.writeVarInt(minY);
        buf.writeVarInt(height);
        buf.writeVarInt(tiles.size());
        for (Tile tile : tiles) {
            buf.writeVarInt(tile.tileX());
            buf.writeVarInt(tile.tileZ());
            buf.writeUnsignedByte(tile.layer());
            buf.writeVarLong(tile.bytes());
            buf.writeBytes(tile.sha256);
            buf.writeString(tile.url());
        }
    }

    /**
     * Decodes an announcement. Counts and lengths are held to their caps and to the bytes left
     * before anything is allocated for them.
     *
     * @param buf the reading buffer, the whole payload
     * @return the announcement
     * @throws MalformedMessageException for an unknown {@code format_version} and for any payload that
     *                                   is not an announcement of it
     */
    public static FarViewAnnouncement read(MsgBuf buf) {
        final int formatVersion = buf.readVarInt();
        if (formatVersion != FORMAT_VERSION) {
            throw new MalformedMessageException("format_version " + formatVersion + " is not " + FORMAT_VERSION);
        }
        final String buildId = buf.readString(MAX_BUILD_ID_BYTES);
        final int baseLevel = buf.readUnsignedByte();
        final int coarseLevel = buf.readUnsignedByte();
        final int minY = buf.readVarInt();
        final int height = buf.readVarInt();
        final int count = buf.readCount(MAX_TILES);
        final List<Tile> tiles = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int tileX = buf.readVarInt();
            final int tileZ = buf.readVarInt();
            final int layer = buf.readUnsignedByte();
            final long bytes = buf.readVarLong();
            final byte[] sha256 = buf.readBytes(SHA256_BYTES);
            tiles.add(new Tile(tileX, tileZ, layer, bytes, sha256, buf.readString(MAX_URL_BYTES)));
        }
        return new FarViewAnnouncement(buildId, baseLevel, coarseLevel, minY, height, tiles);
    }
}
