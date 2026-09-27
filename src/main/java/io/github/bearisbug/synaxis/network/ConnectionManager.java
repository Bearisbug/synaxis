package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.crypto.LoginCrypt;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerConfigurationEvent;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.monitoring.EventsJFR;
import io.github.bearisbug.synaxis.network.packet.server.CachedPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.KeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.ResetChatPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.UpdateEnabledFeaturesPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerInfoUpdatePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.StartConfigurationPacket;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.InboundPacketLimits;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import io.github.bearisbug.synaxis.network.player.SessionClient;
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

import java.nio.file.Path;
import java.security.KeyPair;
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
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Manages the connected clients.
 */
public final class ConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionManager.class);

    private static final long DISCONNECT_FLUSH_TIMEOUT = 1_000;

    /**
     * How long an online mode login waits for the session it replaces to leave the world
     * ({@code docs/DESIGN-PROD.md} §11.1), and how often it looks.
     */
    private static final long SESSION_HANDOVER_TIMEOUT = TimeUnit.SECONDS.toNanos(5);
    private static final long SESSION_HANDOVER_POLL_MILLIS = 10;

    private final ServerProcess process;
    private final CachedPacket cachedTagsPacket;
    private final ConnectionGate gate;
    private final @Nullable KeyPair loginKeyPair;
    private final @Nullable SessionClient sessionClient;
    private final TrafficCounters traffic = new TrafficCounters();
    private final InboundPacketLimits inboundLimits;
    private final @Nullable Favicon serverIcon;

    /**
     * Creates the connection manager of a process.
     *
     * @param process the process
     * @throws IllegalArgumentException if {@code synaxis.server-icon} names a file that is not a valid icon
     */
    public ConnectionManager(ServerProcess process) {
        this.process = process;
        this.serverIcon = serverIcon(process.config().serverIcon());
        this.inboundLimits = InboundPacketLimits.of(process.config());
        this.cachedTagsPacket = new CachedPacket(() -> Registries.tagsPacket(process.registries()));
        this.gate = new ConnectionGate(process.config());
        switch (process.auth()) {
            case Auth.Online online -> {
                this.loginKeyPair = online.keyPair();
                this.sessionClient = new SessionClient(online.sessionServer());
            }
            case Auth.Offline offline -> {
                this.loginKeyPair = offline.encrypt() ? LoginCrypt.generateKeyPair() : null;
                this.sessionClient = null;
            }
        }
    }

    /**
     * The connection gate every socket connection of this process goes through, see
     * {@code docs/DESIGN-PROD.md} {@code API-PROD-003}.
     */
    @ApiStatus.Internal
    public ConnectionGate gate() {
        return gate;
    }

    /**
     * The RSA key pair logins are encrypted with, null when the mode does not encrypt
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-002}).
     */
    @ApiStatus.Internal
    public @Nullable KeyPair loginKeyPair() {
        return loginKeyPair;
    }

    /** The session server client of an online mode process, null in every other mode. */
    @ApiStatus.Internal
    public @Nullable SessionClient sessionClient() {
        return sessionClient;
    }

    /**
     * What the socket connections of this process moved, see {@code docs/DESIGN-PROD.md}
     * {@code API-PROD-015}.
     */
    @ApiStatus.Internal
    public TrafficCounters traffic() {
        return traffic;
    }

    /**
     * The inbound packet limits every socket connection of this process counts against, see
     * {@code docs/DESIGN-PROD.md} {@code API-PROD-012}.
     */
    @ApiStatus.Internal
    public InboundPacketLimits inboundLimits() {
        return inboundLimits;
    }

    /**
     * The server list icon of {@code synaxis.server-icon}, read once when the process is created,
     * null when none is configured ({@code docs/DESIGN-PROD.md} {@code API-PROD-024}).
     */
    @ApiStatus.Internal
    public @Nullable Favicon serverIcon() {
        return serverIcon;
    }

    private static @Nullable Favicon serverIcon(String path) {
        if (path.isEmpty()) return null;
        try {
            return Favicon.read(Path.of(path));
        } catch (IllegalArgumentException e) {
            // A misconfigured icon refuses the start rather than going unnoticed in the server list
            throw new IllegalArgumentException("synaxis.server-icon is not a valid icon: " + e.getMessage(), e);
        }
    }

    // All players once their Player object has been instantiated.
    private final Map<PlayerConnection, Player> connectionPlayerMap = new ConcurrentHashMap<>();
    // The same players keyed by profile uuid, which owns the uuid until its connection is dropped
    private final Map<UUID, Player> uuidPlayerMap = new ConcurrentHashMap<>();
    // Set by #shutdown under this object's lock, which #trackOrPrevious holds too
    private boolean shutDown;
    // Players waiting to be spawned (post configuration state)
    private final MessagePassingQueue<Player> playWaitingPlayers = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    // Players waiting to be (re) configured
    private final MessagePassingQueue<Player> configWaitingPlayers = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    // Players that disconnected in the play state, possibly before they spawned
    private final MessagePassingQueue<Player> leftPlayers = ConcurrentMessageQueues.mpscUnboundedArrayQueue(64);
    // Players in configuration state
    private final Set<Player> configurationPlayers = new CopyOnWriteArraySet<>();
    // Players in play state
    private final Set<Player> playPlayers = new CopyOnWriteArraySet<>();
    // Players whose spawn has started and not completed: in no instance yet, but about to be
    private final Set<Player> spawningPlayers = ConcurrentHashMap.newKeySet();

    // The players who need keep alive ticks. This was added because we may not send a keep alive in
    // the time after sending finish configuration but before receiving configuration end (to swap to play).
    // I(mattw) could not come up with a better way to express this besides completely splitting client/server
    // states. Perhaps there will be an improvement in the future.
    private final Set<Player> keepAlivePlayers = new CopyOnWriteArraySet<>();

    private final Set<Player> unmodifiableConfigurationPlayers = Collections.unmodifiableSet(configurationPlayers);
    private final Set<Player> unmodifiablePlayPlayers = Collections.unmodifiableSet(playPlayers);

    // The player provider to have your own Player implementation
    private volatile PlayerProvider playerProvider = Player::new;

    // Who lists whom in the tab list, null for everyone (docs/DESIGN-PROD.md API-PROD-020)
    private volatile @Nullable BiPredicate<Player, Player> tabListVisibility;
    // When the tab list latencies go out next, tick scheduler thread only
    private long nextTabListLatencies;
    private boolean tabListLatenciesScheduled;

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
     * Tells whether a connection id still belongs to a live connection, in the configuration state
     * as well as in the play state.
     * <p>
     * It stops being true before {@link io.github.bearisbug.synaxis.event.player.PlayerDisconnectEvent}
     * is called — {@link PlayerConnection#disconnect()} drops the connection first and calls the
     * event afterwards — so a listener of that event and a concurrent insertion keyed by the same
     * connection id can agree on which of the two happened last.
     *
     * @param connectionId the connection id, the one that appears in the server logs
     * @return true while the process still holds that connection and it has not been dropped
     */
    public boolean isConnected(UUID connectionId) {
        for (final PlayerConnection connection : connectionPlayerMap.keySet())
            if (connection.connectionId().equals(connectionId)) return connection.isOnline();
        return false;
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
     * @return the player, or null when another live connection already owns that uuid or the
     * manager has shut down ({@link #isShutDown()}), and the caller must reject this one
     */
    @ApiStatus.Internal
    public @Nullable Player createPlayer(PlayerConnection connection, GameProfile gameProfile) {
        assert process.config().insideTest() || Thread.currentThread().isVirtual();
        final Player player = playerProvider.createPlayer(connection, gameProfile);
        return trackNewPlayer(player) ? player : null;
    }

    /**
     * The online mode counterpart of {@link #createPlayer}: the uuid is Mojang's, so a live session
     * holding it is the same account logging in again. That session is kicked with
     * {@link DisconnectReasons#LOGGED_IN_ELSEWHERE}, and this call waits until its player has left
     * the world, because the entity tracker refuses a second entity with the same uuid and the old
     * one is only removed on the next tick ({@code docs/DESIGN-PROD.md} §11.1, §16). Runs on the
     * read thread of the new connection.
     *
     * @return the player, or null when the uuid was still not free after 5 seconds or the manager
     * has shut down ({@link #isShutDown()})
     */
    @ApiStatus.Internal
    public @Nullable Player createPlayerReplacing(PlayerConnection connection, GameProfile gameProfile) {
        assert process.config().insideTest() || Thread.currentThread().isVirtual();
        final Player player = playerProvider.createPlayer(connection, gameProfile);
        final long deadline = System.nanoTime() + SESSION_HANDOVER_TIMEOUT;
        while (true) {
            final Player previous = trackOrPrevious(player);
            if (previous == null) return player;
            if (previous == player) return null;
            previous.kick(DisconnectReasons.LOGGED_IN_ELSEWHERE);
            while (uuidPlayerMap.get(player.getUuid()) == previous || !leftTheWorld(previous)) {
                if (System.nanoTime() - deadline >= 0 || !connection.isOnline()) return null;
                try {
                    Thread.sleep(SESSION_HANDOVER_POLL_MILLIS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            // Another login of the same account may have taken the uuid in the meantime, the loop
            // replaces that one too until the deadline
            if (System.nanoTime() - deadline >= 0) return null;
        }
    }

    /**
     * Whether a player whose connection has been dropped is out of the world for good. A player
     * whose spawn is under way is in no instance yet, but enters the entity tracker once its spawn
     * chunks are loaded, and only leaves it with the removal after that. The rest is read under the
     * player's lock, the one {@code Entity#remove} holds while it writes both.
     */
    private boolean leftTheWorld(Player player) {
        // A dropped connection cannot start a spawn anymore (see the join in #updateWaitingPlayers),
        // so once the player is not spawning, its instance tells whether it is in the world
        if (spawningPlayers.contains(player)) return false;
        synchronized (player) {
            return player.isRemoved() || player.getInstance() == null;
        }
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
     * Reports a player that disconnected in the play state on the next tick, unless it has started
     * to spawn: the state is play once the server wrote {@code FinishConfigurationPacket}, but the
     * player only spawns on a later tick, and the removal its disconnect scheduled on the entity
     * never runs for an entity that is in no instance.
     *
     * @param player the player whose connection was dropped
     */
    @ApiStatus.Internal
    public void leftBeforeSpawn(Player player) {
        leftPlayers.relaxedOffer(player);
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
     * @return false if another live connection already owns that uuid, or the manager has shut down
     */
    private synchronized boolean trackNewPlayer(Player player) {
        return trackOrPrevious(player) == null;
    }

    /**
     * {@link #trackNewPlayer}, returning the player owning the uuid instead when it is taken, and
     * the player itself once the manager has shut down: {@link #shutdown} kicked every player it
     * held, and one tracked after that would go through its configuration in a stopping process
     * with nothing left to kick it.
     */
    private synchronized @Nullable Player trackOrPrevious(Player player) {
        if (shutDown) return player;
        final Player previous = uuidPlayerMap.putIfAbsent(player.getUuid(), player);
        if (previous != null) return previous;
        connectionPlayerMap.put(player.getPlayerConnection(), player);
        return null;
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
     * Whether {@link #shutdown()} has run: from then on no player is created, a connection that
     * acknowledges its login later is refused its player.
     *
     * @return true once the manager has shut down
     */
    @ApiStatus.Internal
    public synchronized boolean isShutDown() {
        return shutDown;
    }

    /**
     * Shutdowns the connection manager by kicking all the currently connected players.
     */
    public synchronized void shutdown() {
        this.shutDown = true;
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
        this.spawningPlayers.clear();
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

        // The latencies the keep alives measured, all players in one packet
        final long interval = TimeUnit.MILLISECONDS.toNanos(process.config().tabLatencyInterval());
        if (!tabListLatenciesScheduled) {
            this.nextTabListLatencies = tickStart + interval;
            this.tabListLatenciesScheduled = true;
        } else if (tickStart - nextTabListLatencies >= 0) {
            this.nextTabListLatencies = tickStart + interval;
            sendTabListLatencies();
        }

        gate.tick(tickStart);
        if (sessionClient != null) sessionClient.tick(tickStart);

        // Interpret packets for configuration players
        configurationPlayers.forEach(Player::interpretPacketQueue);
    }

    /**
     * Sends the latency of every player in the play state to every one of them, one packet in all.
     * The tick does it every {@code synaxis.tab-latency-interval-ms}; a player's own keep alive only
     * stores its latency ({@code docs/DESIGN-PROD.md} {@code API-PROD-020}).
     */
    @ApiStatus.Internal
    public void sendTabListLatencies() {
        final List<PlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
        for (Player player : playPlayers) entries.add(player.tabListEntry(player.isListed()));
        if (entries.isEmpty()) return;
        PacketSendingUtils.sendGroupedPacket(playPlayers,
                new PlayerInfoUpdatePacket(PlayerInfoUpdatePacket.Action.UPDATE_LATENCY, entries));
    }

    /**
     * Decides who lists whom in the tab list ({@code docs/DESIGN-PROD.md} {@code API-PROD-020}). A
     * player that is not listed for a viewer is still sent to it, with {@code listed=false}: the
     * client needs the entry to render the player's skin. Every online player gets the listing of
     * every other one again, one packet per viewer.
     *
     * @param visibility {@code (viewer, subject) -> listed}, called from the thread that sends the
     *                   entry; null lists everyone who is {@linkplain Player#isListed() listed}
     */
    public void setTabListVisibility(@Nullable BiPredicate<Player, Player> visibility) {
        this.tabListVisibility = visibility;
        for (Player viewer : playPlayers) {
            final List<PlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
            for (Player subject : playPlayers) entries.add(subject.tabListEntry(isListedFor(viewer, subject)));
            viewer.sendPacket(new PlayerInfoUpdatePacket(PlayerInfoUpdatePacket.Action.UPDATE_LISTED, entries));
        }
    }

    /**
     * Whether a viewer's tab list shows a player: the player is {@linkplain Player#isListed() listed}
     * and, unless it is the viewer itself, the visibility of {@link #setTabListVisibility} lets it.
     *
     * @param viewer  whose tab list
     * @param subject the player in it
     * @return the {@code listed} flag of the entry the viewer gets
     */
    public boolean isListedFor(Player viewer, Player subject) {
        if (!subject.isListed()) return false;
        if (viewer == subject) return true;
        final BiPredicate<Player, Player> visibility = this.tabListVisibility;
        return visibility == null || visibility.test(viewer, subject);
    }

    /**
     * Connects waiting players, at most {@code synaxis.max-joins-per-tick} of them when it is not 0.
     * The others stay in the queue for the next tick.
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
        final MessagePassingQueue.Consumer<Player> join = player -> {
            if (!player.isOnline()) return; // Player disconnected while in queued to join
            if (!trackPlayer(player, () -> {
                configurationPlayers.remove(player);
                playPlayers.add(player);
                keepAlivePlayers.add(player);
                // Under the same lock as #removePlayer: a connection dropped after this point finds
                // the player spawning, one dropped before it never gets here
                spawningPlayers.add(player);
            })) return;

            // This fixes a bug with Geyser. They do not reply to keep alive during config, meaning that
            // `Player#didAnswerKeepAlive()` will always be false when entering the play state, so a new keep
            // alive will never be sent and they will disconnect themselves or we will kick them for not replying.
            player.refreshAnswerKeepAlive(true);

            // Spawn the player at Player#getRespawnPoint
            final CompletableFuture<Void> spawnFuture;
            try {
                spawnFuture = player.UNSAFE_init();
            } catch (RuntimeException e) {
                spawningPlayers.remove(player);
                throw e;
            }
            var _ = spawnFuture.whenComplete((_, _) -> spawningPlayers.remove(player));
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
        };
        final int maxJoins = process.config().maxJoinsPerTick();
        if (maxJoins == 0) this.playWaitingPlayers.drain(join);
        else this.playWaitingPlayers.drain(join, maxJoins);
        // After the joins, on the same thread: a player that got past the online check above has
        // started to spawn, and its entity removal reports it
        this.leftPlayers.drain(player -> {
            if (!player.dropPendingSpawn()) return;
            process.eventHandler().call(new PlayerDisconnectEvent(player));
            EventsJFR.newPlayerLeave(player.getUuid()).commit();
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
                player.kick(DisconnectReasons.KEEP_ALIVE_TIMEOUT);
            }
        }
    }

}
