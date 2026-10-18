package io.github.bearisbug.synaxis.listener.preplay;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.crypto.LoginCrypt;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientFinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientEncryptionResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginPluginResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientConfigurationAckPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.EncryptionRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.network.player.SessionClient;
import io.github.bearisbug.synaxis.network.plugin.LoginPluginMessageProcessor;
import io.github.bearisbug.synaxis.utils.StringUtils;

import java.net.SocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class LoginListener {
    private static final SecureRandom CHALLENGE_RANDOM = new SecureRandom();

    private static final Component ERROR_DURING_LOGIN = Component.text("Error during login!", NamedTextColor.RED);
    private static final Component ERROR_MALFORMED_USERNAME = Component.text("Malformed username!", NamedTextColor.RED);

    public static final Component INVALID_PROXY_RESPONSE = Component.text("Invalid proxy response!", NamedTextColor.RED);

    public static void loginStartListener(ClientLoginStartPacket packet, PlayerConnection connection) {
        if (!connection.claimLoginStart()) {
            // A client sends this packet once. Every repeat runs the whole login again on a new
            // virtual thread, and none of it requires the peer to be authenticated.
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        if (!StringUtils.isValidUsername(packet.username())) {
            connection.kick(ERROR_MALFORMED_USERNAME);
            return;
        }
        // A process shutting down lets nobody new in (docs/DESIGN-PROD.md §11.4 gate_closed)
        if (connection.process().connection().gate().isClosed()) {
            connection.sendPacket(new LoginDisconnectPacket(DisconnectReasons.SERVER_STOPPING));
            connection.disconnectTurnedAway();
            return;
        }
        // Connection gate L3, before any work is done for the login: a full process is not worth
        // a key exchange or a session server query. The seat itself is taken once the peer is
        // authenticated (ConnectionManager#transitionLoginToConfig). Counted by the gate rather
        // than logged, how often a full process is knocked at is up to the peers.
        if (connection instanceof PlayerSocketConnection
                && !connection.process().connection().gate().seatFree()) {
            connection.sendPacket(new LoginDisconnectPacket(DisconnectReasons.SHARD_FULL));
            connection.disconnectTurnedAway();
            return;
        }

        final Auth auth = connection.process().auth();
        final boolean isSocketConnection = connection instanceof PlayerSocketConnection;
        // Socket clients: cache the login username
        if (isSocketConnection) {
            PlayerSocketConnection socketConnection = (PlayerSocketConnection) connection;
            socketConnection.UNSAFE_setLoginUsername(packet.username());
            // Online mode and encrypted offline mode: the login goes on once the key arrives
            final KeyPair keyPair = connection.process().connection().loginKeyPair();
            if (keyPair != null) {
                final byte[] challenge = new byte[4];
                CHALLENGE_RANDOM.nextBytes(challenge);
                socketConnection.expectEncryptionResponse(challenge);
                socketConnection.sendPacket(new EncryptionRequestPacket("", keyPair.getPublic().getEncoded(),
                        challenge, auth instanceof Auth.Online));
                return;
            }
        }

        // Offline mode: the uuid is the server's derivation from the username, not the one the
        // login start carries. That one is the peer's to choose, and the duplicate login guard
        // keys on the uuid: taken as sent, one name could be online twice under two uuids, and
        // a peer could sit on the uuid of a name it does not use.
        enterConfig(connection, new GameProfile(GameProfile.offlineUuid(packet.username()), packet.username()));
    }

    /**
     * The client's key ({@code docs/DESIGN-PROD.md} §11.1 {@code awaiting_key}), handled on the read
     * thread: one response per encryption request, the challenge must come back, the secret must be
     * an AES-128 key. Then the cipher is switched on before anything else is sent, so that every
     * answer from here on, a refusal included, reaches a client that already decrypts. A peer that
     * gets any of this wrong is not a vanilla client and is dropped without asking the session
     * server.
     */
    public static void loginEncryptionResponseListener(ClientEncryptionResponsePacket packet, PlayerConnection connection) {
        final KeyPair keyPair = connection.process().connection().loginKeyPair();
        if (!(connection instanceof PlayerSocketConnection socketConnection) || keyPair == null) {
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        final byte[] challenge = socketConnection.claimEncryptionResponse();
        final String username = socketConnection.getLoginUsername();
        if (challenge == null || username == null) {
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        final byte[] secret;
        try {
            final byte[] echoed = LoginCrypt.decrypt(keyPair.getPrivate(), packet.encryptedVerifyToken());
            if (!MessageDigest.isEqual(challenge, echoed)) {
                connection.kick(DisconnectReasons.PROTOCOL_ERROR);
                return;
            }
            secret = LoginCrypt.decrypt(keyPair.getPrivate(), packet.sharedSecret());
        } catch (GeneralSecurityException _) {
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        if (secret.length != LoginCrypt.SHARED_SECRET_LENGTH) {
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        socketConnection.enableEncryption(LoginCrypt.sharedSecret(secret));

        final SessionClient sessionClient = connection.process().connection().sessionClient();
        if (sessionClient == null) {
            // Encrypted offline mode, the identity is the same as without encryption
            enterConfig(connection, new GameProfile(GameProfile.offlineUuid(username), username));
            return;
        }
        final String serverHash = LoginCrypt.serverHash("", secret, keyPair.getPublic());
        final SocketAddress address = socketConnection.getRemoteAddress();
        final long deadline = socketConnection.loginDeadlineMillis();
        Thread.ofVirtual().name("Syn-Session-Verify").start(() -> {
            try {
                verify(socketConnection, sessionClient, username, serverHash, address, deadline);
            } catch (Throwable t) {
                connection.process().exception().handleException(t, connection.connectionId());
                connection.kick(ERROR_DURING_LOGIN);
            }
        });
    }

    /**
     * Asks the session server, then goes on with the profile it answered with or turns the
     * connection away. A refusal is counted by the session client rather than logged, how often a
     * name fails verification is up to the peers.
     */
    private static void verify(PlayerSocketConnection connection, SessionClient sessionClient, String username,
                               String serverHash, SocketAddress address, long deadline) throws InterruptedException {
        final SessionClient.Result result = sessionClient.verify(username, serverHash, address, deadline, connection::isOnline);
        // The peer left while the session server was asked, the answer has nobody to go to
        if (!connection.isOnline()) return;
        switch (result) {
            case SessionClient.Result.Verified verified ->
                    connection.process().connection().transitionLoginToConfig(connection, verified.profile());
            case SessionClient.Result.Unverified _ -> turnAway(connection, DisconnectReasons.AUTH_FAILED);
            case SessionClient.Result.Unavailable _ -> turnAway(connection, DisconnectReasons.AUTH_UNAVAILABLE);
        }
    }

    private static void turnAway(PlayerSocketConnection connection, Component reason) {
        connection.sendPacket(new LoginDisconnectPacket(reason));
        connection.disconnectTurnedAway();
    }

    public static void loginPluginResponseListener(ClientLoginPluginResponsePacket packet, PlayerConnection connection) {
        try {
            LoginPluginMessageProcessor messageProcessor = connection.loginPluginMessageProcessor();
            messageProcessor.handleResponse(packet.messageId(), packet.data());
        } catch (Throwable t) {
            // One report per connection, not per packet: a peer fits thousands of plugin responses
            // into one read, and the kick is what ends that batch (PlayerSocketConnection#processPackets).
            // The report goes to the exception handler only, which bounds its lines: the peer is not
            // authenticated and decides how many connections ask for one.
            connection.kick(ERROR_DURING_LOGIN);
            connection.process().exception().handleException(t, connection.connectionId());
        }
    }

    public static void loginAckListener(ClientLoginAcknowledgedPacket ignored, PlayerConnection connection) {
        if (!(connection instanceof PlayerSocketConnection socketConnection))
            throw new UnsupportedOperationException("Only socket");
        final GameProfile gameProfile = socketConnection.gameProfile();
        if (gameProfile == null || connection.getServerState() != ConnectionState.CONFIGURATION) {
            // The peer acknowledged a login success the server has not written: that is the peer
            // breaking the protocol, not a failure of the server (docs/DESIGN.md §11.1)
            connection.kick(DisconnectReasons.PROTOCOL_ERROR);
            return;
        }
        try {
            if (connection.process().auth() instanceof Auth.Online) {
                // The uuid is Mojang's: a live session holding it is the same account logging in
                // again, and it is the one that makes room (docs/DESIGN-PROD.md REQ-PROD-004)
                final Player player = connection.process().connection().createPlayerReplacing(connection, gameProfile);
                if (player == null) {
                    connection.kick(refusal(connection, DisconnectReasons.STATE_UNAVAILABLE));
                    return;
                }
                executeConfig(player, true);
                return;
            }
            final Player player = connection.process().connection().createPlayer(connection, gameProfile);
            if (player == null) {
                // The uuid is already in the world. Letting this connection continue would take it
                // into the play state and only there hit the entity tracker, which refuses the
                // duplicate uuid and leaves an untracked player behind. The newcomer is the one
                // rejected: in offline mode the uuid is derived from the username, so kicking the
                // established session instead would hand anyone a way to evict anyone else.
                connection.kick(refusal(connection, DisconnectReasons.DUPLICATE_LOGIN));
                return;
            }
            executeConfig(player, true);
        } catch (Throwable t) {
            connection.process().exception().handleException(t, connection.connectionId());
            connection.kick(ERROR_DURING_LOGIN);
        }
    }

    /**
     * Why a connection is refused its player: once the connection manager has shut down it refuses
     * every one, and the client is told the server stops ({@code docs/DESIGN-PROD.md} §11.4
     * {@code kicking}) rather than the reason of a live process.
     */
    private static Component refusal(PlayerConnection connection, Component live) {
        return connection.process().connection().isShutDown() ? DisconnectReasons.SERVER_STOPPING : live;
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
                connection.process().exception().handleException(t, connection.connectionId());
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
                player.process().exception().handleException(t, player.getPlayerConnection().connectionId());
                player.kick(ERROR_DURING_LOGIN);
            }
        });
    }
}
