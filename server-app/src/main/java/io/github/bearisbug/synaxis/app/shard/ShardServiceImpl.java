package io.github.bearisbug.synaxis.app.shard;

import io.github.bearisbug.synaxis.app.transfer.TransferTickets;
import io.github.bearisbug.synaxis.entity.Player;

import java.util.concurrent.CompletableFuture;

/**
 * The {@link ShardService} of a shard in the directory mode ({@code docs/DESIGN-M3.md}
 * {@code API-M3-010}): checks the target against the directory, chooses the channel on the
 * heartbeats, and hands the transfer to {@link TransferTickets}.
 * <p>
 * The M3-E step 0 contract: the signatures {@code Main} is assembled against. The transfer track
 * replaces the bodies.
 */
@SuppressWarnings("DoNotCallSuggester") // the bodies of the skeleton, replaced by the transfer track
public final class ShardServiceImpl implements ShardService {

    /**
     * @param directory  the shard directory of this shard
     * @param heartbeats where the channels are read
     * @param tickets    the source side of a transfer
     */
    public ShardServiceImpl(ShardDirectory directory, HeartbeatReader heartbeats, TransferTickets tickets) {
        throw new UnsupportedOperationException("ShardServiceImpl is the M3-E step 0 skeleton");
    }

    @Override
    public String shardId() {
        throw new UnsupportedOperationException("ShardServiceImpl is the M3-E step 0 skeleton");
    }

    @Override
    public String regionId() {
        throw new UnsupportedOperationException("ShardServiceImpl is the M3-E step 0 skeleton");
    }

    @Override
    public CompletableFuture<ShardTransferResult> transfer(Player player, String regionId) {
        throw new UnsupportedOperationException("ShardServiceImpl is the M3-E step 0 skeleton");
    }

    @Override
    public CompletableFuture<ShardTransferResult> transferTo(Player player, String shardId) {
        throw new UnsupportedOperationException("ShardServiceImpl is the M3-E step 0 skeleton");
    }
}
