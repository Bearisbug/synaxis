package dev.gameengine.listener.manager;

import dev.gameengine.entity.Player;
import dev.gameengine.network.packet.client.ClientPacket;

/**
 * Small convenient interface to use method references with {@link PacketListenerManager#setPlayListener(Class, PacketPlayListenerConsumer)}.
 *
 * @param <T> the packet type
 */
@FunctionalInterface
public interface PacketPlayListenerConsumer<T extends ClientPacket> {
    void accept(T packet, Player player);
}
