package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.CookieRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.ResourcePackPopPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.ShowDialogPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.TagsPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.EncryptionRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.AcknowledgeBlockChangePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DeleteChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DestroyEntitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityAnimationPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityAttributesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.InitializeWorldBorderPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.JoinGamePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerAbilitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ServerDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetPassengersPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateSimulationDistancePacket;
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

    @Test
    public void theClientBoundLoginIdsAreTheVanillaOnes() {
        // 26.2 LoginProtocols: login_disconnect, hello, login_finished, login_compression,
        // custom_query, cookie_request. hello is the encryption request of online mode.
        final var login = PacketVanilla.SERVER_PACKET_PARSER.login();
        assertEquals(0, login.packetInfo(LoginDisconnectPacket.class).id());
        assertEquals(2, login.packetInfo(LoginSuccessPacket.class).id());
        assertEquals(3, login.packetInfo(SetCompressionPacket.class).id());
        assertEquals(4, login.packetInfo(LoginPluginRequestPacket.class).id());
        assertEquals(5, login.packetInfo(CookieRequestPacket.class).id());
        assertEquals(1, login.packetInfo(EncryptionRequestPacket.class).id());
    }

    @Test
    public void theClientBoundPlayIdsAreTheVanillaOnes() {
        // 26.2 GameProtocols has 141 clientbound play ids; the engine lacks 18 of them. Each packet
        // right after a reserved run, and the last one, sits where the vanilla client expects it.
        final var play = PacketVanilla.SERVER_PACKET_PARSER.play();
        assertEquals(2, play.packetInfo(EntityAnimationPacket.class).id());
        assertEquals(4, play.packetInfo(AcknowledgeBlockChangePacket.class).id());
        assertEquals(31, play.packetInfo(DeleteChatPacket.class).id());
        assertEquals(43, play.packetInfo(InitializeWorldBorderPacket.class).id());
        assertEquals(49, play.packetInfo(JoinGamePacket.class).id());
        assertEquals(64, play.packetInfo(PlayerAbilitiesPacket.class).id());
        assertEquals(77, play.packetInfo(DestroyEntitiesPacket.class).id());
        assertEquals(80, play.packetInfo(ResourcePackPopPacket.class).id());
        assertEquals(86, play.packetInfo(ServerDataPacket.class).id());
        assertEquals(99, play.packetInfo(EntityMetaDataPacket.class).id());
        assertEquals(107, play.packetInfo(SetPassengersPacket.class).id());
        assertEquals(111, play.packetInfo(UpdateSimulationDistancePacket.class).id());
        assertEquals(131, play.packetInfo(EntityAttributesPacket.class).id());
        assertEquals(134, play.packetInfo(TagsPacket.class).id());
        assertEquals(140, play.packetInfo(ShowDialogPacket.class).id());
        assertThrows(IllegalStateException.class, () -> play.packetInfo(3));
        assertThrows(IllegalStateException.class, () -> play.packetInfo(ReservedPacket.class));
    }
}
