package io.github.bearisbug.synaxis.plugin.play;

import org.jetbrains.annotations.ApiStatus;

/**
 * The widgets one plugin shows to one player ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
 * <p>
 * A widget is addressed by this plugin's id and its {@link Widget#id()}, so two plugins never
 * overwrite each other. Every change sends the whole widget at the end of the player's tick; two
 * changes of the same widget in one tick reach the client as the later one.
 * <p>
 * The three methods are called on the tick thread that owns the player or in the serial part of the
 * tick, as {@link PluginPlay} says; on any other thread they throw an {@link IllegalStateException}.
 * <p>
 * Only the host implements this interface: a plugin is handed one and calls it.
 */
@ApiStatus.NonExtendable
public interface Hud {

    /**
     * Adds a widget, or replaces the widget of this plugin with the same id.
     *
     * @param widget the widget
     * @throws IllegalArgumentException if a component of it encodes to more than 2,048 bytes, the
     *                                  whole widget to more than 4,096, or the action id of a panel
     *                                  button carries another plugin's namespace or was not
     *                                  registered with {@link PluginPlay#onAction}; nothing changes
     * @throws IllegalStateException    if called on a thread that neither owns the player nor runs
     *                                  the serial part of the tick
     */
    void set(Widget widget);

    /**
     * Removes a widget of this plugin; nothing happens if there is none with that id.
     *
     * @param widgetId the id of the widget
     */
    void remove(String widgetId);

    /**
     * Removes every widget of this plugin.
     */
    void clear();
}
