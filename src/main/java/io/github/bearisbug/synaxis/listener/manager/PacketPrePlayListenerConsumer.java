package io.github.bearisbug.synaxis.listener.manager;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;

/**
 * Small convenient interface to use method references with {@link PacketListenerManager#setListener(ConnectionState, Class, PacketPrePlayListenerConsumer)}.
 *
 * @param <T> the packet type
 */
@FunctionalInterface
public interface PacketPrePlayListenerConsumer<T extends ClientPacket> {
    void accept(T packet, PlayerConnection connection);
}
