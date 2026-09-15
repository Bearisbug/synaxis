package io.github.bearisbug.synaxis.listener.preplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientFinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginPluginResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientConfigurationAckPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.network.plugin.LoginPlugin;
import io.github.bearisbug.synaxis.network.plugin.LoginPluginMessageProcessor;
import io.github.bearisbug.synaxis.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;

import static io.github.bearisbug.synaxis.network.NetworkBuffer.STRING;

public final class LoginListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoginListener.class);

    private static final Component ERROR_DURING_LOGIN = Component.text("Error during login!", NamedTextColor.RED);
    private static final Component ERROR_MALFORMED_USERNAME = Component.text("Malformed username!", NamedTextColor.RED);

    public static final Component INVALID_PROXY_RESPONSE = Component.text("Invalid proxy response!", NamedTextColor.RED);

    public static void loginStartListener(ClientLoginStartPacket packet, PlayerConnection connection) {
        if (!StringUtils.isValidUsername(packet.username())) {
            connection.kick(ERROR_MALFORMED_USERNAME);
            return;
        }

        final Auth auth = connection.process().auth();
        final boolean isSocketConnection = connection instanceof PlayerSocketConnection;
        // Proxy support (only for socket clients) and cache the login username
        if (isSocketConnection) {
            PlayerSocketConnection socketConnection = (PlayerSocketConnection) connection;
            socketConnection.UNSAFE_setLoginUsername(packet.username());
            // Velocity support
            if (auth instanceof Auth.Velocity) {
                // Dont block the connection so we can still read more packets
                var _ = connection.loginPluginMessageProcessor().request(Auth.Velocity.PLAYER_INFO_CHANNEL, new byte[0])
                        .thenAccept(response -> handleVelocityProxyResponse(socketConnection, response));
                return;
            }
        }

        final GameProfile gameProfile;
        if (auth instanceof Auth.Bungee) {
            // LEGACY FORWARDING
            // Use game profile set during handshake
            assert connection instanceof PlayerSocketConnection;
            final GameProfile bungeeProfile = ((PlayerSocketConnection) connection).gameProfile();
            assert bungeeProfile != null;
            gameProfile = new GameProfile(bungeeProfile.uuid(), packet.username(), bungeeProfile.properties());
        } else {
            gameProfile = new GameProfile(packet.profileId(), packet.username());
        }
        enterConfig(connection, gameProfile);
    }

    private static void handleVelocityProxyResponse(PlayerSocketConnection socketConnection, LoginPlugin.Response response) {
        if (!(socketConnection.process().auth() instanceof Auth.Velocity velocity)) {
            socketConnection.kick(Component.text("Login plugin response is not supported in this auth mode", NamedTextColor.RED));
            return;
        }

        final byte[] data = response.payload();
        SocketAddress socketAddress = null;
        GameProfile gameProfile = null;
        boolean success = false;
        if (data != null && data.length > 0) {
            NetworkBuffer buffer = NetworkBuffer.wrap(data, 0, data.length);
            success = velocity.checkIntegrity(buffer);
            if (success) {
                // Get the real connection address
                final InetAddress address;
                try {
                    address = InetAddress.getByName(buffer.read(STRING));
                } catch (UnknownHostException e) {
                    socketConnection.kick(INVALID_PROXY_RESPONSE);
                    socketConnection.process().exception().handleException(e);
                    return;
                }
                final int port = ((InetSocketAddress) socketConnection.getRemoteAddress()).getPort();
                socketAddress = new InetSocketAddress(address, port);
                gameProfile = GameProfile.SERIALIZER.read(buffer);
            }
        }
        if (!success) {
            socketConnection.kick(INVALID_PROXY_RESPONSE);
            return;
        }
        socketConnection.setRemoteAddress(socketAddress);
        enterConfig(socketConnection, gameProfile);
    }

    public static void loginPluginResponseListener(ClientLoginPluginResponsePacket packet, PlayerConnection connection) {
        try {
            LoginPluginMessageProcessor messageProcessor = connection.loginPluginMessageProcessor();
            messageProcessor.handleResponse(packet.messageId(), packet.data());
        } catch (Throwable t) {
            connection.kick(ERROR_DURING_LOGIN);
            LOGGER.error("Error handling Login Plugin Response", t);
            connection.process().exception().handleException(t);
        }
    }

    public static void loginAckListener(ClientLoginAcknowledgedPacket ignored, PlayerConnection connection) {
        if (!(connection instanceof PlayerSocketConnection socketConnection))
            throw new UnsupportedOperationException("Only socket");
        final GameProfile gameProfile = socketConnection.gameProfile();
        assert gameProfile != null;
        try {
            final Player player = connection.process().connection().createPlayer(connection, gameProfile);
            executeConfig(player, true);
        } catch (Throwable t) {
            connection.process().exception().handleException(t);
            connection.kick(ERROR_DURING_LOGIN);
        }
    }

    public static void configAckListener(ClientConfigurationAckPacket packet, Player player) {
        final PlayerConnection connection = player.getPlayerConnection();
        if (connection.getServerState() != ConnectionState.CONFIGURATION) {
            // The server never sent StartConfigurationPacket, running the configuration phase
            // would send CONFIGURATION-only packets while the server state is still PLAY.
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        executeConfig(player, false);
    }

    public static void selectKnownPacks(ClientSelectKnownPacksPacket packet, Player player) {
        player.getPlayerConnection().receiveKnownPacksResponse(packet.entries());
    }

    public static void finishConfigListener(ClientFinishConfigurationPacket packet, Player player) {
        final PlayerConnection connection = player.getPlayerConnection();
        if (connection.getServerState() != ConnectionState.PLAY) {
            // The server has not written FinishConfigurationPacket yet, so the spawning instance
            // is not decided. Accepting the acknowledgement would spawn the player without one.
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        connection.process().connection().transitionConfigToPlay(player);
    }

    private static void enterConfig(PlayerConnection connection, GameProfile gameProfile) {
        Thread.startVirtualThread(() -> {
            try {
                connection.process().connection().transitionLoginToConfig(connection, gameProfile);
            } catch (Throwable t) {
                connection.process().exception().handleException(t);
            }
        });
    }

    private static void executeConfig(Player player, boolean isFirstConfig) {
        // We have to create another thread (even though we should already be in a virtual thread)
        // because configuration handling involves waiting for the client to send a known packs packet.
        // Which mean that we have to free up the current thread to continue reading the socket.
        Thread.startVirtualThread(() -> {
            try {
                player.process().connection().doConfiguration(player, isFirstConfig);
            } catch (Throwable t) {
                player.process().exception().handleException(t);
                player.kick(ERROR_DURING_LOGIN);
            }
        });
    }
}
