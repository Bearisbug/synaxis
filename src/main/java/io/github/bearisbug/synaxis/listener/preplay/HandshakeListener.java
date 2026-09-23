package io.github.bearisbug.synaxis.listener.preplay;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;

public final class HandshakeListener {

    /**
     * Text sent if a player was transferred to this server but the {@link ServerConfig#acceptTransfers()} setting is not enabled.
     */
    private static final Component TRANSFERS_DISABLED_TEXT = Component.translatable("multiplayer.disconnect.transfers_disabled");

    /** The longest server address a vanilla client sends. */
    private static final int MAX_SERVER_ADDRESS_LENGTH = 255;

    @SuppressWarnings("fallthrough") // transfers deliberately continue with the regular login flow
    public static void listener(ClientHandshakePacket packet, PlayerConnection connection) {
        final String address = packet.serverAddress();
        if (address.length() > MAX_SERVER_ADDRESS_LENGTH) {
            throw new IllegalArgumentException("Server address too long: " + address.length());
        }
        if (connection instanceof PlayerSocketConnection socketConnection
                && !passesTheGate(socketConnection, packet.intent())) return;

        switch (packet.intent()) {
            case TRANSFER:
                connection.markTransferred(true);

                if (!connection.process().config().acceptTransfers()) {
                    connection.kick(TRANSFERS_DISABLED_TEXT);
                    return;
                }
            case LOGIN:
                if (packet.protocolVersion() != MinecraftServer.PROTOCOL_VERSION) {
                    // Incorrect client version
                    connection.kick(DisconnectReasons.UNSUPPORTED_VERSION);
                    break;
                }
            default:
                break;
        }

        if (connection instanceof PlayerSocketConnection socketConnection) {
            socketConnection.refreshServerInformation(address, packet.serverPort(), packet.protocolVersion());
        }
    }

    /**
     * Connection gate L1 (with the PROXY protocol, whose header has named the real address by now)
     * and L2. A status handshake turned away is closed, a login or transfer one is told why. Neither
     * is logged one by one, the gate counts them.
     */
    private static boolean passesTheGate(PlayerSocketConnection connection, ClientHandshakePacket.Intent intent) {
        final ConnectionGate gate = connection.process().connection().gate();
        final ConnectionGate.Verdict verdict = gate.handshake(connection.gateTicket(), connection.getRemoteAddress(), intent);
        if (verdict == ConnectionGate.Verdict.PASS) return true;
        // The connection state already follows the intent: LOGIN for a login or a transfer
        if (intent != ClientHandshakePacket.Intent.STATUS) {
            connection.sendPacket(new LoginDisconnectPacket(verdict == ConnectionGate.Verdict.TOO_MANY
                    ? DisconnectReasons.TOO_MANY_CONNECTIONS : DisconnectReasons.LOGIN_THROTTLED));
        }
        connection.disconnectTurnedAway();
        return false;
    }
}
