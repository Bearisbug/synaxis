package io.github.bearisbug.synaxis.listener.manager;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

/**
 * Small convenient interface to use method references with {@link PacketListenerManager#setPlayListener(Class, PacketPlayListenerConsumer)}.
 *
 * @param <T> the packet type
 */
@FunctionalInterface
public interface PacketPlayListenerConsumer<T extends ClientPacket> {
    void accept(T packet, Player player);
}
