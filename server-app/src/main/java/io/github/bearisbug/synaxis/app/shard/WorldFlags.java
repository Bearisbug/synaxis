package io.github.bearisbug.synaxis.app.shard;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * The world state flags of this shard's region ({@code docs/DESIGN-M3.md} {@code API-M3-013}): world
 * state every channel of the region and every map release share, kept in {@code world_flag} and
 * applied to the blocks a key's {@link FlagApplier} gives. A host interface: a map shard loads no
 * plugin, so it never reaches the plugin API.
 */
public interface WorldFlags {

    /**
     * Registers how a key lands on the blocks. Only before the replay at start ({@code Main} step (5)).
     *
     * @param key     the flag key, {@code <namespace>:<name>}
     * @param applier the cells a value of the key asks for
     * @throws IllegalStateException once the flags have been replayed
     */
    void register(String key, FlagApplier applier);

    /**
     * The value and version this process applied last, as a copy of its own.
     *
     * @param key the flag key
     * @return the value, null if this process has none for the key
     */
    @Nullable FlagValue get(String key);

    /**
     * A conditional write: only when the version in the database is {@code expectedVersion} (0 for a
     * new key), written as that version plus one. Callable from any thread; the value is copied first.
     * <p>
     * Written means the row is in the database: the blocks change, and {@link #get} returns the value,
     * on a later tick once its chunks have loaded. A key with no applier registered in this process is
     * written and announced but never applied here, and {@link #get} stays null for it. A key that is
     * not {@code <namespace>:<name>}, or a value too long for the table, fails for good.
     *
     * @param key             the flag key
     * @param value           the new value
     * @param expectedVersion the version the caller saw, 0 for a new key
     * @return completes with how the write went
     */
    CompletableFuture<FlagWrite> set(String key, JsonElement value, long expectedVersion);
}
