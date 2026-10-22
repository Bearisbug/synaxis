package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.plugin.play.Widget;
import io.github.bearisbug.synaxis.protocol.MsgBuf;
import io.github.bearisbug.synaxis.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.protocol.play.PropValue;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the widget a plugin sets into the widget {@code synaxis:play} carries ({@code docs/DESIGN-PLAY.md}
 * {@code API-PLAY-003}), checking the sizes only the encoding can tell: a component of at most
 * {@value ComponentBytes#MAX_BYTES} bytes and a whole widget of at most
 * {@value PlayProtocol#MAX_WIDGET_BYTES} (§6.4.3). The limits that need no encoding are the records' own.
 */
final class WidgetEncoder {

    /**
     * One encoded widget.
     *
     * @param widget        the widget as the wire carries it
     * @param buttonActions the full action ids of its buttons, empty unless it is a panel
     */
    record Encoded(io.github.bearisbug.synaxis.protocol.play.Widget widget, Set<String> buttonActions) {
    }

    private WidgetEncoder() {
    }

    /**
     * Encodes one widget.
     *
     * @param pluginId the plugin setting the widget, which owns the action ids of its buttons
     * @param widget   the widget
     * @return the encoded widget
     * @throws IllegalArgumentException if a component encodes to more than {@value ComponentBytes#MAX_BYTES}
     *                                  bytes, the widget to more than {@value PlayProtocol#MAX_WIDGET_BYTES},
     *                                  or a button's action id is not one of the plugin's
     */
    static Encoded encode(String pluginId, Widget widget) {
        final Set<String> buttonActions = new LinkedHashSet<>();
        final io.github.bearisbug.synaxis.protocol.play.Widget encoded = switch (widget) {
            case Widget.Text text -> io.github.bearisbug.synaxis.protocol.play.Widget.text(
                    text.slot().ordinal(), text.order(), component(text.text()), (int) text.ttl().toMillis());
            case Widget.Timer timer -> io.github.bearisbug.synaxis.protocol.play.Widget.timer(
                    timer.slot().ordinal(), timer.order(), component(timer.label()),
                    (int) timer.remaining().toMillis(), timer.running());
            case Widget.Scoreboard scoreboard -> io.github.bearisbug.synaxis.protocol.play.Widget.scoreboard(
                    scoreboard.slot().ordinal(), scoreboard.order(), component(scoreboard.title()),
                    rows(scoreboard.rows()));
            case Widget.Panel panel -> {
                final List<PropValue.Button> buttons = new ArrayList<>();
                for (final Widget.Button button : panel.buttons()) {
                    final String actionId = SceneService.qualify(pluginId, button.actionId());
                    buttonActions.add(actionId);
                    buttons.add(new PropValue.Button(actionId, component(button.label())));
                }
                yield io.github.bearisbug.synaxis.protocol.play.Widget.panel(component(panel.title()),
                        rows(panel.rows()), buttons);
            }
        };
        final MsgBuf buf = MsgBuf.writing();
        encoded.write(buf);
        final int bytes = buf.toByteArray().length;
        if (bytes > PlayProtocol.MAX_WIDGET_BYTES)
            throw new IllegalArgumentException("widget " + widget.id() + " of plugin_id=" + pluginId + " encodes to "
                    + bytes + " bytes, over the " + PlayProtocol.MAX_WIDGET_BYTES + " a widget may take");
        return new Encoded(encoded, Set.copyOf(buttonActions));
    }

    /**
     * Encodes one component through {@link ComponentBytes}, as the wire carries it.
     *
     * @param component the component
     * @return its bytes behind their length
     * @throws IllegalArgumentException if the component encodes to more than
     *                                  {@value ComponentBytes#MAX_BYTES} bytes
     */
    static EncodedComponent component(Component component) {
        return new EncodedComponent(ComponentBytes.encode(component));
    }

    private static List<PropValue.Row> rows(List<Widget.Row> rows) {
        final List<PropValue.Row> encoded = new ArrayList<>(rows.size());
        for (final Widget.Row row : rows)
            encoded.add(new PropValue.Row(component(row.label()), row.value(), row.highlight()));
        return encoded;
    }
}
