package io.github.bearisbug.synaxis.plugin.play;

import io.github.bearisbug.synaxis.entity.Player;
import org.jetbrains.annotations.ApiStatus;

import java.util.Optional;

/**
 * Scenes, the HUD and actions of one plugin ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
 * <p>
 * A scene is what a connection is in right now: its id, title, camera, cursor and action slots. At
 * most one plugin of the process, the scene owner, moves players between scenes; in a gameplay
 * process that is the plugin holding the admission slot. Every other plugin is an overlay: it may
 * set widgets and register actions in its own namespace, but not enter or leave a scene.
 * <p>
 * Scene ids and action ids, including the action ids of panel buttons, are
 * {@code <plugin_id>:<name>}. A name may be given with or without this plugin's prefix, which the
 * host adds; a prefix that is not this plugin's id is refused with an
 * {@link IllegalArgumentException}, the same rule as the channels of
 * {@link io.github.bearisbug.synaxis.plugin.PluginChannels}. So is a name that does not match
 * {@code [a-z0-9._/-]+}, and an id that is over
 * {@value io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol#MAX_ID_BYTES} bytes of UTF-8
 * once the prefix is added.
 * <p>
 * Threads: {@link #enter}, {@link #leave}, {@link #currentScene} and the writing methods of
 * {@link #hud} are called either on the tick thread that owns the player or in the serial part of
 * the tick (a task of {@code scheduleTask}). Neither runs at the same time as the other, and the
 * host reads the same state at the end of the player's tick, so neither needs a lock. Any other
 * thread hands the call to the player with {@code runOn}: the host checks the calling thread of
 * {@link #enter}, {@link #leave} and the writing methods of {@link #hud}, and throws an
 * {@link IllegalStateException} on any other.
 * <p>
 * Nothing is sent right away: scene changes and widget changes are queued per connection and sent
 * at the end of the player's tick, the scene first.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface PluginPlay {

    /**
     * Puts a player into a scene. The scene changes on the server at once and its sequence number
     * goes up by one; the HUD changes of this plugin still queued for the player are dropped and a
     * clear of this plugin's widgets is queued in their place, so a widget set before this call in
     * the same tick never reaches the new scene. The scene is sent at the end of the player's tick,
     * or after a move of the player into another managed instance completes if one is under way,
     * whichever way the move went: when it completes with false the player is still where it was, in
     * this scene, and the plugin enters the scene that fits where the player is.
     * <p>
     * Nothing happens for a player whose connection has dropped. A connection is offline from the
     * moment it drops, while {@code PlayerDisconnectEvent} waits for the player's next tick, so
     * checking {@code isOnline()} first does not rule this out.
     *
     * @param player the player
     * @param scene  the scene
     * @throws IllegalStateException    if called on a thread that neither owns the player nor runs
     *                                  the serial part of the tick, this plugin is not the scene owner
     *                                  of the process, or the player is online but not in the play state
     * @throws IllegalArgumentException if the encoded scene is over 8 KiB, a component of it
     *                                  encodes to more than
     *                                  {@value io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent#MAX_BYTES}
     *                                  bytes or cannot be encoded, the scene id or an action id is not
     *                                  an id of this plugin (see above), or an action id was not
     *                                  registered with {@link #onAction}; the player's scene is left
     *                                  as it was
     */
    void enter(Player player, SceneSpec scene);

    /**
     * Takes a player out of its scene and queues a clear of this plugin's widgets. Nothing happens
     * for a player in no scene.
     *
     * @param player the player
     * @throws IllegalStateException if called on a thread that neither owns the player nor runs the
     *                               serial part of the tick, or this plugin is not the scene owner of
     *                               the process
     */
    void leave(Player player);

    /**
     * The scene a player is in.
     *
     * @param player the player
     * @return the full id of the scene, empty when the player is in none
     */
    Optional<String> currentScene(Player player);

    /**
     * The HUD of a player as this plugin sees it: only the widgets in this plugin's namespace. The
     * widgets the scene owner set are cleared when the scene changes; an overlay's are kept.
     *
     * @param player the player
     * @return the HUD of {@code player} for this plugin
     */
    Hud hud(Player player);

    /**
     * Registers what happens when a player sends an action of this plugin, by action key or panel
     * button. The host answers every action exactly once; it only calls the handler for an action
     * sent in the player's current scene, or before the first one, that is a key of that scene or a
     * button on one of the player's panels, and that is neither over the rate limit nor, for a key,
     * cooling down.
     *
     * @param actionId the action id, with or without this plugin's prefix
     * @param handler  the handler, called on the tick thread that owns the sender
     * @throws IllegalStateException    if called after {@code onEnable} returned, or the action is
     *                                  already registered
     * @throws IllegalArgumentException if the id is not an id of this plugin: another plugin's
     *                                  namespace, a name that does not match {@code [a-z0-9._/-]+},
     *                                  or over
     *                                  {@value io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol#MAX_ID_BYTES}
     *                                  bytes in full
     */
    void onAction(String actionId, ActionHandler handler);
}
