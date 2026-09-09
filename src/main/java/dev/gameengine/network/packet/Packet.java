package dev.gameengine.network.packet;

import dev.gameengine.network.packet.client.ClientPacket;
import dev.gameengine.network.packet.server.ServerPacket;

public sealed interface Packet permits ClientPacket, ServerPacket {
}
