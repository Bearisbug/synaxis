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
 * the dirty sections every 60 seconds, on logout and on shutdown. The logout snapshot is taken once
 * every plugin's {@code PlayerDisconnectEvent} listener has run, so a section changed and marked
 * dirty there is in it, and so is the player's inventory, position and health as those listeners
 * left them. The other disconnects come after the player's data was written: on shutdown the save
 * precedes the kick, after a transfer the write that handed the character over; a change made in a
 * disconnect listener then is lost. Write a section as it changes, or on
 * shutdown in {@link PluginLifecycle#onShutdownSave}.
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
     * @return the value, which the caller may change in place and then {@link #markDirty}; not while
     *         a {@link #commitTogether} of the player is under way, whose staged value replaces it
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
     * Writes every dirty section of the player now. Callable from any thread: the snapshot reads the
     * player, so off the tick thread that owns the player it is taken on the player's next tick,
     * and a player that is gone or frozen by then, or not ticked within 10 seconds, gets
     * {@link SaveResult#UNAVAILABLE} without a write.
     *
     * @param player an online player
     * @return the outcome. A frozen player, or one that is not online here, gets a future that is
     *         complete already, whose callbacks run on the calling thread; so may a database that
     *         answers fast. Otherwise the future completes on another thread: a virtual thread of
     *         the host, the player's tick thread when that tick finds the player gone or frozen, or
     *         a timer thread of the JDK when no tick came within the 10 seconds. Either way a
     *         callback neither touches an entity nor blocks: hand an effect on an entity over with
     *         {@link PluginScheduler#runOn}, a blocking call with {@link PluginScheduler#runAsync}
     */
    CompletableFuture<SaveResult> saveNow(Player player);

    /**
     * Writes first, applies after: the staged values of every participant are written in one
     * transaction that checks every participant's right to write, and only when it commits does
     * {@code apply} run, once, on the tick scheduler thread in the serial part of the next tick.
     * There it may touch every participant, whichever tick thread each of them is ticked on; a
     * participant may have left by then, so {@code apply} checks {@link Player#isRemoved()} first.
     * If any check fails nothing is written and {@code apply} never runs. Every participant must be
     * on this shard. An empty map writes nothing and saves: {@code apply} runs as it would after a
     * commit, and the outcome is {@link SaveResult#SAVED}.
     * <p>
     * A staged value becomes the section's value when the commit is written; until then
     * {@link #get} returns the old one. Stage a new object, a deep copy for a mutable model, never
     * the value {@code get} returned changed in place: a commit that fails leaves the host's values
     * as they were and is not retried, the caller decides whether to do it again. The maps are copied
     * by the call: changing them once it returned changes nothing of the commit. From the call until
     * the future completes every participant is frozen ({@link #isFrozen}): the host cancels what
     * {@link PluginEvents} lists, and a second {@code commitTogether} or a {@link #saveNow} of a
     * participant gets {@link SaveResult#UNAVAILABLE}. Do not change these sections in place
     * meanwhile either: the staged value replaces whatever they turned into.
     *
     * @param staged the value each section of each participant has after the operation
     * @param apply  what makes the operation visible in game
     * @return the outcome, always one {@link SaveResult}: a database that fails in any way gives
     *         {@link SaveResult#UNAVAILABLE}. A frozen participant, or one that is not online here,
     *         gets a future that is complete already, whose callbacks run on the calling thread;
     *         otherwise it completes on a virtual thread of the host, before {@code apply} runs and
     *         not on its thread. A callback neither touches an entity nor blocks, as for
     *         {@link #saveNow}
     * @throws IllegalArgumentException if a participant is staged with a null section map, a value is
     *                                  null, or a section cannot encode its value
     * @throws IllegalStateException    if this plugin did not register one of the sections
     */
    CompletableFuture<SaveResult> commitTogether(Map<Player, Map<DataSection<?>, Object>> staged, Runnable apply);

    /**
     * Whether the player is frozen: while the shard cannot reach the database, while it saves
     * everyone for a shutdown, while this player's own logout or transfer is being saved, and while a
     * {@link #commitTogether} it takes part in is under way.
     * <p>
     * A frozen player can move and fight, but nothing that changes who owns an item may happen: the
     * host cancels the events {@link PluginEvents} lists for a frozen player, and a plugin must check
     * this before a trade, a mail or a shop purchase. Callable from any thread.
     *
     * @param player the player
     * @return true while the player is frozen
     */
    boolean isFrozen(Player player);
}
