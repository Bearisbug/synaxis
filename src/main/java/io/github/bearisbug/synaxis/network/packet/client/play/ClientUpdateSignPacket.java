package io.github.bearisbug.synaxis.network.packet.client.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;

import java.util.List;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public record ClientUpdateSignPacket(
        Point blockPosition,
        boolean isFrontText,
        List<String> lines
) implements ClientPacket.Play {
    public ClientUpdateSignPacket {
        lines = List.copyOf(lines);
        if (lines.size() != 4) {
            throw new IllegalArgumentException("Signs must have 4 lines!");
        }
        for (String line : lines) {
            if (line.length() > 384) {
                throw new IllegalArgumentException("Signs must have a maximum of 384 characters per line!");
            }
        }
    }

    public static final NetworkBuffer.Type<ClientUpdateSignPacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, ClientUpdateSignPacket::blockPosition,
            BOOLEAN, ClientUpdateSignPacket::isFrontText,
            STRING, p -> p.lines().get(0),
            STRING, p -> p.lines().get(1),
            STRING, p -> p.lines().get(2),
            STRING, p -> p.lines().get(3),
            (blockPosition, isFrontText, line0, line1, line2, line3) ->
                    new ClientUpdateSignPacket(blockPosition, isFrontText, List.of(line0, line1, line2, line3)));
}
