package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.KeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.ResetChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.UpdateEnabledFeaturesPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.StartConfigurationPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.network.plugin.LoginPluginMessageProcessor;
import io.github.bearisbug.synaxis.registry.Registries;
import io.github.bearisbug.synaxis.registry.StaticProtocolObject;
import io.github.bearisbug.synaxis.utils.StringUtils;
import io.github.bearisbug.synaxis.utils.collection.ConcurrentMessageQueues;
import org.jctools.queues.MessagePassingQueue;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Manages the connected clients.
 */
public final class ConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionManager.class);

    private static final Component TIMEOUT_TEXT = Component.text("Timeout", NamedTextColor.RED);
    private static final long DISCONNECT_FLUSH_TIMEOUT = 1_000;

    private final ServerProcess process;
    private final CachedPacket cachedTagsPacket;

    public ConnectionManager(ServerProcess process) {
        this.process = process;
        this.cachedTagsPacket = new CachedPacket(() -> Registries.tagsPacket(process.registries()));
    }

    // All players once their Player object has been instantiated.
    private final Map<PlayerConnection, Player> connectionPlayerMap = new ConcurrentHashMap<>();
    // The same players keyed by profile uuid, which owns the uuid until its connection is dropped
    private final Map<UUID, Player> uuidPlayerMap = new ConcurrentHashMap<>();
    // Players waiting to be spawned (post configuration state)
    private final MessagePassingQueue<Player> playWaitingPlayers = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    // Players waiting to be (re) configured
    private final MessagePassingQueue<Player> configWaitingPlayers = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    // Players in configuration state
    private final Set<Player> configurationPlayers = new CopyOnWriteArraySet<>();
    // Players in play state
    private final Set<Player> playPlayers = new CopyOnWriteArraySet<>();

    // The players who need keep alive ticks. This was added because we may not send a keep alive in
    // the time after sending finish configuration but before receiving configuration end (to swap to play).
    // I(mattw) could not come up with a better way to express this besides completely splitting client/server
    // states. Perhaps there will be an improvement in the future.
    private final Set<Player> keepAlivePlayers = new CopyOnWriteArraySet<>();

    private final Set<Player> unmodifiableConfigurationPlayers = Collections.unmodifiableSet(configurationPlayers);
    private final Set<Player> unmodifiablePlayPlayers = Collections.unmodifiableSet(playPlayers);

    // The player provider to have your own Player implementation
    private volatile PlayerProvider playerProvider = Player::new;

    /**
     * Gets the number of "online" players, e.g. for the query response.
     *
     * <p>Only includes players in the play state, not players in configuration.</p>
     */
    public int getOnlinePlayerCount() {
        return playPlayers.size();
    }

    /**
     * Returns an unmodifiable set containing the players currently in the play state.
     */
    @SuppressWarnings("PreferredInterfaceType") // wider type kept for binary compatibility until the next breaking release
    public Collection<Player> getOnlinePlayers() {
        return unmodifiablePlayPlayers;
    }

    /**
     * Returns an unmodifiable set containing the players currently in the configuration state.
     */
    @SuppressWarnings("PreferredInterfaceType") // wider type kept for binary compatibility until the next breaking release
    public Collection<Player> getConfigPlayers() {
        return unmodifiableConfigurationPlayers;
    }

    /**
     * Gets the {@link Player} linked to a {@link PlayerConnection}.
     *
     * <p>The player will be returned whether they are in the play or config state,
     * so be sure to check before sending packets to them.</p>
     *
     * @param connection the player connection
     * @return the player linked to the connection
     */
    public @Nullable Player getPlayer(PlayerConnection connection) {
        return connectionPlayerMap.get(connection);
    }

    /**
     * Gets the first player in the play state which validates {@link String#equalsIgnoreCase(String)}.
     * <p>
     * This can cause issue if two or more players have the same username.
     *
     * @param username the player username (case-insensitive)
     * @return the first player who validate the username condition, null if none was found
     */
    public @Nullable Player getOnlinePlayerByUsername(String username) {
        for (Player player : getOnlinePlayers()) {
            if (player.getUsername().equalsIgnoreCase(username))
                return player;
        }
        return null;
    }

    /**
     * Gets the first player in the play state which validates {@link UUID#equals(Object)}.
     * <p>
     * This can cause issue if two or more players have the same UUID.
     *
     * @param uuid the player UUID
     * @return the first player who validate the UUID condition, null if none was found
     */
    public @Nullable Player getOnlinePlayerByUuid(UUID uuid) {
        for (Player player : getOnlinePlayers()) {
            if (player.getUuid().equals(uuid))
                return player;
        }
        return null;
    }

    /**
     * Finds the closest player in the play state matching a given username.
     *
     * @param username the player username (can be partial)
     * @return the closest match, null if no players are online
     */
    public @Nullable Player findOnlinePlayer(String username) {
        Player exact = getOnlinePlayerByUsername(username);
        if (exact != null) return exact;
        final String username1 = username.toLowerCase(Locale.ROOT);

        Function<Player, Double> distanceFunction = player -> {
            final String username2 = player.getUsername().toLowerCase(Locale.ROOT);
            return StringUtils.jaroWinklerScore(username1, username2);
        };
        return getOnlinePlayers().stream()
                .max(Comparator.comparingDouble(distanceFunction::apply))
                .filter(player -> distanceFunction.apply(player) > 0)
                .orElse(null);
    }

    /**
     * Changes the {@link Player} provider, to change which object to link to him.
     *
     * @param playerProvider the new {@link PlayerProvider}, can be set to null to apply the default provider
     */
    public void setPlayerProvider(@Nullable PlayerProvider playerProvider) {
        this.playerProvider = playerProvider != null ? playerProvider : Player::new;
    }

    /**
     * Creates the player of a connection which acknowledged its login, and claims its profile uuid.
     *
     * @return the player, or null when another live connection already owns that uuid and the
     * caller must reject this one
     */
    @ApiStatus.Internal
    public @Nullable Player createPlayer(PlayerConnection connection, GameProfile gameProfile) {
        assert process.config().insideTest() || Thread.currentThread().isVirtual();
        final Player player = playerProvider.createPlayer(connection, gameProfile);
        return trackNewPlayer(player) ? player : null;
    }

    public void sendRegistryTags(Player player) {
        player.sendPacket(cachedTagsPacket);
    }

    // This is a somewhat weird implementation where connectionmanager owns the caching of tags.
    // There should be no registry->connectionmanager communication.
    @ApiStatus.Internal
    public void invalidateTags() {
        this.cachedTagsPacket.invalidate();
    }

    public GameProfile transitionLoginToConfig(PlayerConnection connection, GameProfile gameProfile) {
        assert process.config().insideTest() || Thread.currentThread().isVirtual();
        // Compression
        if (connection instanceof PlayerSocketConnection socketConnection) {
            final int threshold = process.compressionThreshold();
            if (threshold > 0) socketConnection.startCompression();
        }
        // Call pre login event
        LoginPluginMessageProcessor pluginMessageProcessor = connection.loginPluginMessageProcessor();
        AsyncPlayerPreLoginEvent asyncPlayerPreLoginEvent = new AsyncPlayerPreLoginEvent(connection, gameProfile, pluginMessageProcessor);
        process.eventHandler().call(asyncPlayerPreLoginEvent);
        if (!connection.isOnline()) return gameProfile; // Player has been kicked
        // Change UUID/Username based on the event
        gameProfile = asyncPlayerPreLoginEvent.getGameProfile();
        // Wait for pending login plugin messages
        try {
            pluginMessageProcessor.awaitReplies(process.config().loginPluginMessageTimeout(), TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            connection.kick(LoginListener.INVALID_PROXY_RESPONSE);
            throw new RuntimeException("Error getting replies for login plugin messages", t);
        }
        // Publish the final profile before the client could possibly respond
        if (connection instanceof PlayerSocketConnection socketConnection) {
            socketConnection.UNSAFE_setProfile(gameProfile);
        }
        // Send login success packet (and switch to configuration phase)
        connection.sendPacket(new LoginSuccessPacket(gameProfile, new UUID(0L, 0L)));
        LOGGER.info("Login success: username={} connection_id={} remote_address={}",
                gameProfile.name(), connection.connectionId(), connection.getRemoteAddress());
        return gameProfile;
    }

    @ApiStatus.Internal
    public void transitionPlayToConfig(Player player) {
        configWaitingPlayers.relaxedOffer(player);
    }

    /**
     * Return value exposed for testing
     */
    @ApiStatus.Internal
    public void doConfiguration(Player player, boolean isFirstConfig) {
        assert process.config().insideTest() || Thread.currentThread().isVirtual();
        if (isFirstConfig) {
            final boolean tracked = trackPlayer(player, () -> {
                configurationPlayers.add(player);
                keepAlivePlayers.add(player);
            });
            // The client left between the login acknowledgement and this thread starting
            if (!tracked) return;
        }
        player.sendPacket(PluginMessagePacket.brandPacket(process.brandName()));
        // Request known packs immediately, but don't wait for the response until required (sending registry data).
        final var knownPacksFuture = player.getPlayerConnection().requestKnownPacks(List.of(SelectKnownPacksPacket.MINECRAFT_CORE));

        var event = new AsyncPlayerConfigurationEvent(player, isFirstConfig);
        process.eventHandler().call(event);
        if (!player.isOnline()) return; // Player was kicked during config.

        // send player features that were enabled or disabled during async config event
        player.sendPacket(new UpdateEnabledFeaturesPacket(event.getFeatureFlags().stream().map(StaticProtocolObject::name).toList()));

        final Instance spawningInstance = event.getSpawningInstance();
        Objects.requireNonNull(spawningInstance, "You need to specify a spawning instance in the AsyncPlayerConfigurationEvent");

        if (event.willClearChat()) player.sendPacket(new ResetChatPacket());

        // Registry data (if it should be sent)
        if (event.willSendRegistryData()) {
            List<SelectKnownPacksPacket.Entry> knownPacks;
            try {
                knownPacks = knownPacksFuture.get(process.config().knownPacksResponseTimeout(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException | TimeoutException _) {
                LOGGER.warn("Player failed to respond to known packs query: username={} connection_id={}",
                        player.getUsername(), player.getPlayerConnection().connectionId());
                player.getPlayerConnection().disconnect();
                return;
            } catch (ExecutionException e) {
                throw new RuntimeException("Error receiving known packs", e);
            }
            // The wait is also released by the disconnection, with nothing to answer with
            if (!player.isOnline()) return;
            boolean excludeVanilla = knownPacks.contains(SelectKnownPacksPacket.MINECRAFT_CORE);

            Registries registries = process.registries();
            player.sendPackets(Registries.registryDataPackets(registries, excludeVanilla));
            // TODO: TEST_ENVIRONMENT, TEST_INSTANCE

            sendRegistryTags(player);
        }

        // Wait for pending resource packs if any
        var packFuture = player.getResourcePackFuture();
        if (packFuture != null) packFuture.join();

        keepAlivePlayers.remove(player);
        player.setPendingOptions(spawningInstance, event.isHardcore());
        player.sendPacket(new FinishConfigurationPacket());
    }

    @ApiStatus.Internal
    public void transitionConfigToPlay(Player player) {
        this.playWaitingPlayers.relaxedOffer(player);
    }

    /**
     * Removes a {@link Player} from the players list.
     * <p>
     * Used during disconnection, you shouldn't have to do it manually.
     *
     * @param connection the player connection
     * @see PlayerConnection#disconnect() to properly disconnect a player
     */
    @ApiStatus.Internal
    public synchronized void removePlayer(PlayerConnection connection) {
        final Player player = this.connectionPlayerMap.remove(connection);
        if (player == null) return;
        // Only drop the uuid when it is still this player's, a reconnection may already own it
        this.uuidPlayerMap.remove(player.getUuid(), player);
        this.configurationPlayers.remove(player);
        this.playPlayers.remove(player);
        this.keepAlivePlayers.remove(player);
        // The client is gone and will never answer what #doConfiguration waits for
        player.releaseResourcePackWait();
        connection.releaseKnownPacksWait();
    }

    /**
     * Publishes a freshly created player, unless its profile uuid is already taken.
     * <p>
     * Synchronized against {@link #removePlayer(PlayerConnection)} so that the uuid index and
     * {@link #connectionPlayerMap} always hold the same players: a uuid is owned for exactly as
     * long as its connection is tracked. Two connections carrying the same uuid — which offline
     * mode hands out to anyone reusing a username — therefore cannot both win, whatever their
     * interleaving, and the loser never reaches the entity tracker.
     *
     * @return false if another live connection already owns that uuid
     */
    private synchronized boolean trackNewPlayer(Player player) {
        if (uuidPlayerMap.putIfAbsent(player.getUuid(), player) != null) return false;
        connectionPlayerMap.put(player.getPlayerConnection(), player);
        return true;
    }

    /**
     * Puts a player into the lifecycle sets, unless its connection has already been dropped.
     * <p>
     * Synchronized against {@link #removePlayer(PlayerConnection)} so that a concurrent
     * disconnection can never be overtaken by the insertion: a player added after its own removal
     * stays in the set forever, and with it the connection, the profile and the settings it holds.
     *
     * @return false if the player was not tracked because its connection is gone
     */
    private synchronized boolean trackPlayer(Player player, Runnable insertion) {
        if (!connectionPlayerMap.containsKey(player.getPlayerConnection())) return false;
        insertion.run();
        return true;
    }

    /**
     * Shutdowns the connection manager by kicking all the currently connected players.
     */
    public synchronized void shutdown() {
        final List<PlayerSocketConnection> kicked = new ArrayList<>();
        for (final PlayerConnection configPlayer : connectionPlayerMap.keySet()) {
            configPlayer.kick(DisconnectReasons.SERVER_STOPPING);
            if (configPlayer instanceof PlayerSocketConnection socketConnection) kicked.add(socketConnection);
        }
        this.configurationPlayers.clear();
        for (final Player playPlayer : playPlayers)
            playPlayer.kick(DisconnectReasons.SERVER_STOPPING);
        this.playPlayers.clear();

        this.keepAlivePlayers.clear();
        this.connectionPlayerMap.clear();
        this.uuidPlayerMap.clear();

        // A disconnect packet is written by the connection own write thread. Without waiting for
        // those threads the process exits first and the client only sees a closed socket.
        final long deadline = System.currentTimeMillis() + DISCONNECT_FLUSH_TIMEOUT;
        for (PlayerSocketConnection connection : kicked) {
            final long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            try {
                connection.writeThread().join(remaining);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void tick(long tickStart) {
        // Let waiting players into their instances
        updateWaitingPlayers();

        // Send keep alive packets
        handleKeepAlive(keepAlivePlayers, tickStart);

        // Interpret packets for configuration players
        configurationPlayers.forEach(Player::interpretPacketQueue);
    }

    /**
     * Connects waiting players.
     */
    @ApiStatus.Internal
    public void updateWaitingPlayers() {
        this.configWaitingPlayers.drain(player -> {
            // In case the method was called multiple times, the player disconnected, etc. just ignore it.
            if (!playPlayers.remove(player)) return;

            if (!trackPlayer(player, () -> configurationPlayers.add(player))) return;
            player.remove(false);
            player.sendPacket(new StartConfigurationPacket());
        });
        this.playWaitingPlayers.drain(player -> {
            if (!player.isOnline()) return; // Player disconnected while in queued to join
            if (!trackPlayer(player, () -> {
                configurationPlayers.remove(player);
                playPlayers.add(player);
                keepAlivePlayers.add(player);
            })) return;

            // This fixes a bug with Geyser. They do not reply to keep alive during config, meaning that
            // `Player#didAnswerKeepAlive()` will always be false when entering the play state, so a new keep
            // alive will never be sent and they will disconnect themselves or we will kick them for not replying.
            player.refreshAnswerKeepAlive(true);

            // Spawn the player at Player#getRespawnPoint
            CompletableFuture<Void> spawnFuture = player.UNSAFE_init();
            // A failed spawn (e.g. the spawn chunks could not be loaded, or the entity tracker
            // refused the player) would otherwise leave the client waiting on the join screen
            // forever, in a world its entity is not tracked in.
            var _ = spawnFuture.exceptionally(throwable -> {
                LOGGER.error("Failed to spawn player {}", player.getUsername(), throwable);
                player.kick(DisconnectReasons.INTERNAL_ERROR);
                return null;
            });

            // Required to get the exact moment the player spawns. Only this future can be joined,
            // Player#setInstance overrides join to run the pending chunk work of this very thread.
            if (process.config().insideTest()) {
                try {
                    spawnFuture.join();
                } catch (CompletionException _) {
                    // Already reported and disconnected above, the remaining players still join
                }
            }
        });
    }

    /**
     * Updates keep alive by checking the last keep alive packet and send a new one if needed.
     *
     * @param tickStart the time of the update in nanoseconds, forwarded to the packet
     */
    private void handleKeepAlive(Collection<Player> playerGroup, long tickStart) {
        final KeepAlivePacket keepAlivePacket = new KeepAlivePacket(tickStart);
        for (Player player : playerGroup) {
            final long lastKeepAlive = tickStart - player.getLastKeepAlive();
            if (lastKeepAlive > TimeUnit.MILLISECONDS.toNanos(process.config().keepAliveDelay()) && player.didAnswerKeepAlive()) {
                player.refreshKeepAlive(tickStart);
                player.sendPacket(keepAlivePacket);
            } else if (lastKeepAlive >= TimeUnit.MILLISECONDS.toNanos(process.config().keepAliveKick())) {
                player.kick(TIMEOUT_TEXT);
            }
        }
    }

}
