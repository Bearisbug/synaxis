package io.github.bearisbug.synaxis.game.client.play;

import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The HUD element {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} §6.4.8): the {@code TEXT}s of
 * {@link PlayClient}, one per line from the top left corner, in their slot and order. A connection that has
 * not entered a scene draws nothing, so a server without the scene layer, the light and far view fixtures
 * among them, renders exactly as without this element.
 */
public final class PlayHud {

    /** The element's id in Fabric's HUD layers. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("synaxis", "play");

    private static final int MARGIN = 2;

    private PlayHud() {
    }

    /**
     * Adds the element on top of the vanilla HUD.
     *
     * @param play the connection's mirror the element draws
     */
    public static void register(PlayClient play) {
        HudElementRegistry.addLast(ID, (graphics, _) -> {
            final Font font = Minecraft.getInstance().font;
            int y = MARGIN;
            for (final Component line : lines(play)) {
                graphics.text(font, line, MARGIN, y, 0xFFFFFFFF);
                y += font.lineHeight + 1;
            }
        });
    }

    /**
     * What the element draws, top to bottom; an empty component keeps its line and draws nothing on it.
     *
     * @param play the connection's mirror
     * @return the lines, none before the connection's first scene
     */
    public static List<Component> lines(PlayClient play) {
        if (play.scene() == null) return List.of();
        return play.texts().stream().map(PlayClient.Text::text).toList();
    }
}
