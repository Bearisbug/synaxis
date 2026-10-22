package io.github.bearisbug.synaxis.client.play;

import io.github.bearisbug.synaxis.protocol.play.Widget;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The slots of {@code docs/DESIGN-PLAY.md} §6.4.2 and §6.4.8 on a 427 × 240 GUI (854 × 480 at scale 2, the size
 * of the gametest window): the order within a slot, where each slot stacks, and the room {@code TOP_CENTER}
 * leaves the boss bars and {@code BOTTOM} the action bar.
 */
class WidgetLayoutTest {

    private static final int WIDTH = 427;
    private static final int HEIGHT = 240;

    @Test
    void withinASlotTheSceneOwnersComeFirstThenOrderThenTheFirstSet() {
        final List<WidgetLayout.Box> boxes = List.of(
                new WidgetLayout.Box(Widget.TOP_LEFT, false, 0, 1, 50, 9),
                new WidgetLayout.Box(Widget.TOP_LEFT, true, 2, 2, 50, 9),
                new WidgetLayout.Box(Widget.TOP_LEFT, true, 1, 4, 50, 9),
                new WidgetLayout.Box(Widget.TOP_LEFT, true, 1, 3, 50, 9));

        final List<WidgetLayout.Placement> placed = WidgetLayout.place(boxes, WIDTH, HEIGHT, 0);

        // Order: owner order 1 first set 3, owner order 1 first set 4, owner order 2, overlay
        assertEquals(List.of(35, 24, 13, 2), placed.stream().map(WidgetLayout.Placement::y).toList());
        assertEquals(List.of(2, 2, 2, 2), placed.stream().map(WidgetLayout.Placement::x).toList());
    }

    @Test
    void eachSlotStacksFromItsOwnEdge() {
        final List<WidgetLayout.Box> boxes = List.of(
                new WidgetLayout.Box(Widget.TOP_RIGHT, true, 0, 1, 100, 40),
                new WidgetLayout.Box(Widget.TOP_RIGHT, true, 1, 2, 60, 9),
                new WidgetLayout.Box(Widget.LEFT, true, 0, 3, 80, 9),
                new WidgetLayout.Box(Widget.LEFT, true, 1, 4, 80, 9),
                new WidgetLayout.Box(Widget.RIGHT, true, 0, 5, 30, 20),
                new WidgetLayout.Box(Widget.CENTER, true, 0, 6, 41, 11));

        final List<WidgetLayout.Placement> placed = WidgetLayout.place(boxes, WIDTH, HEIGHT, 0);

        assertEquals(new WidgetLayout.Placement(325, 2), placed.get(0));
        assertEquals(new WidgetLayout.Placement(365, 44), placed.get(1));
        // LEFT: a stack of 9 + 2 + 9 = 20 centred on 240
        assertEquals(new WidgetLayout.Placement(2, 110), placed.get(2));
        assertEquals(new WidgetLayout.Placement(2, 121), placed.get(3));
        assertEquals(new WidgetLayout.Placement(395, 110), placed.get(4));
        assertEquals(new WidgetLayout.Placement(193, 114), placed.get(5));
    }

    @Test
    void bottomEndsAboveTheActionBarAndIsCentred() {
        final List<WidgetLayout.Box> boxes = List.of(
                new WidgetLayout.Box(Widget.BOTTOM, true, 0, 1, 120, 9),
                new WidgetLayout.Box(Widget.BOTTOM, true, 1, 2, 60, 9));

        final List<WidgetLayout.Placement> placed = WidgetLayout.place(boxes, WIDTH, HEIGHT, 0);

        // The action bar's text starts at 240 - 72 = 168; the stack of 20 ends 2 above it
        assertEquals(new WidgetLayout.Placement(153, 146), placed.get(0));
        assertEquals(new WidgetLayout.Placement(183, 157), placed.get(1));
        assertEquals(166, placed.get(1).y() + 9);
    }

    @Test
    void topCenterStartsBelowTheBossBarsVanillaDraws() {
        assertEquals(2, WidgetLayout.topCenterTop(0, HEIGHT));
        // One bar at y 12, 5 high, then the gap
        assertEquals(19, WidgetLayout.topCenterTop(1, HEIGHT));
        assertEquals(38, WidgetLayout.topCenterTop(2, HEIGHT));
        // At 240 vanilla stops once the next bar would start at 80 or lower: bars at 12, 31, 50, 69, no fifth
        assertEquals(76, WidgetLayout.topCenterTop(4, HEIGHT));
        assertEquals(76, WidgetLayout.topCenterTop(9, HEIGHT));

        final List<WidgetLayout.Placement> placed = WidgetLayout.place(
                List.of(new WidgetLayout.Box(Widget.TOP_CENTER, true, 0, 1, 81, 9)), WIDTH, HEIGHT, 2);
        assertEquals(new WidgetLayout.Placement(173, 38), placed.getFirst());
    }
}
