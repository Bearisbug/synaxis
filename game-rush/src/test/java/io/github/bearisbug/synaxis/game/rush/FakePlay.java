package io.github.bearisbug.synaxis.game.rush;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.plugin.play.ActionHandler;
import io.github.bearisbug.synaxis.plugin.play.Hud;
import io.github.bearisbug.synaxis.plugin.play.PluginPlay;
import io.github.bearisbug.synaxis.plugin.play.SceneSpec;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The host's scene layer as rush sees it ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}): it records
 * every call in order, and replays them into what each player has now. The real one is the host's
 * {@code SceneService}, which a plugin must not depend on.
 */
final class FakePlay implements PluginPlay {

    /**
     * One call of the plugin.
     *
     * @param kind   {@code enter}, {@code leave}, {@code set}, {@code remove} or {@code clear}
     * @param player the player
     * @param value  the scene, the widget or the widget id
     */
    record Call(String kind, Player player, @Nullable Object value) {
    }

    final List<Call> calls = new CopyOnWriteArrayList<>();
    final Map<String, ActionHandler> actions = new ConcurrentHashMap<>();

    @Override
    public void enter(Player player, SceneSpec scene) {
        calls.add(new Call("enter", player, scene));
    }

    @Override
    public void leave(Player player) {
        calls.add(new Call("leave", player, null));
    }

    @Override
    public Optional<String> currentScene(Player player) {
        final SceneSpec scene = scene(player);
        return scene == null ? Optional.empty() : Optional.of("rush:" + scene.id());
    }

    @Override
    public Hud hud(Player player) {
        return new Hud() {
            @Override
            public void set(Widget widget) {
                calls.add(new Call("set", player, widget));
            }

            @Override
            public void remove(String widgetId) {
                calls.add(new Call("remove", player, widgetId));
            }

            @Override
            public void clear() {
                calls.add(new Call("clear", player, null));
            }
        };
    }

    @Override
    public void onAction(String actionId, ActionHandler handler) {
        if (actions.putIfAbsent(actionId, handler) != null) throw new IllegalStateException(actionId + " is registered");
    }

    /**
     * The scene a player is in now.
     *
     * @param player the player
     * @return the last scene entered, null for none
     */
    @Nullable SceneSpec scene(Player player) {
        final List<Call> calls = of(player);
        for (int i = calls.size() - 1; i >= 0; i--) {
            switch (calls.get(i).kind()) {
                case "enter" -> {
                    return (SceneSpec) calls.get(i).value();
                }
                case "leave" -> {
                    return null;
                }
                default -> {
                }
            }
        }
        return null;
    }

    /**
     * The widgets a player has now: entering a scene clears them.
     *
     * @param player the player
     * @return the widgets by id
     */
    Map<String, Widget> widgets(Player player) {
        final Map<String, Widget> widgets = new LinkedHashMap<>();
        for (final Call call : of(player)) {
            switch (call.kind()) {
                case "enter", "leave", "clear" -> widgets.clear();
                case "set" -> {
                    final Widget widget = (Widget) call.value();
                    widgets.put(widget.id(), widget);
                }
                case "remove" -> widgets.remove((String) call.value());
                default -> throw new AssertionError(call.kind());
            }
        }
        return widgets;
    }

    /**
     * Every widget set for a player under an id, in order.
     *
     * @param player   the player
     * @param widgetId the widget id
     * @return the widgets
     */
    List<Widget> sets(Player player, String widgetId) {
        return of(player).stream()
                .filter(call -> call.kind().equals("set") && ((Widget) call.value()).id().equals(widgetId))
                .map(call -> (Widget) call.value())
                .toList();
    }

    /**
     * The calls for one player.
     *
     * @param player the player
     * @return the calls, in order
     */
    List<Call> of(Player player) {
        return calls.stream().filter(call -> call.player() == player).toList();
    }
}
