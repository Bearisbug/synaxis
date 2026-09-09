package dev.gameengine.listener.manager;

import dev.gameengine.network.ConnectionState;
import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.network.player.PlayerConnection;

/**
 * Small convenient interface to use method references with {@link PacketListenerManager#setListener(ConnectionState, Class, PacketPrePlayListenerConsumer)}.
 *
 * @param <T> the packet type
 */
@FunctionalInterface
public interface PacketPrePlayListenerConsumer<T extends ClientPacket> {
    void accept(T packet, PlayerConnection connection);
}
