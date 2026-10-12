package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.play.ActionSlot;
import io.github.bearisbug.synaxis.plugin.play.CameraSpec;
import io.github.bearisbug.synaxis.plugin.play.CursorMode;
import io.github.bearisbug.synaxis.plugin.play.HudSlot;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.plugin.play.SceneSpec;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The texts, scenes and widgets of rush, and the widgets each player was last sent
 * ({@code docs/DESIGN-PLAY.md} §6.7.5).
 * <p>
 * A widget is only set again when it differs from the one the player has, and a timer only when the
 * player has none, since the client counts it down by itself. Entering a scene through
 * {@link #enter} forgets what the player had: the host cleared the scene owner's widgets.
 * <p>
 * The record of each player is written on that player's tick thread and in the serial part of the
 * tick, which never run at the same time; the map holding the records is concurrent because two
 * players' threads do.
 */
final class RushHud {

    /** The action of key 1 in the lobby, joining or leaving the queue. */
    static final String QUEUE = "queue";

    /** The action of the button of the result panel, back to the lobby. */
    static final String BACK = "back";

    static final String HINT = "hint";
    static final String TIMER = "timer";
    static final String OBJECTIVE = "objective";
    static final String SCORE = "score";
    static final String CAPTURE = "capture";
    static final String RESULT = "result";

    /**
     * The translate keys of rush with their English text as the fallback, the same as in
     * {@code en_us.json} of the client mod (§6.7.5, {@code ADR-PLAY-007}).
     */
    private static final Map<String, String> FALLBACKS = Map.ofEntries(
            Map.entry("rush.scene.lobby", "Beacon Rush: lobby"),
            Map.entry("rush.scene.round", "Beacon Rush: round"),
            Map.entry("rush.action.queue", "Join or leave the queue"),
            Map.entry("rush.action.back", "Back to the lobby"),
            Map.entry("rush.hint", "Press action key 1 (default H) to join or leave the queue. Queued: %s"),
            Map.entry("rush.hint.failed", "The arena could not be prepared. Press action key 1 (default H) to queue again."),
            Map.entry("rush.timer.countdown", "Round starts in"),
            Map.entry("rush.timer.round", "Time left"),
            Map.entry("rush.objective", "Hold beacons A, B and C: first to %s points wins"),
            Map.entry("rush.score", "Score"),
            Map.entry("rush.capture", "Capturing %s: %s%%"),
            Map.entry("rush.result", "Round over"),
            Map.entry("rush.result.row", "%s. %s (%s captures)"));

    /** The lobby: action key 1 queues, with a cooldown of 2 s. */
    static final SceneSpec LOBBY = new SceneSpec("lobby", text("rush.scene.lobby"), new CameraSpec.Vanilla(),
            CursorMode.LOCKED, List.of(new ActionSlot(0, QUEUE, text("rush.action.queue"), Duration.ofSeconds(2))));

    /** A round: no action key in S1. */
    static final SceneSpec ROUND = new SceneSpec("round", text("rush.scene.round"), new CameraSpec.Vanilla(),
            CursorMode.LOCKED, List.of());

    private final PluginPlay play;
    private final Map<UUID, Map<String, Widget>> shown = new ConcurrentHashMap<>();

    RushHud(PluginPlay play) {
        this.play = play;
    }

    /**
     * The translate keys rush sends.
     *
     * @return the 13 keys of §6.7.5
     */
    static Set<String> keys() {
        return FALLBACKS.keySet();
    }

    /**
     * A text of rush: its translate key with the English fallback.
     *
     * @param key       one of {@link #keys()}
     * @param arguments the arguments of the key
     * @return the component
     */
    static Component text(String key, ComponentLike... arguments) {
        return Component.translatable(key, arguments).fallback(Objects.requireNonNull(FALLBACKS.get(key), key));
    }

    static Widget hint(int queued) {
        return new Widget.Text(HINT, HudSlot.LEFT, 0, text("rush.hint", Component.text(queued)), Duration.ZERO);
    }

    static Widget hintFailed() {
        return new Widget.Text(HINT, HudSlot.LEFT, 0, text("rush.hint.failed"), Duration.ZERO);
    }

    static Widget timer(String key, Duration remaining, boolean running) {
        return new Widget.Timer(TIMER, HudSlot.TOP_CENTER, 0, text(key), remaining, running);
    }

    static Widget objective(int targetScore) {
        return new Widget.Text(OBJECTIVE, HudSlot.TOP_CENTER, 1, text("rush.objective", Component.text(targetScore)),
                Duration.ZERO);
    }

    static Widget score(List<Widget.Row> rows) {
        return new Widget.Scoreboard(SCORE, HudSlot.TOP_RIGHT, 0, text("rush.score"), rows);
    }

    static Widget capture(char letter, int percent) {
        return new Widget.Text(CAPTURE, HudSlot.BOTTOM, 0,
                text("rush.capture", Component.text(String.valueOf(letter)), Component.text(percent)), Duration.ZERO);
    }

    static Widget.Row resultRow(int rank, String name, int captures, int score, boolean highlight) {
        return new Widget.Row(text("rush.result.row", Component.text(rank), Component.text(name), Component.text(captures)),
                String.valueOf(score), highlight);
    }

    static Widget result(List<Widget.Row> rows) {
        return new Widget.Panel(RESULT, text("rush.result"), rows,
                List.of(new Widget.Button(BACK, text("rush.action.back"))));
    }

    /**
     * Puts a player into a scene and forgets the widgets the player had.
     *
     * @param player the player
     * @param scene  the scene
     */
    void enter(Player player, SceneSpec scene) {
        play.enter(player, scene);
        shown.remove(player.getUuid());
    }

    /**
     * Sets a widget unless the player has the same one.
     *
     * @param player the player
     * @param widget the widget
     */
    void show(Player player, Widget widget) {
        final Map<String, Widget> widgets = widgets(player);
        if (widget.equals(widgets.get(widget.id()))) return;
        play.hud(player).set(widget);
        widgets.put(widget.id(), widget);
    }

    /**
     * Sets a widget unless the player has one with its id, whatever it shows: a running timer.
     *
     * @param player the player
     * @param widget the widget
     */
    void showOnce(Player player, Widget widget) {
        final Map<String, Widget> widgets = widgets(player);
        if (widgets.containsKey(widget.id())) return;
        play.hud(player).set(widget);
        widgets.put(widget.id(), widget);
    }

    /**
     * Removes a widget the player has.
     *
     * @param player   the player
     * @param widgetId the id of the widget
     */
    void hide(Player player, String widgetId) {
        final Map<String, Widget> widgets = shown.get(player.getUuid());
        if (widgets == null || widgets.remove(widgetId) == null) return;
        play.hud(player).remove(widgetId);
    }

    /**
     * Forgets a player who left the server.
     *
     * @param player the player
     */
    void forget(Player player) {
        shown.remove(player.getUuid());
    }

    private Map<String, Widget> widgets(Player player) {
        return shown.computeIfAbsent(player.getUuid(), _ -> new ConcurrentHashMap<>());
    }
}
