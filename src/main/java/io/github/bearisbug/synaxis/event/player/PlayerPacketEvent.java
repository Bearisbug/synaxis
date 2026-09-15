package io.github.bearisbug.synaxis.event.player;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.trait.CancellableEvent;
import io.github.bearisbug.synaxis.event.trait.PlayerInstanceEvent;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

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
