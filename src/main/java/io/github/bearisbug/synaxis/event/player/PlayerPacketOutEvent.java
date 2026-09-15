package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerEvent;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.jetbrains.annotations.ApiStatus;

/**
 * Listen to outgoing packets asynchronously.
 * <p>
 * Currently, do not support viewable packets.
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
