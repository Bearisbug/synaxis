package io.github.bearisbug.synaxis.client.play;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The screen a {@code PANEL} opens ({@code docs/DESIGN-PLAY.md} §6.4.8, {@code PAGE-PLAY-PANEL}): its title, its
 * rows and its buttons in a box in the middle, over the world, which keeps running ({@link #isPauseScreen()} is
 * false). A button sends its action through {@link PlayClient#click}; a refusal shows its reason under the
 * buttons and the panel stays. Esc closes it on this client only, until the panel's next SET.
 * <p>
 * {@link #sync} opens, updates and closes it once per client tick from the state of {@link PlayClient}, which is
 * where every decision is: which panel, whether it was closed locally, what was refused.
 */
public final class PanelScreen extends Screen {

    private static final int PAD = 6;
    private static final int MIN_WIDTH = 200;
    private static final int BUTTON_WIDTH = 98;
    private static final int BUTTON_GAP = 4;
    private static final int BOX = 0xD0101010;
    private static final int EDGE = 0xFF808080;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int VALUE = 0xFFFF5555;
    private static final int REASON = 0xFFFF5555;
    private static final int HIGHLIGHT_BAND = 0x40FFFFFF;

    private final PlayClient play;
    private PlayClient.Entry entry;
    private int left, top, boxWidth, boxHeight, rowsTop, buttonsTop;

    private PanelScreen(PlayClient play, PlayClient.Entry entry) {
        super(panel(entry).title());
        this.play = play;
        this.entry = entry;
    }

    /**
     * Brings the screen in line with the mirror; on the client thread, once per tick. The panel opens when the
     * mirror has one and no other screen is open, takes the content of a new SET in place, and closes when the
     * mirror no longer shows it.
     *
     * @param client the client
     * @param play   the connection's mirror
     */
    public static void sync(Minecraft client, PlayClient play) {
        final PlayClient.Entry wanted = play.panel();
        final Screen open = client.gui.screen();
        if (open instanceof PanelScreen screen) {
            if (wanted == null) {
                client.gui.setScreen(null);
            } else if (wanted.set() != screen.entry.set() || !wanted.widgetId().equals(screen.entry.widgetId())
                    || !wanted.owner().equals(screen.entry.owner())) {
                screen.entry = wanted;
                screen.rebuildWidgets();
            }
        } else if (open == null && wanted != null && client.level != null) {
            client.gui.setScreen(new PanelScreen(play, wanted));
        }
    }

    /**
     * @return the panel's widget this screen shows
     */
    public PlayClient.Entry entry() {
        return entry;
    }

    private static PlayClient.Panel panel(PlayClient.Entry entry) {
        return (PlayClient.Panel) entry.content();
    }

    @Override
    protected void init() {
        final PlayClient.Panel panel = panel(entry);
        final int lineHeight = font.lineHeight + 1;
        int content = Math.max(font.width(panel.title()), panel.buttons().size() * (BUTTON_WIDTH + BUTTON_GAP) - BUTTON_GAP);
        for (final PlayClient.Row row : panel.rows()) content = Math.max(content, font.width(row.label()) + 12 + font.width(row.value()));
        boxWidth = Math.min(width - 2 * PAD, Math.max(MIN_WIDTH, content + 2 * PAD));
        final int buttonsHeight = panel.buttons().isEmpty() ? 0 : Button.DEFAULT_HEIGHT + PAD;
        boxHeight = PAD + lineHeight + PAD + panel.rows().size() * lineHeight + buttonsHeight + lineHeight + PAD;
        left = (width - boxWidth) / 2;
        top = (height - boxHeight) / 2;
        rowsTop = top + PAD + lineHeight + PAD;
        buttonsTop = rowsTop + panel.rows().size() * lineHeight + PAD;
        final List<PlayClient.Button> buttons = panel.buttons();
        final int buttonWidth = buttons.isEmpty() ? 0
                : Math.min(BUTTON_WIDTH, (boxWidth - 2 * PAD - (buttons.size() - 1) * BUTTON_GAP) / buttons.size());
        int x = left + (boxWidth - (buttons.size() * (buttonWidth + BUTTON_GAP) - BUTTON_GAP)) / 2;
        for (final PlayClient.Button button : buttons) {
            final PlayClient.Entry shown = entry;
            addRenderableWidget(Button.builder(button.label(), _ -> play.click(shown, button))
                    .bounds(x, buttonsTop, buttonWidth, Button.DEFAULT_HEIGHT).build());
            x += buttonWidth + BUTTON_GAP;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No blur and no dimming: the HUD and the world stay readable around the box
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        final PlayClient.Panel panel = panel(entry);
        final int lineHeight = font.lineHeight + 1;
        graphics.fill(left, top, left + boxWidth, top + boxHeight, BOX);
        graphics.outline(left, top, boxWidth, boxHeight, EDGE);
        graphics.centeredText(font, panel.title(), left + boxWidth / 2, top + PAD, WHITE);
        int y = rowsTop;
        for (final PlayClient.Row row : panel.rows()) {
            if (row.highlight()) graphics.fill(left + 1, y - 1, left + boxWidth - 1, y + lineHeight - 1, HIGHLIGHT_BAND);
            graphics.text(font, row.label(), left + PAD, y, WHITE);
            graphics.text(font, row.value(), left + boxWidth - PAD - font.width(row.value()), y, VALUE);
            y += lineHeight;
        }
        final Component reason = play.panelReason(entry);
        if (reason != null) {
            final int reasonTop = panel.buttons().isEmpty() ? y + PAD : buttonsTop + Button.DEFAULT_HEIGHT + PAD;
            graphics.centeredText(font, reason, left + boxWidth / 2, reasonTop, REASON);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Esc: closed here only, the server is not told (§13). */
    @Override
    public void onClose() {
        play.dismiss(entry);
        super.onClose();
    }
}
