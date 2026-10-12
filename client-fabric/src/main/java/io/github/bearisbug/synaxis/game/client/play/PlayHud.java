package io.github.bearisbug.synaxis.game.client.play;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * The HUD element {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} §6.4.8): the {@code TEXT}s, {@code TIMER}s
 * and {@code SCOREBOARD}s of {@link PlayClient}, placed by {@link WidgetLayout}; a {@code PANEL} is a screen of
 * its own, {@link PanelScreen}. A connection that has not entered a scene draws nothing, so a server without
 * the scene layer, the light and far view fixtures among them, renders exactly as without this element.
 */
public final class PlayHud {

    /** The element's id in Fabric's HUD layers. */
    public static final Identifier ID = Identifier.fromNamespaceAndPath("synaxis", "play");

    private static final int WHITE = 0xFFFFFFFF;
    /** The colour of a scoreboard's values, vanilla's sidebar red. */
    private static final int VALUE = 0xFFFF5555;
    private static final int FRAME = 0x66000000;
    private static final int TITLE_BAND = 0x66000000;
    private static final int HIGHLIGHT_BAND = 0x40FFFFFF;
    /** The space inside a scoreboard's frame. */
    private static final int PAD = 2;
    /** The space between a row's name and its value. */
    private static final int VALUE_GAP = 8;

    /**
     * One line of a widget.
     *
     * @param text      the text, from the left edge
     * @param value     the value, flush right, null for none
     * @param highlight whether the line gets a band
     */
    public record Line(Component text, @Nullable String value, boolean highlight) {
    }

    /**
     * What one widget draws.
     *
     * @param entry  the widget
     * @param framed whether it sits on a frame with its first line as a centred title, which a scoreboard does
     * @param lines  its lines, top to bottom
     */
    public record Block(PlayClient.Entry entry, boolean framed, List<Line> lines) {
    }

    private PlayHud() {
    }

    /**
     * Adds the element on top of the vanilla HUD.
     *
     * @param play     the connection's mirror the element draws
     * @param bossBars how many boss bars vanilla has, which {@code TOP_CENTER} makes room for
     */
    public static void register(PlayClient play, IntSupplier bossBars) {
        HudElementRegistry.addLast(ID, (graphics, _) -> draw(graphics, Minecraft.getInstance().font, play, bossBars.getAsInt()));
    }

    /**
     * What the element draws, in layout order; an empty component keeps its line and draws nothing on it.
     *
     * @param play the connection's mirror
     * @return the widgets with their lines, none before the connection's first scene
     */
    public static List<Block> blocks(PlayClient play) {
        if (play.scene() == null) return List.of();
        final List<Block> blocks = new ArrayList<>();
        for (final PlayClient.Entry entry : play.widgets()) {
            if (entry.content() instanceof PlayClient.Text text) blocks.add(new Block(entry, false, List.of(new Line(text.text(), null, false))));
        }
        return blocks;
    }

    /**
     * A {@code TIMER}'s line: its label, a space and the time left as minutes and seconds.
     *
     * @param label       the label
     * @param remainingMs the time left
     * @return the line
     */
    static Component timerLine(Component label, long remainingMs) {
        throw new UnsupportedOperationException("not yet");
    }

    /**
     * The time left as {@code m:ss}, the seconds rounded up so that {@code 0:00} means it ran out.
     *
     * @param remainingMs the time left
     * @return the text
     */
    static String clock(long remainingMs) {
        throw new UnsupportedOperationException("not yet");
    }

    private static void draw(GuiGraphicsExtractor graphics, Font font, PlayClient play, int bossBars) {
        int y = PAD;
        for (final Block block : blocks(play)) {
            graphics.text(font, block.lines().getFirst().text(), PAD, y, WHITE);
            y += font.lineHeight + 1;
        }
    }
}
