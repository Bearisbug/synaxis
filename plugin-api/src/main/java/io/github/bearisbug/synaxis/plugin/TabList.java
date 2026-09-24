package io.github.bearisbug.synaxis.plugin;

import io.github.bearisbug.synaxis.entity.Player;

import java.util.function.BiPredicate;

/**
 * Who appears in whose tab list ({@code docs/DESIGN-PROD.md} API-PROD-020).
 * <p>
 * A player left out of a viewer's list is still sent to that viewer, unlisted: the client needs
 * the entry to render the player's skin. A player always lists itself. The process has one
 * visibility; the host takes it back when the plugin that set it is disabled.
 */
public interface TabList {

    /**
     * Sets the visibility of the whole process and sends every online player its list again.
     * <p>
     * The host calls it whenever it sends an entry, from whichever thread does so (the tick
     * scheduler when a player joins, the caller of {@link Player#setListed(boolean)}). An exception
     * it throws counts as "listed" and is logged at most once per 10 s. Call this method again to
     * have a visibility that depends on something else re-evaluated for everyone.
     *
     * @param visibility {@code (viewer, subject) -> whether viewer lists subject}; only consulted for
     *                   a subject that is {@linkplain Player#isListed() listed} at all
     * @throws IllegalStateException if another plugin set the visibility
     */
    void setVisibility(BiPredicate<Player, Player> visibility);
}
