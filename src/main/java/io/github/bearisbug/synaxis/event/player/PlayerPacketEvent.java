package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

/**
 * Called for a play packet of a player before the engine handles it; cancelling it drops the packet.
 * <p>
 * Dispatched for the play packets queued for the player only, on the player's tick thread. While the
 * player is in no instance, waiting to join after the configuration or sent back to the
 * configuration and not acknowledged yet, its queue is handled, and this event dispatched, in the
 * serial part of the tick on the tick scheduler thread. The keep alive, ping and cookie answers skip
 * the queue and are handled on the read thread of the connection without this event. A packet that switches the decoding state, the acknowledgement of
 * a return to the configuration, is never offered either: the client has switched already, and
 * cancelling it would leave the two sides decoding different states.
 */
public class PlayerPacketEvent implements PlayerInstanceEvent, CancellableEvent {

    private final Player player;
    private final ClientPacket packet;
    private boolean cancelled;

    public PlayerPacketEvent(Player player, ClientPacket packet) {
        this.player = player;
        this.packet = packet;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    public ClientPacket getPacket() {
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
