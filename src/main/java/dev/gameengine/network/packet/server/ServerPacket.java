package dev.gameengine.network.packet.server;

import dev.gameengine.adventure.ComponentHolder;
import dev.gameengine.network.packet.Packet;
import dev.gameengine.network.player.PlayerConnection;

/**
 * Represents a packet which can be sent to a player using {@link PlayerConnection#sendPacket(SendablePacket)}.
 * <p>
 * Packets are value-based, and should therefore not be reliant on identity.
 */
public sealed interface ServerPacket extends Packet, SendablePacket {

    // By default, this isn't used
    non-sealed interface Handshake extends ServerPacket {
    }

    non-sealed interface Status extends ServerPacket {
    }

    non-sealed interface Login extends ServerPacket {
    }

    non-sealed interface Configuration extends ServerPacket {
    }

    non-sealed interface Play extends ServerPacket {
    }

    interface ComponentHolding extends ComponentHolder<ServerPacket> {
    }
}
