package dev.gameengine.event.player;

import dev.gameengine.entity.Player;
import dev.gameengine.event.trait.CancellableEvent;
import dev.gameengine.event.trait.PlayerInstanceEvent;
import dev.gameengine.network.packet.client.ClientPacket;

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
