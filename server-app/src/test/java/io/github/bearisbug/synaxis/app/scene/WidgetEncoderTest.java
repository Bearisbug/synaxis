package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.play.EncodedComponent;
import io.github.bearisbug.synaxis.game.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.game.protocol.play.PropValue;
import io.github.bearisbug.synaxis.plugin.play.HudSlot;
import io.github.bearisbug.synaxis.plugin.play.Widget;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The widget a plugin sets becomes the widget {@code synaxis:play} carries ({@code docs/DESIGN-PLAY.md}
 * {@code API-PLAY-003}), and the sizes only the encoding can tell are checked there: a component of at most
 * 2,048 bytes and a widget of at most 4,096 (§6.4.3).
 */
class WidgetEncoderTest {

    private static final Component HINT = Component.translatable("rush.hint", NamedTextColor.GOLD, Component.text("1"))
            .fallback("Queued: %s");

    private static EncodedComponent encoded(Component component) {
        return new EncodedComponent(ComponentBytes.encode(component));
    }

    private static int size(io.github.bearisbug.synaxis.game.protocol.play.Widget widget) {
        final MsgBuf buf = MsgBuf.writing();
        widget.write(buf);
        return buf.toByteArray().length;
    }

    @Test
    void eachKindBecomesItsWireWidget() {
        final Widget.Row row = new Widget.Row(Component.text("Alex"), "12", true);
        final PropValue.Row wireRow = new PropValue.Row(encoded(Component.text("Alex")), "12", true);

        final WidgetEncoder.Encoded text = WidgetEncoder.encode("rush",
                new Widget.Text("hint", HudSlot.TOP_CENTER, 3, HINT, Duration.ofSeconds(5)));
        final WidgetEncoder.Encoded timer = WidgetEncoder.encode("rush",
                new Widget.Timer("timer", HudSlot.TOP_RIGHT, 0, Component.text("Round"), Duration.ofSeconds(45), true));
        final WidgetEncoder.Encoded board = WidgetEncoder.encode("rush",
                new Widget.Scoreboard("score", HudSlot.RIGHT, 1, Component.text("Score"), List.of(row)));
        final WidgetEncoder.Encoded panel = WidgetEncoder.encode("rush", new Widget.Panel("result",
                Component.text("Result"), List.of(row), List.of(new Widget.Button("back", Component.text("Back")))));

        assertEquals(io.github.bearisbug.synaxis.game.protocol.play.Widget.text(1, 3, encoded(HINT), 5_000), text.widget());
        assertEquals(io.github.bearisbug.synaxis.game.protocol.play.Widget.timer(2, 0, encoded(Component.text("Round")),
                45_000, true), timer.widget());
        assertEquals(io.github.bearisbug.synaxis.game.protocol.play.Widget.scoreboard(4, 1,
                encoded(Component.text("Score")), List.of(wireRow)), board.widget());
        assertEquals(io.github.bearisbug.synaxis.game.protocol.play.Widget.panel(encoded(Component.text("Result")),
                List.of(wireRow), List.of(new PropValue.Button("rush:back", encoded(Component.text("Back"))))),
                panel.widget());
        assertEquals(Set.of(), text.buttonActions());
        assertEquals(Set.of("rush:back"), panel.buttonActions());
    }

    private static Widget.Panel panel(int titleLength) {
        final List<Widget.Row> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(new Widget.Row(Component.text("r".repeat(150)), "", false));
        final List<Widget.Button> buttons = new ArrayList<>();
        for (int i = 0; i < 4; i++) buttons.add(new Widget.Button("b" + i, Component.text("l".repeat(100))));
        return new Widget.Panel("big", Component.text("t".repeat(titleLength)), rows, buttons);
    }

    private static boolean fits(Widget widget) {
        try {
            WidgetEncoder.encode("rush", widget);
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    @Test
    void aWidgetOf4096BytesIsTakenAndOneByteMoreIsNot() {
        // The title stays under the 2,048 bytes of a component: the widget reaches 4,096 near 1,700 characters
        int titleLength = 1;
        while (titleLength < 2_000 && fits(panel(titleLength + 1))) titleLength++;

        assertEquals(PlayProtocol.MAX_WIDGET_BYTES, size(WidgetEncoder.encode("rush", panel(titleLength)).widget()));
        final int longer = titleLength + 1;
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> WidgetEncoder.encode("rush", panel(longer)));
        assertTrue(refused.getMessage().contains("4097 bytes"), refused.getMessage());
    }

    @Test
    void aPanelOf5000BytesIsRefused() {
        final List<Widget.Row> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) rows.add(new Widget.Row(Component.text("r".repeat(245)), "", false));
        final Widget.Panel panel = new Widget.Panel("big", Component.text("t".repeat(2_000)), rows, List.of());

        assertThrows(IllegalArgumentException.class, () -> WidgetEncoder.encode("rush", panel));
    }

    @Test
    void aComponentOver2048BytesIsRefused() {
        final Widget.Text text = new Widget.Text("hint", HudSlot.TOP_LEFT, 0, Component.text("x".repeat(2_100)), Duration.ZERO);

        assertThrows(IllegalArgumentException.class, () -> WidgetEncoder.encode("rush", text));
    }

    @Test
    void aButtonGetsThePluginsPrefixAndAnotherPluginsIsRefused() {
        final WidgetEncoder.Encoded panel = WidgetEncoder.encode("rush", new Widget.Panel("result", Component.text("R"),
                List.of(), List.of(new Widget.Button("back", Component.text("Back")),
                new Widget.Button("rush:again", Component.text("Again")))));

        assertEquals(Set.of("rush:back", "rush:again"), panel.buttonActions());
        assertThrows(IllegalArgumentException.class, () -> WidgetEncoder.encode("rush", new Widget.Panel("result",
                Component.text("R"), List.of(), List.of(new Widget.Button("rts:back", Component.text("Back"))))));
        assertThrows(IllegalArgumentException.class, () -> WidgetEncoder.encode("rush", new Widget.Panel("result",
                Component.text("R"), List.of(), List.of(new Widget.Button("Back Home", Component.text("Back"))))));
    }
}
