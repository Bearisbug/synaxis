package io.github.bearisbug.synaxis.network.packet.server;

import io.github.bearisbug.synaxis.adventure.ComponentHolder;
import io.github.bearisbug.synaxis.network.packet.Packet;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;

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
