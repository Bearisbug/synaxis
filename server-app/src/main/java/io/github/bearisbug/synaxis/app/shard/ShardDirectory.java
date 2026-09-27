package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * The shard directory {@code <maps_root>/<map_id>/shards.json} as a shard in the directory mode read
 * it ({@code docs/DESIGN-M3.md} {@code API-M3-010}): the regions of the map with their bounds and
 * gates, the channels with their address and capacity, and which of them this shard is.
 * <p>
 * The public constructor checks nothing, so that a test can build any directory; the file is only
 * checked by {@link #load} and against the map release by {@link #checkAgainst}.
 *
 * @param mapId    the {@code map_id} of the map
 * @param regions  the regions, in the order of the file
 * @param channels the channels of every region, in the order of the file
 * @param shardId  this shard, {@code synaxis.app.shard-id}
 */
@SuppressWarnings("DoNotCallSuggester") // load and checkAgainst are the M3-E step 0 skeleton, the directory track replaces them
public record ShardDirectory(String mapId, List<Region> regions, List<Channel> channels, String shardId) {
    /** The name of the file under {@code synaxis.map.root}, next to the release directories. */
    public static final String FILE_NAME = "shards.json";
    /** What separates the region from the channel number in a shard id, {@code <region_id>-c<channel>}. */
    private static final String CHANNEL_SEPARATOR = "-c";

    public ShardDirectory {
        regions = List.copyOf(regions);
        channels = List.copyOf(channels);
    }

    /**
     * A region of the map.
     *
     * @param regionId its id
     * @param bounds   the chunks it covers, inclusive
     * @param gates    its gates to other regions
     */
    public record Region(String regionId, SynxManifest.Bounds bounds, List<Gate> gates) {
        public Region {
            gates = List.copyOf(gates);
        }
    }

    /**
     * A gate of a region: walking into its frame sends a player to the target region.
     *
     * @param gateId       its id
     * @param frame        the blocks it covers
     * @param targetRegion where it sends a player
     * @param arrival      where a player coming from the target region into this region spawns
     */
    public record Gate(String gateId, Frame frame, String targetRegion, Pos arrival) {
    }

    /**
     * The frame of a gate, inclusive block coordinates.
     *
     * @param minX the smallest x
     * @param minY the smallest y
     * @param minZ the smallest z
     * @param maxX the largest x
     * @param maxY the largest y
     * @param maxZ the largest z
     */
    public record Frame(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        /**
         * Whether a block is inside the frame.
         *
         * @param x the block x
         * @param y the block y
         * @param z the block z
         * @return true if it is, bounds included
         */
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /**
     * A channel of a region, one shard process.
     *
     * @param shardId  its shard id, {@code <region_id>-c<channel>}
     * @param regionId its region
     * @param channel  its number in the region
     * @param host     the address clients connect to
     * @param port     the port clients connect to
     * @param capacity how many players it seats, its {@code synaxis.max-players}
     */
    public record Channel(String shardId, String regionId, int channel, String host, int port, int capacity) {
    }

    /**
     * Reads and checks the directory ({@code API-M3-010}): the whole file, then the channel of
     * {@code shardId}. Writes nothing.
     *
     * @param file    {@code <map_root>/shards.json}
     * @param shardId {@code synaxis.app.shard-id}
     * @return the directory
     * @throws IllegalArgumentException for a refused start, naming the file and what is wrong in it
     */
    public static ShardDirectory load(Path file, String shardId) {
        throw new UnsupportedOperationException("ShardDirectory#load is the M3-E step 0 skeleton");
    }

    /**
     * Checks the directory against the map release it serves ({@code API-M3-010}): the same
     * {@code map_id}, every region within the release's bounds, the gate frames and arrivals within
     * the height of its world. The directory does not know its file: the message is the detail
     * alone, and {@link ShardWorld} puts {@code the shard directory <path> is not valid: } in front.
     *
     * @param manifest the manifest of the release
     * @throws IllegalArgumentException for a refused start, naming the array index or id at fault
     */
    public void checkAgainst(SynxManifest manifest) {
        throw new UnsupportedOperationException("ShardDirectory#checkAgainst is the M3-E step 0 skeleton");
    }

    /**
     * The channel of this shard.
     *
     * @return the channel whose shard id is {@link #shardId()}
     * @throws IllegalStateException if there is none, which {@link #load} refuses
     */
    public Channel ownChannel() {
        for (Channel channel : channels) {
            if (channel.shardId().equals(shardId)) return channel;
        }
        throw new IllegalStateException(shardId + " is not a channel of the shard directory");
    }

    /**
     * The region of this shard.
     *
     * @return the region of {@link #ownChannel()}
     * @throws IllegalStateException if the directory has no such region, which {@link #load} refuses
     */
    public Region ownRegion() {
        final String regionId = ownChannel().regionId();
        final Region region = region(regionId);
        if (region == null) throw new IllegalStateException(regionId + " is not a region of the shard directory");
        return region;
    }

    /**
     * A region by id.
     *
     * @param regionId the region id
     * @return the region, null if the directory has none of that id
     */
    public @Nullable Region region(String regionId) {
        for (Region region : regions) {
            if (region.regionId().equals(regionId)) return region;
        }
        return null;
    }

    /**
     * The gate of this shard's region that leads to a region; there is at most one.
     *
     * @param targetRegion the region it leads to
     * @return the gate, null if this shard's region has none leading there
     */
    public @Nullable Gate gateTo(String targetRegion) {
        for (Gate gate : ownRegion().gates()) {
            if (gate.targetRegion().equals(targetRegion)) return gate;
        }
        return null;
    }

    /**
     * The region a shard id names: what comes before its last {@code -c}.
     *
     * @param shardId a shard id, {@code <region_id>-c<channel>}
     * @return the region id, null if the shard id is not written that way
     */
    public static @Nullable String regionOfShard(String shardId) {
        final int separator = shardId.lastIndexOf(CHANNEL_SEPARATOR);
        return separator <= 0 ? null : shardId.substring(0, separator);
    }
}
