package io.github.bearisbug.synaxis.app.transfer;

/**
 * How a transfer went ({@code docs/DESIGN-PROD.md} {@code API-PROD-028}).
 */
public enum TransferOutcome {
    /** The ticket is stored and the client told where to go. */
    SENT,
    /** The player transferred less than the cooldown ago; nothing was written. */
    COOLDOWN,
    /** The player is not in play on this shard. */
    NOT_ONLINE,
    /** The character could not be written; the player stays here. */
    SAVE_FAILED
}
