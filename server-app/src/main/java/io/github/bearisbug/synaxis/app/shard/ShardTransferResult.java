package io.github.bearisbug.synaxis.app.shard;

/**
 * How a {@link ShardService} transfer went ({@code docs/DESIGN-M3.md} {@code API-M3-010}). The first
 * four are the {@code TransferOutcome} of the same name.
 */
public enum ShardTransferResult {
    /** The ticket is stored on the client and the client told where to go ({@code TransferOutcome.SENT}). */
    SENT,
    /**
     * The player transferred less than {@code synaxis.app.transfer-cooldown-ms} ago
     * ({@code TransferOutcome.COOLDOWN}); the database was not touched.
     */
    COOLDOWN,
    /**
     * The player is not online on this shard: transferring already, logged out or still loading
     * ({@code TransferOutcome.NOT_ONLINE}).
     */
    NOT_ONLINE,
    /**
     * The character could not be written back; the player stays on this shard and was sent
     * {@code synaxis:transfer-failed} ({@code TransferOutcome.SAVE_FAILED}).
     */
    SAVE_FAILED,
    /**
     * {@code transfer}: the region is not in this shard's directory; {@code transferTo}: the shard
     * has no heartbeat row of this map.
     */
    UNKNOWN_TARGET,
    /** {@code transfer}: the target is this shard's region; {@code transferTo}: the target is this shard. */
    SAME_TARGET,
    /**
     * Every live and accepting channel of the target region is full; {@code transferTo}: the target
     * is at its capacity.
     */
    FULL,
    /**
     * The target region has no live and accepting channel; {@code transferTo}: the target's heartbeat
     * expired or its login gate is closed.
     */
    OFFLINE,
    /** Reading {@code shard_heartbeat} failed or took more than 2 s. */
    DIRECTORY_UNAVAILABLE
}
