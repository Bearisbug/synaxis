package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.entity.PlayerHand;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientAnimationPacket;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SystemChatPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class SendablePacketTest {

    @Test
    public void cached() {
        var packet = new SystemChatPacket(Component.text("Hello World!"), false);
        var cached = new CachedPacket(packet);
        assertSame(packet, cached.packet());

        var buffer = PacketWriting.allocateTrimmedPacket(ConnectionState.PLAY, packet, null, 256);
        var cachedBuffer = cached.body(ConnectionState.PLAY, null, 256);
        assertTrue(NetworkBuffer.equals(buffer, cachedBuffer));
        // May fail in the very unlikely case where soft references are cleared
        // Rare enough to make this test worth it
        assertSame(cached.body(ConnectionState.PLAY, null, 256), cachedBuffer);

        assertSame(packet, cached.packet());
    }

    @Test
    public void trimmed() throws DataFormatException {
        var packet = new ClientAnimationPacket(PlayerHand.MAIN);

        var buffer = PacketWriting.allocateTrimmedPacket(ConnectionState.PLAY, packet, null, 0);

        var result = PacketReading.readClient(buffer, ConnectionState.PLAY, false);
        if (!(result instanceof PacketReading.Result.Success<ClientPacket>(
                List<PacketReading.ParsedPacket<ClientPacket>> packets
        ))) {
            fail();
            return;
        }
        assertEquals(1, packets.size());
        ClientPacket readPacket = packets.getFirst().packet();
        assertEquals(packet, readPacket);
    }
}
