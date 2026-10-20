package io.github.bearisbug.synaxis.network.packet.client;

import io.github.bearisbug.synaxis.network.packet.Packet;

/**
 * Represents a packet received from a client.
 * <p>
 * The records themselves are immutable, but their {@code byte[]} and {@code BitSet} accessors
 * return the storage of the packet, shared by every listener that receives it: do not modify
 * them, copy them first to make changes.
 */
public sealed interface ClientPacket extends Packet {
    non-sealed interface Handshake extends ClientPacket {
    }

    non-sealed interface Status extends ClientPacket {
    }

    non-sealed interface Login extends ClientPacket {
    }

    non-sealed interface Configuration extends ClientPacket {
    }

    non-sealed interface Play extends ClientPacket {
    }
}