package io.github.bearisbug.synaxis.app.transfer;

/**
 * How a transfer went ({@code docs/DESIGN-PROD.md} {@code API-PROD-028}).
 */
public enum TransferOutcome {
    /**
     * The ticket is stored on the client and the transfer handed to the connection. A listener that cancels
     * {@code OutgoingTransferEvent} stops the Transfer packet; the player is then disconnected with
     * {@code synaxis:transfer-rejected} {@code TicketIssuer#LEAVE_WITHIN} later ({@code API-PROD-028} ④).
     */
    SENT,
    /** The player transferred less than the cooldown ago; nothing was written. */
    COOLDOWN,
    /** The player is not in play on this shard. */
    NOT_ONLINE,
    /**
     * The character could not be written; the player stays here and was sent {@code synaxis:transfer-failed}
     * ({@link TicketIssuer#TRANSFER_FAILED}), so a caller does not tell it again.
     */
    SAVE_FAILED
}
