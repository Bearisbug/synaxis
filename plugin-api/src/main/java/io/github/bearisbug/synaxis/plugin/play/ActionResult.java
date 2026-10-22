package io.github.bearisbug.synaxis.plugin.play;

import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

/**
 * The answer of an {@link ActionHandler} ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}).
 */
public sealed interface ActionResult {

    /**
     * The action was carried out; the host starts the cooldown of its slot and answers {@code ok}.
     */
    record Ok() implements ActionResult {
    }

    /**
     * The action was refused; the host answers {@code rejected} with the reason.
     *
     * @param reason what the client shows, a translate key with a fallback and arguments where the text is
     *               new, the way the text of a widget is written; null for none. A reason that does not
     *               encode, over
     *               {@value io.github.bearisbug.synaxis.protocol.play.EncodedComponent#MAX_BYTES} bytes
     *               or holding a {@code show_item} hover, is answered without one
     */
    record Rejected(@Nullable Component reason) implements ActionResult {

        /**
         * A refusal whose reason is a translate key alone, {@code Component.translatable(reasonKey)}.
         *
         * @param reasonKey a translate key, one of this plugin's {@code translationKeys()}
         */
        public Rejected(String reasonKey) {
            this(Component.translatable(reasonKey));
        }
    }
}
