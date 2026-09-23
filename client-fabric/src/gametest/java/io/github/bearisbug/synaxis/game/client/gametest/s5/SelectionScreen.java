package io.github.bearisbug.synaxis.game.client.gametest.s5;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The transparent screen path of S5's input half: a screen that does not pause, draws no
 * background, and turns a press, drag and release into a selection box. Every mouse event it gets
 * is logged in GUI coordinates.
 */
public final class SelectionScreen extends Screen {

    public final List<String> events = new CopyOnWriteArrayList<>();
    private double startX, startY, endX, endY;
    private boolean dragging;

    public SelectionScreen() {
        super(Component.empty());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No blur and no dimming: the world stays as it is under the cursor.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!dragging) return;
        final int x0 = (int) Math.min(startX, endX), y0 = (int) Math.min(startY, endY);
        final int x1 = (int) Math.max(startX, endX), y1 = (int) Math.max(startY, endY);
        graphics.fill(x0, y0, x1, y1, 0x3300FF00);
        graphics.outline(x0, y0, x1 - x0, y1 - y0, 0xFF00FF00);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        events.add("press " + event.button() + " " + xy(event));
        startX = endX = event.x();
        startY = endY = event.y();
        dragging = true;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        events.add("drag " + xy(event));
        endX = event.x();
        endY = event.y();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        events.add("release " + event.button() + " " + xy(event));
        dragging = false;
        return true;
    }

    private static String xy(MouseButtonEvent event) {
        return String.format("%.1f,%.1f", event.x(), event.y());
    }
}
