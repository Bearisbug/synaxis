package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.util.concurrent.CompletableFuture;

/**
 * The world state flags of a shard in the directory mode ({@code docs/DESIGN-M3.md}
 * {@code API-M3-013}): read and replayed at start, applied at run time on the tick scheduling thread,
 * kept in step across the channels of the region through Redis and a read of every row each 60 s,
 * and the cells they govern protected from the players.
 * <p>
 * The M3-E step 0 contract: the signatures {@code Main} and {@link ShardWorld} are assembled against.
 * The flags track replaces the bodies.
 */
@SuppressWarnings("DoNotCallSuggester") // the bodies of the skeleton, replaced by the flags track
public final class WorldFlagsImpl implements WorldFlags, AutoCloseable {

    /**
     * What the flags did, for {@code EVT:shard_started} and {@code EVT:chunk_stream_stats}.
     *
     * @param replayed     the keys applied by the replay at start
     * @param replayMillis how long reading and replaying them took together
     * @param conflicts    the cells found changed by another way than a flag, since the start
     */
    public record Stats(int replayed, long replayMillis, long conflicts) {
    }

    /**
     * Builds the flags of a region; reads nothing and starts no thread.
     *
     * @param dataSource   the player store database, which holds {@code world_flag}
     * @param redis        where the changes are announced
     * @param mapId        the {@code map_id} of the map
     * @param regionId     this shard's region
     * @param regionBounds the chunks of the region, the only ones a flag changes
     */
    public WorldFlagsImpl(DataSource dataSource, RedisLink redis, String mapId, String regionId,
                          SynxManifest.Bounds regionBounds) {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    @Override
    public void register(String key, FlagApplier applier) {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    @Override
    public @Nullable FlagValue get(String key) {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    @Override
    public CompletableFuture<FlagWrite> set(String key, JsonElement value, long expectedVersion) {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    /**
     * Reads every flag of the region, on the calling thread: at the start of {@link ShardWorld#start},
     * before the overlay store is opened. Retried 3 times.
     *
     * @throws IllegalArgumentException starting with {@code synx-world-flags-unavailable} when the
     *                                  flags cannot be read, which refuses the start
     */
    public void load() {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    /**
     * Applies what {@link #load} read to the map instance, on the calling thread, before the process
     * ticks: after the instance is built, before the world border, the spawn point and the bind.
     * Reads no database and refuses nothing.
     *
     * @param instance the map instance
     */
    public void replay(InstanceContainer instance) {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    /**
     * Protects the governed cells from the players, subscribes to the region's Redis channel and
     * schedules the read of every flag each 60 s; step (5) of {@code Main}.
     *
     * @param instance the map instance
     */
    public void start(InstanceContainer instance) {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    /** Returns what the flags did so far. */
    public Stats stats() {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    /** Runs a round of the 60 s read at once, for the tests. */
    void syncNow() {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    /**
     * Schedules one application straight onto the tick scheduling thread, skipping the chunk loads,
     * for the tests.
     *
     * @param key     the flag key
     * @param value   the value to apply
     * @param version its version
     */
    void schedule(String key, JsonElement value, long version) {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }

    /** Unsubscribes and stops the 60 s read; before the player store closes its pool. */
    @Override
    public void close() {
        throw new UnsupportedOperationException("WorldFlagsImpl is the M3-E step 0 skeleton");
    }
}
