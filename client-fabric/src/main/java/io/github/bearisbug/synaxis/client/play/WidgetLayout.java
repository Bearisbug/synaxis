package io.github.bearisbug.synaxis.client.play;

import io.github.bearisbug.synaxis.protocol.play.Widget;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Where the HUD widgets go ({@code docs/DESIGN-PLAY.md} §6.4.2, §6.4.8): seven slots, in each the scene owner's
 * widgets before the overlays', each by {@code order}, ties by the first SET. Everything is in GUI pixels.
 * <p>
 * {@code TOP_LEFT} and {@code TOP_RIGHT} stack down from the top edge; {@code LEFT}, {@code RIGHT} and
 * {@code CENTER} centre their stack vertically; {@code TOP_CENTER}, {@code BOTTOM} and {@code CENTER} centre each
 * widget horizontally. Two slots make room for vanilla, by vanilla's own numbers in 26.2: {@code TOP_CENTER}
 * starts below the last boss bar drawn ({@code BossHealthOverlay#extractRenderState}), and {@code BOTTOM} ends
 * above the action bar ({@code Hud#extractOverlayMessage}), which the hotbar, the experience bar, health and
 * food all lie under.
 */
public final class WidgetLayout {

    /** The distance to the screen's edge. */
    public static final int MARGIN = 2;
    /** The distance between two widgets of one slot. */
    public static final int GAP = 2;
    /** How far above the bottom edge the action bar's text starts: drawn at guiHeight − 68, shifted up 4. */
    public static final int ACTION_BAR_TOP = 72;

    // BossHealthOverlay: the first bar at y 12, every next one 19 lower, none once y reaches a third of the height
    private static final int BOSS_FIRST_Y = 12;
    private static final int BOSS_STEP = 19;
    private static final int BOSS_BAR_HEIGHT = 5;

    /**
     * One widget to place.
     *
     * @param slot       its slot, {@link Widget#TOP_LEFT} to {@link Widget#CENTER}
     * @param sceneOwner whether the scene owner set it
     * @param order      its position within the slot
     * @param first      the number of the SET that first put it in the table
     * @param width      its width
     * @param height     its height
     */
    public record Box(int slot, boolean sceneOwner, int order, long first, int width, int height) {
    }

    /**
     * Where one widget goes.
     *
     * @param x its left edge
     * @param y its top edge
     */
    public record Placement(int x, int y) {
    }

    /** The order of the widgets: by slot, the scene owner's first, by {@code order}, by the first SET. */
    public static final Comparator<Box> ORDER = Comparator.comparingInt(Box::slot)
            .thenComparing(Box::sceneOwner, Comparator.reverseOrder())
            .thenComparingInt(Box::order)
            .thenComparingLong(Box::first);

    private WidgetLayout() {
    }

    /**
     * Places widgets.
     *
     * @param boxes     the widgets, in any order
     * @param guiWidth  the GUI width
     * @param guiHeight the GUI height
     * @param bossBars  how many boss bars vanilla has
     * @return where each goes, in the order of {@code boxes}
     */
    public static List<Placement> place(List<Box> boxes, int guiWidth, int guiHeight, int bossBars) {
        final Integer[] sorted = new Integer[boxes.size()];
        for (int i = 0; i < sorted.length; i++) sorted[i] = i;
        Arrays.sort(sorted, Comparator.comparing(boxes::get, ORDER));
        final Placement[] placed = new Placement[boxes.size()];
        int from = 0;
        while (from < sorted.length) {
            final int slot = boxes.get(sorted[from]).slot();
            int to = from;
            int stack = -GAP;
            while (to < sorted.length && boxes.get(sorted[to]).slot() == slot) stack += boxes.get(sorted[to++]).height() + GAP;
            int y = switch (slot) {
                case Widget.TOP_CENTER -> topCenterTop(bossBars, guiHeight);
                case Widget.LEFT, Widget.RIGHT, Widget.CENTER -> (guiHeight - stack) / 2;
                case Widget.BOTTOM -> guiHeight - ACTION_BAR_TOP - GAP - stack;
                default -> MARGIN;
            };
            for (int i = from; i < to; i++) {
                final Box box = boxes.get(sorted[i]);
                final int x = switch (slot) {
                    case Widget.TOP_RIGHT, Widget.RIGHT -> guiWidth - MARGIN - box.width();
                    case Widget.TOP_CENTER, Widget.BOTTOM, Widget.CENTER -> (guiWidth - box.width()) / 2;
                    default -> MARGIN;
                };
                placed[sorted[i]] = new Placement(x, y);
                y += box.height() + GAP;
            }
            from = to;
        }
        return List.of(placed);
    }

    /**
     * Where {@code TOP_CENTER} starts: below the last boss bar vanilla draws, which stops once the next bar would
     * start at a third of the GUI height or lower.
     *
     * @param bossBars  how many boss bars vanilla has
     * @param guiHeight the GUI height
     * @return the top edge of the slot
     */
    public static int topCenterTop(int bossBars, int guiHeight) {
        if (bossBars <= 0) return MARGIN;
        int y = BOSS_FIRST_Y;
        int last = y;
        for (int i = 0; i < bossBars; i++) {
            last = y;
            y += BOSS_STEP;
            if (y >= guiHeight / 3) break;
        }
        return last + BOSS_BAR_HEIGHT + GAP;
    }
}
