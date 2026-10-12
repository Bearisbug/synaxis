package io.github.bearisbug.synaxis.plugin.play;

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
     * @param reasonKey the translate key the client shows, one of this plugin's
     *                  {@code translationKeys()}
     */
    record Rejected(String reasonKey) implements ActionResult {
    }
}
