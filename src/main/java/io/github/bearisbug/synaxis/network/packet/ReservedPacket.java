package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.NetworkBufferTemplate;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

/**
 * Holds the id of a vanilla clientbound packet the engine does not have (removed with its feature,
 * REQ-005). Packet ids are positions in the registry, so the slot has to stay taken or every packet
 * after it goes out one id too low. A registry does not look it up by class or by id, so it can
 * never be sent or read.
 */
record ReservedPacket() implements ServerPacket.Login, ServerPacket.Play {
    static final NetworkBuffer.Type<ReservedPacket> SERIALIZER = NetworkBufferTemplate.template(new ReservedPacket());
}
