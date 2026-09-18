package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PacketRegistryLookupTest {

    private static PacketRegistry<ClientPacket.Play> clientPlay() {
        return PacketVanilla.CLIENT_PACKET_PARSER.play();
    }

    @Test
    public void aPacketIdIsItsIndexInTheRegistryDeclaration() {
        final PacketRegistry<ClientPacket.Play> registry = clientPlay();
        List<PacketRegistry.PacketInfo<? extends ClientPacket.Play>> infos = new ArrayList<>();
        registry.forEach(infos::add);
        assertTrue(infos.size() > 1);

        for (int id = 0; id < infos.size(); id++) {
            final var info = infos.get(id);
            assertEquals(id, info.id(), "The iteration order is the id order");
            assertSame(info, registry.packetInfo(id));
            assertSame(info, registry.packetInfo(info.packetClass()));
        }
    }

    @Test
    public void anIdOutsideTheRegistryIsRejected() {
        final PacketRegistry<ClientPacket.Play> registry = clientPlay();
        int size = 0;
        for (var _ : registry) size++;

        final int beyond = size;
        assertThrows(IllegalStateException.class, () -> registry.packetInfo(beyond));
        assertThrows(IllegalStateException.class, () -> registry.packetInfo(-1));
    }

    @Test
    public void aPacketOfTheWrongStateIsRejectedWithBothSideAndState() {
        final PacketRegistry<ClientPacket.Handshake> handshake = PacketVanilla.CLIENT_PACKET_PARSER.handshake();
        final var error = assertThrows(IllegalStateException.class,
                () -> handshake.packetInfo(ClientPluginMessagePacket.class));

        assertTrue(error.getMessage().contains("CLIENT_HANDSHAKE"),
                "The error has to name the side and the state: " + error.getMessage());
    }

    @Test
    public void theHandshakeRegistryDescribesItselfAsTheClientHandshake() {
        final PacketRegistry<ClientPacket.Handshake> handshake = PacketVanilla.CLIENT_PACKET_PARSER.handshake();
        assertEquals(ConnectionState.HANDSHAKE, handshake.state());
        assertEquals(PacketRegistry.ConnectionSide.CLIENT, handshake.side());
        assertEquals(0, handshake.packetInfo(ClientHandshakePacket.class).id());
    }

    @Test
    public void theSameClassInBothDirectionsKeepsSeparateRegistries() {
        final var serverPlay = PacketVanilla.SERVER_PACKET_PARSER.play();
        assertEquals(PacketRegistry.ConnectionSide.SERVER, serverPlay.side());
        assertEquals(ConnectionState.PLAY, serverPlay.state());
        // A server packet is unknown to the client-bound registry of the same state
        assertThrows(IllegalStateException.class, () -> clientPlay().packetInfo(PluginMessagePacket.class));
    }
}
