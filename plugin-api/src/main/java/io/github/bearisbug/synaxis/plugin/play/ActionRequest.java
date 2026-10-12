package io.github.bearisbug.synaxis.plugin.play;

/**
 * One action a player sent, as the host hands it to an {@link ActionHandler}
 * ({@code docs/DESIGN-PLAY.md} {@code API-PLAY-005}, {@code C2S_ACTION} of {@code API-PLAY-002}).
 *
 * @param actionId  the full action id, {@code <plugin_id>:<name>}
 * @param actionSeq the client's sequence number of the action, which the result answers to
 * @param target    what the player aimed at
 */
public record ActionRequest(String actionId, int actionSeq, Target target) {
}
