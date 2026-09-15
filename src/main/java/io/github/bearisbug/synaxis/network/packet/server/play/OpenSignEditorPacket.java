package io.github.bearisbug.synaxis.network.packet.server.play;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.BLOCK_POSITION;
import static io.github.bearisbug.synaxis.network.NetworkBuffer.BOOLEAN;

public record OpenSignEditorPacket(Point position, boolean isFrontText) implements ServerPacket.Play {
    public static final NetworkBuffer.Type<OpenSignEditorPacket> SERIALIZER = NetworkBufferTemplate.template(
            BLOCK_POSITION, OpenSignEditorPacket::position,
            BOOLEAN, OpenSignEditorPacket::isFrontText,
            OpenSignEditorPacket::new);
}
