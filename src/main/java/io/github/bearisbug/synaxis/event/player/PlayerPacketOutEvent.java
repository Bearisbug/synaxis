package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerEvent;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.jetbrains.annotations.ApiStatus;

/**
 * Called for every packet written to a player; cancelling it keeps the packet from being written.
 * <p>
 * Dispatched on the write thread of the connection, not on a tick thread: a listener must not read
 * or change an entity or an instance (hand that over with the player's scheduler or
 * {@code Acquirable#sync}), and must not block, which would hold up every packet of that player
 * until the backlog limit drops the connection. A {@code BufferedPacket} (chunks sent by handle,
 * batched broadcasts) is written without this event; a {@code CachedPacket} or a
 * {@code FramedPacket} gets it with the packet it holds.
 */
@ApiStatus.Experimental
public class PlayerPacketOutEvent implements PlayerEvent, CancellableEvent {
    private final Player player;
    private final ServerPacket packet;
    private boolean cancelled;

    public PlayerPacketOutEvent(Player player, ServerPacket packet) {
        this.player = player;
        this.packet = packet;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public ServerPacket getPacket() {
        return packet;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
