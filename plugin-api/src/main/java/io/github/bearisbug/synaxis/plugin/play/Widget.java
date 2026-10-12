package io.github.bearisbug.synaxis.plugin.play;

import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.List;

/**
 * One element of the HUD ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-003}, {@code API-PLAY-005}).
 * <p>
 * The kinds are a closed catalogue the client mod draws; version 1 of {@code synaxis:play} has the
 * four below. Text is sent as a component, a translate key with a fallback where the text is new.
 * The host checks the encoded size when the widget is set ({@link Hud#set}); the limits that need no
 * encoding are checked here, and break with an {@link IllegalArgumentException}.
 */
public sealed interface Widget {

    /**
     * The id of the widget, unique among this plugin's widgets: 1 to 32 bytes of UTF-8.
     *
     * @return the widget id
     */
    String id();

    /**
     * Where the widget goes.
     *
     * @return the slot
     */
    HudSlot slot();

    /**
     * The position among the widgets of the same owner in the same slot, lowest first.
     *
     * @return the order
     */
    int order();

    /**
     * A line of text.
     *
     * @param id    the widget id
     * @param slot  where it goes
     * @param order its position in the slot
     * @param text  the text
     * @param ttl   how long it stays, {@link Duration#ZERO} for until it is removed; 0 to
     *              {@link Integer#MAX_VALUE} milliseconds
     */
    record Text(String id, HudSlot slot, int order, Component text, Duration ttl) implements Widget {
    }

    /**
     * A label with a remaining time the client counts down by itself while it runs.
     *
     * @param id        the widget id
     * @param slot      where it goes
     * @param order     its position in the slot
     * @param label     the label
     * @param remaining the time left when the client receives it; 0 to {@link Integer#MAX_VALUE}
     *                  milliseconds
     * @param running   whether the client counts down; a stopped timer shows {@code remaining}
     */
    record Timer(String id, HudSlot slot, int order, Component label, Duration remaining, boolean running)
            implements Widget {
    }

    /**
     * A title over rows of names and values.
     *
     * @param id    the widget id
     * @param slot  where it goes
     * @param order its position in the slot
     * @param title the title
     * @param rows  at most 16 rows; copied
     */
    record Scoreboard(String id, HudSlot slot, int order, Component title, List<Row> rows) implements Widget {
    }

    /**
     * A panel in the middle of the screen, which the client opens as a screen that does not pause
     * the game: a title, rows and buttons. It always sits in {@link HudSlot#CENTER}.
     *
     * @param id      the widget id
     * @param title   the title
     * @param rows    at most 12 rows; copied
     * @param buttons at most 4 buttons; copied
     */
    record Panel(String id, Component title, List<Row> rows, List<Button> buttons) implements Widget {

        @Override
        public HudSlot slot() {
            return HudSlot.CENTER;
        }

        @Override
        public int order() {
            return 0;
        }
    }

    /**
     * One row of a scoreboard or a panel.
     *
     * @param label     the name of the row
     * @param value     its value, at most 32 bytes of UTF-8
     * @param highlight whether the row is highlighted, such as the player's own
     */
    record Row(Component label, String value, boolean highlight) {
    }

    /**
     * One button of a panel. Pressing it sends its action like an action key does, and the host
     * accepts the action ids of the buttons on the player's panels besides the scene's slots.
     *
     * @param actionId the action it sends, registered with {@link PluginPlay#onAction}, with or
     *                 without this plugin's prefix
     * @param label    what the button says
     */
    record Button(String actionId, Component label) {
    }
}
