package io.github.bearisbug.synaxis.testing;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public interface TestConnection {
    Player connect(Instance instance, Pos pos);

    /**
     * Tracks the packets of a type sent to this connection, until the first
     * {@link Collector#collect()} of the returned collector. A
     * {@link io.github.bearisbug.synaxis.network.packet.server.BufferedPacket}, which holds encoded bytes
     * and no packet object, is not tracked; the packet inside a
     * {@link io.github.bearisbug.synaxis.network.packet.server.CachedPacket} or a
     * {@link io.github.bearisbug.synaxis.network.packet.server.FramedPacket} is. With
     * {@code synaxis.automatic-component-translation} on, a packet sent as a packet object and holding
     * components is tracked as {@link io.github.bearisbug.synaxis.adventure.EngineAdventure#COMPONENT_TRANSLATOR}
     * renders it for the player, and the packet inside a cached or framed packet is tracked as it is:
     * both are what a socket connection sends.
     *
     * @param type the packet type, subtypes included
     * @param <T>  the packet type
     * @return the collector
     */
    <T extends ServerPacket> Collector<T> trackIncoming(Class<T> type);

    default Collector<ServerPacket> trackIncoming() {
        return trackIncoming(ServerPacket.class);
    }
}
