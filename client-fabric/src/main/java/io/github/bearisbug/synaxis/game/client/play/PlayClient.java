package io.github.bearisbug.synaxis.game.client.play;

import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayMessage;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue;
import io.github.bearisbug.synaxis.game.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.game.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.game.protocol.play.Widget;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's mirror of its connection's scene and HUD ({@code docs/DESIGN-PLAY.md} §6.4.3,
 * {@code ENT-PlayScene}): the current scene from {@code S2C_SCENE} and the {@code TEXT}s of {@code S2C_HUD},
 * addressed by {@code (owner, widget_id)}. The other kinds of version 1 and the actions are not mirrored yet;
 * a kind this version does not know is dropped with a warning ({@code API-PLAY-003}).
 * <p>
 * One connection's worth: {@link #reset} at {@code ClientPlayConnectionEvents.INIT}. Single threaded, like
 * {@code RtsClient}: the payloads, that event and the HUD all run on the client thread.
 */
public final class PlayClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /**
     * The scene the connection is in.
     *
     * @param seq   its {@code scene_seq}
     * @param id    its scene id
     * @param title its title
     */
    public record Scene(int seq, String id, Component title) {
    }

    /**
     * A {@code TEXT} on the HUD.
     *
     * @param owner    the plugin that set it
     * @param widgetId its id within the owner
     * @param slot     its slot, 0 {@code TOP_LEFT} to 6 {@code CENTER}
     * @param order    its position within the slot
     * @param text     the line
     */
    public record Text(String owner, String widgetId, int slot, int order, Component text) {
    }

    private record Address(String owner, String widgetId) {
    }

    private @Nullable Scene scene;
    private final Map<Address, Text> texts = new LinkedHashMap<>();

    /**
     * Applies one message of the server.
     *
     * @param message    the message
     * @param registries the registries of the connection, for the components
     */
    public void receive(PlayMessage message, RegistryAccess registries) {
        if (message instanceof S2CScene enter) {
            scene = new Scene(enter.sceneSeq(), enter.sceneId(), component(enter.title(), registries));
        } else if (message instanceof S2CHud hud) {
            for (final S2CHud.Op op : hud.ops()) apply(op, registries);
        }
    }

    private void apply(S2CHud.Op op, RegistryAccess registries) {
        final Address address = new Address(op.owner(), op.widgetId());
        switch (op) {
            case S2CHud.SetWidget set -> {
                final Widget widget = set.widget();
                if (widget.kind() == Widget.TEXT) {
                    final EncodedComponent text = widget.prop(Widget.PROP_TEXT)
                            .map(value -> value instanceof PropValue.Component component ? component.value() : null)
                            .orElse(EncodedComponent.NONE);
                    texts.put(address, new Text(set.owner(), set.widgetId(), widget.slot(), widget.order(),
                            component(text, registries)));
                    return;
                }
                texts.remove(address);
                if (!isVersionOneKind(widget.kind())) {
                    LOGGER.warn("Dropped a widget of unknown kind {}: owner={} widget_id={}", widget.kind(), set.owner(),
                            set.widgetId());
                }
            }
            case S2CHud.RemoveWidget _ -> texts.remove(address);
            case S2CHud.ClearOwner clear -> texts.keySet().removeIf(key -> key.owner().equals(clear.owner()));
        }
    }

    private static boolean isVersionOneKind(int kind) {
        return kind == Widget.TEXT || kind == Widget.TIMER || kind == Widget.SCOREBOARD || kind == Widget.PANEL;
    }

    /** A component the codec cannot read shows as empty text, with one warning, and keeps the connection. */
    private static Component component(EncodedComponent encoded, RegistryAccess registries) {
        try {
            return PlayComponents.decode(encoded, registries);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("A play component could not be decoded, showing empty text: {}", e.getMessage());
            return Component.empty();
        }
    }

    /**
     * The scene the connection is in.
     *
     * @return the scene, null before the connection's first {@code S2C_SCENE}
     */
    public @Nullable Scene scene() {
        return scene;
    }

    /**
     * The {@code TEXT}s, by slot and then by order; ties keep the order they were first set in.
     *
     * @return a copy
     */
    public List<Text> texts() {
        return texts.values().stream().sorted(Comparator.comparingInt(Text::slot).thenComparingInt(Text::order)).toList();
    }

    /** Forgets everything: a new connection starts from nothing (§6.4.3). */
    public void reset() {
        scene = null;
        texts.clear();
    }
}
