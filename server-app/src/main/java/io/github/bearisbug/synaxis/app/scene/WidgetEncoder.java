package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import net.kyori.adventure.text.Component;

import java.util.Set;

/**
 * Turns the widget a plugin sets into the widget {@code synaxis:play} carries. Skeleton: every widget comes out as the same empty text.
 */
final class WidgetEncoder {

    record Encoded(io.github.bearisbug.synaxis.game.protocol.play.Widget widget, int bytes,
                   Set<String> buttonActions) {
    }

    private WidgetEncoder() {
    }

    static Encoded encode(String pluginId, Widget widget) {
        return new Encoded(io.github.bearisbug.synaxis.game.protocol.play.Widget.text(0, 0, EncodedComponent.NONE, 0), 0,
                Set.of());
    }

    static EncodedComponent component(Component component) {
        return new EncodedComponent(ComponentBytes.encode(component));
    }
}
