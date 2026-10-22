package io.github.bearisbug.synaxis.plugin.instance;

import io.github.bearisbug.synaxis.coordinate.Pos;
import net.kyori.adventure.text.Component;

import java.util.Objects;

/**
 * Where a player goes when a managed instance closes ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-006}).
 * A player still inside 5 s after the evacuation started is kicked with
 * {@code synaxis:match-unavailable}.
 */
public sealed interface Exit {

    /**
     * Into another managed instance, the way {@link ManagedInstance#move} moves a player. A target that does
     * not take the player, one not {@link ManagedInstance.State#OPEN} or {@link ManagedInstance.State#RUNNING}
     * when the move runs, has the player kicked with {@code synaxis:match-unavailable}.
     *
     * @param target the instance
     * @param at     where the player lands
     */
    record ToInstance(ManagedInstance target, Pos at) implements Exit {
    }

    /**
     * Off the server.
     *
     * @param reason the disconnect reason the client shows, a translate key with a fallback where the text
     *               is new, the way the text of a widget is written
     */
    record Kick(Component reason) implements Exit {

        /**
         * Checks the reason.
         *
         * @throws NullPointerException if it is null
         */
        public Kick {
            Objects.requireNonNull(reason, "reason");
        }

        /**
         * A kick whose reason is a translate key alone, {@code Component.translatable(reasonKey)}.
         *
         * @param reasonKey the disconnect reason key, one the client mod has a sentence for
         */
        public Kick(String reasonKey) {
            this(Component.translatable(reasonKey));
        }
    }
}
