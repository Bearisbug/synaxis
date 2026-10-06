package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Player data of one plugin, stored by the host ({@code docs/DESIGN-PROD.md} API-PROD-005).
 * <p>
 * A plugin sees sections, never a database: the right to write a character is checked in the same
 * statement as every write, which only holds while nobody can write around the host.
 * <p>
 * Every registered section of a player is loaded before the player enters the world, and a section
 * the player has nothing stored for starts as {@link DataSection#createDefault()}. The host saves
 * the dirty sections every 60 seconds, on logout and on shutdown.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PlayerDataStore {

    /**
     * Registers a section of this plugin. Only allowed inside {@link SynaxisPlugin#onEnable}.
     *
     * @param section the section, whose id starts with {@code <plugin_id>:}
     * @param <T>     the model of the section
     * @throws IllegalStateException if called outside {@code onEnable}, if the id is outside this
     *                               plugin's namespace, or if it is already registered
     */
    <T> void register(DataSection<T> section);

    /**
     * The current value of a section of a player. Called on the tick thread that owns the player.
     *
     * @param player  an online player
     * @param section a section this plugin registered
     * @param <T>     the model of the section
     * @return the value, which the caller may change in place and then {@link #markDirty}
     * @throws IllegalStateException if this plugin did not register the section, or the player's
     *                               data is not loaded
     */
    <T> T get(Player player, DataSection<T> section);

    /**
     * Marks a section of a player as changed, so the next save writes it.
     *
     * @param player  an online player
     * @param section a section this plugin registered
     */
    void markDirty(Player player, DataSection<?> section);

    /**
     * Writes every dirty section of the player now. The future completes on a virtual thread: do
     * not touch an entity from its callbacks.
     *
     * @param player an online player
     * @return the outcome
     */
    CompletableFuture<SaveResult> saveNow(Player player);

    /**
     * Writes first, applies after: the staged values of every participant are written in one
     * transaction that checks every participant's right to write, and only when it commits does
     * {@code apply} run, on the tick thread. If any check fails nothing is written and
     * {@code apply} never runs. Every participant must be on this shard.
     *
     * @param staged the value each section of each participant has after the operation
     * @param apply  what makes the operation visible in game
     * @return the outcome
     */
    CompletableFuture<SaveResult> commitTogether(Map<Player, Map<DataSection<?>, Object>> staged, Runnable apply);

    /**
     * Whether the player is frozen: while the shard cannot reach the database, while it saves
     * everyone for a shutdown, and while this player's own logout or transfer is being saved.
     * <p>
     * A frozen player can move and fight, but nothing that changes who owns an item may happen:
     * the host cancels the pickups and drops dispatched while it is frozen before any plugin
     * listener of any scope sees them (see {@link PluginEvents} for a freeze in the middle of a
     * dispatch and for undoing that cancel), and a plugin must check this before a trade, a mail or
     * a shop purchase. Callable from any thread.
     *
     * @param player the player
     * @return true while the player is frozen
     */
    boolean isFrozen(Player player);
}
