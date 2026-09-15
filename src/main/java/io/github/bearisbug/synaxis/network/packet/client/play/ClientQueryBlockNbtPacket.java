package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.VAR_INT;

public record ClientQueryBlockNbtPacket(int transactionId, Point blockPosition) implements ClientPacket.Play {
    public static final NetworkBuffer.Type<ClientQueryBlockNbtPacket> SERIALIZER = NetworkBufferTemplate.template(
            VAR_INT, ClientQueryBlockNbtPacket::transactionId,
            BLOCK_POSITION, ClientQueryBlockNbtPacket::blockPosition,
            ClientQueryBlockNbtPacket::new);
}
