package io.github.bearisbug.synaxis.plugin.play;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The limits of the four widgets of {@code synaxis:play} version 1 that need no encoding
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-003}, {@code API-PLAY-005}, §9): an id of 1 to 32
 * bytes of UTF-8, times that fit the varint on the wire, at most 16 scoreboard rows, at most 12 rows
 * and 4 buttons on a panel, a row value of at most 32 bytes. Lists are copied.
 */
class WidgetTest {

    private static final Component TEXT = Component.text("text");
    private static final Widget.Row ROW = new Widget.Row(TEXT, "35", false);
    private static final Widget.Button BUTTON = new Widget.Button("back", TEXT);

    /** One widget of each kind, built around the given id. */
    private static final List<Function<String, Widget>> KINDS = List.of(
            id -> new Widget.Text(id, HudSlot.LEFT, 0, TEXT, Duration.ZERO),
            id -> new Widget.Timer(id, HudSlot.TOP_CENTER, 0, TEXT, Duration.ofSeconds(45), true),
            id -> new Widget.Scoreboard(id, HudSlot.TOP_RIGHT, 0, TEXT, List.of(ROW)),
            id -> new Widget.Panel(id, TEXT, List.of(ROW), List.of(BUTTON)));

    @Test
    void everyKindTakesAnIdOfOneTo32BytesOfUtf8() {
        for (final Function<String, Widget> kind : KINDS) {
            assertEquals("x".repeat(32), kind.apply("x".repeat(32)).id());
            assertEquals("信".repeat(10), kind.apply("信".repeat(10)).id(), "30 bytes");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "信信信信信信信信信信信"})
    void everyKindRefusesAnEmptyIdOrOneOver32Bytes(String id) {
        for (final Function<String, Widget> kind : KINDS) {
            assertThrows(IllegalArgumentException.class, () -> kind.apply(id), kind.apply("x").getClass().getSimpleName());
        }
    }

    @Test
    void aTextLivesFromZeroToTheLargestVarintOfMilliseconds() {
        assertEquals(Duration.ofMillis(Integer.MAX_VALUE),
                new Widget.Text("hint", HudSlot.LEFT, 0, TEXT, Duration.ofMillis(Integer.MAX_VALUE)).ttl());

        assertThrows(IllegalArgumentException.class,
                () -> new Widget.Text("hint", HudSlot.LEFT, 0, TEXT, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Widget.Text("hint", HudSlot.LEFT, 0, TEXT, Duration.ofMillis(Integer.MAX_VALUE + 1L)));
    }

    @Test
    void aTimerHoldsFromZeroToTheLargestVarintOfMilliseconds() {
        assertEquals(Duration.ZERO, new Widget.Timer("timer", HudSlot.TOP_CENTER, 0, TEXT, Duration.ZERO, false).remaining());

        assertThrows(IllegalArgumentException.class,
                () -> new Widget.Timer("timer", HudSlot.TOP_CENTER, 0, TEXT, Duration.ofMillis(-1), true));
        assertThrows(IllegalArgumentException.class,
                () -> new Widget.Timer("timer", HudSlot.TOP_CENTER, 0, TEXT, Duration.ofMillis(Integer.MAX_VALUE + 1L), true));
    }

    @Test
    void aScoreboardTakes16RowsAndNoMore() {
        assertEquals(16, new Widget.Scoreboard("score", HudSlot.TOP_RIGHT, 0, TEXT, Collections.nCopies(16, ROW)).rows().size());

        assertThrows(IllegalArgumentException.class,
                () -> new Widget.Scoreboard("score", HudSlot.TOP_RIGHT, 0, TEXT, Collections.nCopies(17, ROW)));
    }

    @Test
    void aPanelTakes12RowsAnd4ButtonsAndSitsInTheCentre() {
        final Widget.Panel panel = new Widget.Panel("result", TEXT, Collections.nCopies(12, ROW), Collections.nCopies(4, BUTTON));
        assertEquals(12, panel.rows().size());
        assertEquals(4, panel.buttons().size());
        assertEquals(HudSlot.CENTER, panel.slot());
        assertEquals(0, panel.order());

        assertThrows(IllegalArgumentException.class,
                () -> new Widget.Panel("result", TEXT, Collections.nCopies(13, ROW), List.of(BUTTON)));
        assertThrows(IllegalArgumentException.class,
                () -> new Widget.Panel("result", TEXT, List.of(ROW), Collections.nCopies(5, BUTTON)));
    }

    @Test
    void aRowValueIsAtMost32BytesOfUtf8() {
        assertEquals("9".repeat(32), new Widget.Row(TEXT, "9".repeat(32), true).value());

        assertThrows(IllegalArgumentException.class, () -> new Widget.Row(TEXT, "9".repeat(33), false));
        assertThrows(IllegalArgumentException.class, () -> new Widget.Row(TEXT, "分".repeat(11), false));
    }

    @Test
    void theListsOfAWidgetAreItsOwn() {
        final List<Widget.Row> rows = new ArrayList<>(List.of(ROW));
        final List<Widget.Button> buttons = new ArrayList<>(List.of(BUTTON));
        final Widget.Scoreboard scoreboard = new Widget.Scoreboard("score", HudSlot.TOP_RIGHT, 0, TEXT, rows);
        final Widget.Panel panel = new Widget.Panel("result", TEXT, rows, buttons);
        rows.clear();
        buttons.clear();

        assertEquals(List.of(ROW), scoreboard.rows());
        assertEquals(List.of(ROW), panel.rows());
        assertEquals(List.of(BUTTON), panel.buttons());
        assertThrows(UnsupportedOperationException.class, () -> panel.buttons().add(BUTTON));
    }
}
