package io.github.bearisbug.synaxis.loadtest;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketRegistry;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The packet ids and codecs of the bots, taken from the engine's own registries
 * ({@code docs/DESIGN-PROD.md} {@code ADR-PROD-013}): nothing is remembered from a protocol dump,
 * so a bot follows the repository. The price is that a codec bug is shared by the bots and the
 * server and cannot show in a load test; the real client regression ({@code REQ-PROD-030}) covers it.
 */
final class Protocol {

    private Protocol() {
    }

    /**
     * The body of a client packet, its id first, ready to be framed.
     *
     * @param state  the state the packet is sent in
     * @param packet the packet
     * @return id and payload
     */
    static byte[] encode(ConnectionState state, ClientPacket packet) {
        @SuppressWarnings("unchecked")
        final PacketRegistry<ClientPacket> registry =
                (PacketRegistry<ClientPacket>) PacketVanilla.CLIENT_PACKET_PARSER.stateRegistry(state);
        final PacketRegistry.PacketInfo<ClientPacket> info = registry.packetInfo(packet);
        return NetworkBuffer.makeArray(buffer -> {
            buffer.write(NetworkBuffer.VAR_INT, info.id());
            info.serializer().write(buffer, packet);
        });
    }

    /**
     * The id of a server packet class in a state.
     *
     * @param state the state
     * @param type  the packet class
     * @return its id
     */
    static int serverId(ConnectionState state, Class<? extends ServerPacket> type) {
        return PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(state).packetInfo(type).id();
    }

    /**
     * The names of the server packets of a state, by id.
     *
     * @param state the state
     * @return the simple class names
     */
    static List<String> serverNames(ConnectionState state) {
        final PacketRegistry<? extends ServerPacket> registry = PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(state);
        final List<String> names = new ArrayList<>();
        for (PacketRegistry.PacketInfo<? extends ServerPacket> info : registry) {
            while (names.size() <= info.id()) names.add("Unknown(" + names.size() + ")");
            names.set(info.id(), info.packetClass().getSimpleName());
        }
        return List.copyOf(names);
    }

    /**
     * Decodes a server packet body that follows its id.
     *
     * @param state  the state it was received in
     * @param id     its id
     * @param body   the whole body, id included
     * @param offset where the payload starts
     * @return the packet, null if the engine's codec cannot read it without registries
     */
    static @Nullable ServerPacket decode(ConnectionState state, int id, byte[] body, int offset) {
        try {
            final NetworkBuffer buffer = NetworkBuffer.wrap(body, offset, body.length);
            return PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(state).packetInfo(id).serializer().read(buffer);
        } catch (RuntimeException _) {
            return null;
        }
    }
}
