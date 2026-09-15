package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;

public sealed interface Packet permits ClientPacket, ServerPacket {
}
