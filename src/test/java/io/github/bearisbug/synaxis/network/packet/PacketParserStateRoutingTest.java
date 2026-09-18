package io.github.bearisbug.synaxis.network.packet;

import io.github.bearisbug.synaxis.network.ConnectionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class PacketParserStateRoutingTest {

    @Test
    public void everyStateOfTheClientParserRoutesToItsOwnRegistry() {
        final var parser = PacketVanilla.CLIENT_PACKET_PARSER;
        for (ConnectionState state : ConnectionState.values()) {
            final var registry = parser.stateRegistry(state);
            assertEquals(state, registry.state(), "The registry must own the state it is routed from");
            assertEquals(PacketRegistry.ConnectionSide.CLIENT, registry.side());
        }
    }

    @Test
    public void everyStateOfTheServerParserRoutesToItsOwnRegistry() {
        final var parser = PacketVanilla.SERVER_PACKET_PARSER;
        for (ConnectionState state : ConnectionState.values()) {
            final var registry = parser.stateRegistry(state);
            assertEquals(state, registry.state());
            assertEquals(PacketRegistry.ConnectionSide.SERVER, registry.side());
        }
    }

    @Test
    public void theRoutingMatchesTheNamedAccessors() {
        final var parser = PacketVanilla.CLIENT_PACKET_PARSER;
        assertSame(parser.handshake(), parser.stateRegistry(ConnectionState.HANDSHAKE));
        assertSame(parser.status(), parser.stateRegistry(ConnectionState.STATUS));
        assertSame(parser.login(), parser.stateRegistry(ConnectionState.LOGIN));
        assertSame(parser.configuration(), parser.stateRegistry(ConnectionState.CONFIGURATION));
        assertSame(parser.play(), parser.stateRegistry(ConnectionState.PLAY));
    }
}
