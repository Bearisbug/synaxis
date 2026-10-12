package io.github.bearisbug.synaxis.plugin.play;

/**
 * Where on the screen a widget goes ({@code docs/DESIGN-PLAY.md} §6.4.2). The ordinal is the slot on
 * the wire. Within a slot the scene owner's widgets come first, then the overlays', each by
 * {@link Widget#order()}; the top centre gives way to the vanilla boss bars and the bottom to the
 * action bar and the hotbar.
 */
public enum HudSlot {

    /** The top left corner. */
    TOP_LEFT,
    /** The top centre, below the boss bars. */
    TOP_CENTER,
    /** The top right corner. */
    TOP_RIGHT,
    /** The left edge. */
    LEFT,
    /** The right edge. */
    RIGHT,
    /** The bottom centre, above the action bar and the hotbar. */
    BOTTOM,
    /** The middle of the screen, where a panel opens. */
    CENTER
}
