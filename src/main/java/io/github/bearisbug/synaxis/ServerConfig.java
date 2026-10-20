package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.network.player.InboundPacketLimits;
import io.github.bearisbug.synaxis.network.player.PacketLimitAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Function;

/**
 * Per-process server settings, reachable through {@link ServerProcess#config()}.
 *
 * <p>Every value has one default, shared by production and tests: a process created without an
 * explicit configuration uses {@link #fromSystemProperties()}, so launch arguments still work,
 * while tests build their own instance and never touch the JVM properties.</p>
 *
 * <p>Switches that are read from static initializers or from objects with no process to ask live
 * in {@link JvmFlags} instead.</p>
 */
public record ServerConfig(
        // Server behavior
        boolean shutdownOnSignal,
        int maxTickCatchUp,
        int chunkViewDistance,
        int entityViewDistance,
        int entitySynchronizationTicks,
        int dispatcherThreads,
        // How the chunks are spread over the dispatcher threads (docs/DESIGN-PROD.md API-PROD-016)
        ThreadProviderKind threadProvider,
        int threadGridExponent,
        int sendLightAfterBlockPlacementDelay,
        long loginTimeout,
        long loginPluginMessageTimeout,
        long knownPacksResponseTimeout,
        boolean acceptTransfers,

        // Network rate limiting
        int playerPacketPerTick,
        int playerPacketQueueSize,
        long keepAliveDelay,
        long keepAliveKick,
        int playerChunkUpdateLimiterHistorySize,

        // Connection gate (docs/DESIGN-PROD.md API-PROD-003), durations in milliseconds
        int maxConnections,
        long acceptBackoff,
        int maxConnectionsPerIp,
        long handshakeTimeout,
        double loginRatePerIp,
        int loginBurstPerIp,
        double statusRatePerIp,
        int statusBurstPerIp,
        double transferRatePerIp,
        int transferBurstPerIp,
        String connectionGateTrustedNetworks,
        int maxPlayers,
        int maxJoinsPerTick,

        // Server list (docs/DESIGN-PROD.md API-PROD-024), the icon is a path, empty for none
        String motd,
        String serverIcon,
        boolean hideOnlinePlayers,

        // Chat (docs/DESIGN-PROD.md API-PROD-008): a token bucket per connection
        int chatRatePerSecond,
        int chatBurst,

        // Inbound packet limits (docs/DESIGN-PROD.md API-PROD-012), the window in milliseconds
        long packetLimitWindow,
        int packetLimitMaxPackets,
        PacketLimitAction packetLimitAction,
        String packetLimitOverrides,
        long maxInflatedBytesPerSecond,
        int tabCompleteRatePerSecond,

        // Outbound (docs/DESIGN-PROD.md API-PROD-020), the tab list interval in milliseconds
        long maxOutboundBacklogBytes,
        long tabLatencyInterval,
        // A packet body of at least this many bytes is compressed, 0 turns compression off (API-PROD-020)
        int compressionThreshold,

        // Network error handling
        boolean suppressConnectionAcceptErrors,
        boolean suppressConnectionIoErrors,
        int suppressMalformedPacketErrorLevel,
        int suppressMisusedPacketErrorLevel,
        boolean rejectMalformedPacket,
        boolean rejectMisusedPacket,

        // Network buffers
        int socketSendBufferSize,
        int socketReceiveBufferSize,
        boolean socketNoDelay,
        int socketTimeout,
        int pooledBufferSize,

        // Chunk update
        float minChunksPerTick,
        float maxChunksPerTick,
        float chunksPerTickMultiplier,

        // Packet sending optimizations
        boolean viewablePacket,

        // Entities
        boolean enforceInteractionLimit,
        // docs/DESIGN-PROD.md API-PROD-019, 0 means no limit
        int maxVisibleEntitiesPerPlayer,

        // Tick watchdog (docs/DESIGN-PROD.md API-PROD-013), in milliseconds, 0 turns the stage off
        long watchdogEarlyWarning,
        long watchdogEarlyWarningEvery,
        long watchdogTimeout,
        long watchdogHaltAfter,

        // Experimental/unstable
        boolean registryUnsafeOps,
        boolean insideTest,
        boolean fasterSocketWrites,
        boolean proxyProtocol,
        // Only the confirmation that proxyProtocol is meant: on its own, true changes nothing
        boolean proxyProtocolRequired,
        String proxyProtocolTrustedNetworks
) {
    /** Initial size of the pooled network buffers, also used by the buffer pools shared by every process. */
    public static final int DEFAULT_POOLED_BUFFER_SIZE = 16_383;

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfig.class);

    private static final String PROXY_PROTOCOL_TRUSTED_NETWORKS = "synaxis.proxy-protocol.trusted-networks";
    // The keys of the values the constructor checks, which its messages start with
    private static final String DISPATCHER_THREADS = "synaxis.dispatcher-threads";
    private static final String THREAD_GRID_EXPONENT = "synaxis.thread-grid-exponent";
    private static final String CHUNK_VIEW_DISTANCE = "synaxis.chunk-view-distance";
    private static final String ENTITY_VIEW_DISTANCE = "synaxis.entity-view-distance";
    private static final String MAX_TICK_CATCH_UP = "synaxis.max-tick-catch-up";
    private static final String CHUNK_UPDATE_LIMITER_HISTORY_SIZE = "synaxis.player.chunk-update-limiter-history-size";
    private static final String SEND_BUFFER_SIZE = "synaxis.send-buffer-size";
    private static final String RECEIVE_BUFFER_SIZE = "synaxis.receive-buffer-size";
    private static final String POOLED_BUFFER_SIZE = "synaxis.pooled-buffer-size";
    private static final String CHAT_RATE_PER_SECOND = "synaxis.chat-rate-per-second";
    private static final String CHAT_BURST = "synaxis.chat-burst";
    private static final String WATCHDOG_EARLY_WARNING = "synaxis.watchdog.early-warning-ms";
    private static final String WATCHDOG_EARLY_WARNING_EVERY = "synaxis.watchdog.early-warning-every-ms";
    private static final String WATCHDOG_TIMEOUT = "synaxis.watchdog.timeout-ms";
    private static final String WATCHDOG_HALT_AFTER = "synaxis.watchdog.halt-after-ms";
    private static final String MAX_CONNECTIONS = "synaxis.max-connections";
    private static final String MAX_CONNECTIONS_PER_IP = "synaxis.max-connections-per-ip";
    private static final String HANDSHAKE_TIMEOUT = "synaxis.handshake-timeout-ms";
    private static final String MAX_VISIBLE_ENTITIES_PER_PLAYER = "synaxis.max-visible-entities-per-player";
    private static final String ACCEPT_BACKOFF = "synaxis.accept-backoff-ms";
    private static final String MAX_PLAYERS = "synaxis.max-players";
    private static final String MAX_JOINS_PER_TICK = "synaxis.max-joins-per-tick";
    private static final String LOGIN_RATE_PER_IP = "synaxis.login-rate-per-ip";
    private static final String LOGIN_BURST_PER_IP = "synaxis.login-burst-per-ip";
    private static final String STATUS_RATE_PER_IP = "synaxis.status-rate-per-ip";
    private static final String STATUS_BURST_PER_IP = "synaxis.status-burst-per-ip";
    private static final String TRANSFER_RATE_PER_IP = "synaxis.transfer-rate-per-ip";
    private static final String TRANSFER_BURST_PER_IP = "synaxis.transfer-burst-per-ip";
    private static final String PACKET_LIMIT_WINDOW = "synaxis.packet-limit.window-ms";
    private static final String PACKET_LIMIT_MAX_PACKETS = "synaxis.packet-limit.max-packets";
    private static final String MAX_INFLATED_BYTES_PER_SECOND = "synaxis.packet-limit.max-inflated-bytes-per-second";
    private static final String TAB_COMPLETE_RATE_PER_SECOND = "synaxis.tab-complete-rate-per-second";
    private static final String MAX_OUTBOUND_BACKLOG_BYTES = "synaxis.outbound.max-backlog-bytes";
    private static final String TAB_LATENCY_INTERVAL = "synaxis.tab-latency-interval-ms";
    private static final String COMPRESSION_THRESHOLD = "synaxis.compression-threshold";

    /**
     * A {@code SO_TIMEOUT}, which the channel reads of the engine never honour: idle connections
     * are bounded by {@link #loginTimeout()} and {@link #keepAliveKick()}.
     *
     * @deprecated has no effect, removed in the release after the one that deprecated it
     * ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-043})
     */
    @Deprecated
    @Override
    public int socketTimeout() {
        return socketTimeout;
    }

    /**
     * The {@code ThreadProvider} the dispatcher of a process is built with, {@code synaxis.thread-provider}.
     */
    public enum ThreadProviderKind {
        /** Every new chunk goes to the next thread, the engine default. */
        COUNTER,
        /** The chunks of one cell of the grid share a thread, for the shards of the MMO. */
        GRID
    }

    public ServerConfig {
        atLeast(DISPATCHER_THREADS, dispatcherThreads, 1);
        Objects.requireNonNull(threadProvider, "threadProvider");
        if (threadGridExponent < 0 || threadGridExponent > 31)
            throw new IllegalArgumentException(THREAD_GRID_EXPONENT + " must be between 0 and 31 but was " + threadGridExponent);
        atLeast(CHUNK_UPDATE_LIMITER_HISTORY_SIZE, playerChunkUpdateLimiterHistorySize, 0);
        atLeast(CHUNK_VIEW_DISTANCE, chunkViewDistance, 0);
        atLeast(ENTITY_VIEW_DISTANCE, entityViewDistance, 0);
        atLeast(MAX_TICK_CATCH_UP, maxTickCatchUp, 0);
        if (entitySynchronizationTicks < 1)
            throw new IllegalArgumentException("synaxis.entity-synchronization-ticks must be at least 1 but was "
                    + entitySynchronizationTicks);
        if (loginTimeout < 1)
            throw new IllegalArgumentException("synaxis.login-timeout must be at least 1 ms but was " + loginTimeout);
        if (keepAliveDelay < 1)
            throw new IllegalArgumentException("synaxis.keep-alive-delay must be at least 1 ms but was " + keepAliveDelay);
        if (keepAliveKick <= keepAliveDelay)
            throw new IllegalArgumentException("synaxis.keep-alive-kick (" + keepAliveKick + " ms) must be greater than "
                    + "synaxis.keep-alive-delay (" + keepAliveDelay + " ms): it counts from the last keep-alive sent, "
                    + "a smaller value disconnects every player");
        if (playerPacketPerTick < 1)
            throw new IllegalArgumentException("synaxis.packet-per-tick must be at least 1 but was " + playerPacketPerTick);
        if (playerPacketQueueSize < Math.max(2, playerPacketPerTick))
            throw new IllegalArgumentException("synaxis.packet-queue-size must be at least 2 and at least "
                    + "synaxis.packet-per-tick (" + playerPacketPerTick + ") but was " + playerPacketQueueSize);
        atLeast(SEND_BUFFER_SIZE, socketSendBufferSize, 1);
        atLeast(RECEIVE_BUFFER_SIZE, socketReceiveBufferSize, 1);
        atLeast(POOLED_BUFFER_SIZE, pooledBufferSize, 1);
        if (!(minChunksPerTick <= maxChunksPerTick))
            throw new IllegalArgumentException("synaxis.chunk-queue.min-per-tick (" + minChunksPerTick
                    + ") must not be above synaxis.chunk-queue.max-per-tick (" + maxChunksPerTick + ")");
        Objects.requireNonNull(motd, "motd");
        Objects.requireNonNull(serverIcon, "serverIcon");
        Objects.requireNonNull(connectionGateTrustedNetworks, "connectionGateTrustedNetworks");
        Objects.requireNonNull(packetLimitOverrides, "packetLimitOverrides");
        Objects.requireNonNull(proxyProtocolTrustedNetworks, "proxyProtocolTrustedNetworks");
        atLeast(CHAT_RATE_PER_SECOND, chatRatePerSecond, 1);
        atLeast(CHAT_BURST, chatBurst, 1);
        atLeast(WATCHDOG_EARLY_WARNING, watchdogEarlyWarning, 0);
        atLeast(WATCHDOG_EARLY_WARNING_EVERY, watchdogEarlyWarningEvery, 0);
        atLeast(WATCHDOG_TIMEOUT, watchdogTimeout, 0);
        atLeast(WATCHDOG_HALT_AFTER, watchdogHaltAfter, 0);
        // The watchdog looks once a second: a shorter timeout judges every tick that takes it dead
        if (watchdogTimeout != 0 && watchdogTimeout < 1_000)
            throw new IllegalArgumentException("synaxis.watchdog.timeout-ms must be 0 (off) or at least 1000 ms, the "
                    + "interval the watchdog looks at the tick, but was " + watchdogTimeout);
        atLeast(MAX_CONNECTIONS, maxConnections, 1);
        atLeast(MAX_CONNECTIONS_PER_IP, maxConnectionsPerIp, 1);
        atLeast(HANDSHAKE_TIMEOUT, handshakeTimeout, 1);
        atLeast(MAX_VISIBLE_ENTITIES_PER_PLAYER, maxVisibleEntitiesPerPlayer, 0);
        atLeast(ACCEPT_BACKOFF, acceptBackoff, 0);
        atLeast(MAX_PLAYERS, maxPlayers, 0);
        atLeast(MAX_JOINS_PER_TICK, maxJoinsPerTick, 0);
        positive(LOGIN_RATE_PER_IP, loginRatePerIp);
        atLeast(LOGIN_BURST_PER_IP, loginBurstPerIp, 1);
        positive(STATUS_RATE_PER_IP, statusRatePerIp);
        atLeast(STATUS_BURST_PER_IP, statusBurstPerIp, 1);
        positive(TRANSFER_RATE_PER_IP, transferRatePerIp);
        atLeast(TRANSFER_BURST_PER_IP, transferBurstPerIp, 1);
        atLeast(PACKET_LIMIT_WINDOW, packetLimitWindow, 1);
        atLeast(PACKET_LIMIT_MAX_PACKETS, packetLimitMaxPackets, 1);
        atLeast(MAX_INFLATED_BYTES_PER_SECOND, maxInflatedBytesPerSecond, 1);
        atLeast(TAB_COMPLETE_RATE_PER_SECOND, tabCompleteRatePerSecond, 1);
        Objects.requireNonNull(packetLimitAction, "packetLimitAction");
        var _ = InboundPacketLimits.parseOverrides(packetLimitOverrides);
        atLeast(MAX_OUTBOUND_BACKLOG_BYTES, maxOutboundBacklogBytes, 1);
        atLeast(TAB_LATENCY_INTERVAL, tabLatencyInterval, 1);
        if (compressionThreshold < 0)
            throw new IllegalArgumentException(COMPRESSION_THRESHOLD + " must not be negative but was " + compressionThreshold
                    + ", 0 turns compression off");
        var _ = ConnectionGate.parseNetworks(connectionGateTrustedNetworks);
        // A PROXY header names any address its sender likes, so only the load balancers may send
        // one, and a peer that skips it must not pass as a direct connection (REQ-PROD-036)
        final boolean noTrustedProxies = ConnectionGate.parseNetworks(PROXY_PROTOCOL_TRUSTED_NETWORKS,
                proxyProtocolTrustedNetworks).isEmpty();
        if (proxyProtocol && noTrustedProxies)
            throw new IllegalArgumentException("synaxis.proxy-protocol needs " + PROXY_PROTOCOL_TRUSTED_NETWORKS
                    + ", the networks of the load balancers allowed to send a PROXY header");
        if (proxyProtocol && !proxyProtocolRequired)
            throw new IllegalArgumentException("synaxis.proxy-protocol needs synaxis.proxy-protocol.required=true, "
                    + "a connection without the header would otherwise pass as a direct one");
    }

    /** Refuses a value below {@code min}, in a message that starts with the key of the value. */
    private static void atLeast(String key, long value, long min) {
        if (value < min) throw new IllegalArgumentException(key
                + (min == 0 ? " must not be negative" : " must be at least " + min) + " but was " + value);
    }

    /** Refuses a value that is not above 0, NaN included, in a message that starts with the key of the value. */
    private static void positive(String key, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(key + " must be positive but was " + value);
    }

    /**
     * The engine defaults, ignoring the JVM system properties.
     */
    public static ServerConfig defaults() {
        return builder().build();
    }

    /**
     * The engine defaults with every {@code synaxis.*} system property applied on top.
     */
    public static ServerConfig fromSystemProperties() {
        if (System.getProperty("synaxis.socket-timeout") != null)
            LOGGER.warn("synaxis.socket-timeout is deprecated and has no effect, the channel reads never honour it; "
                    + "idle connections are bounded by synaxis.login-timeout and synaxis.keep-alive-kick. "
                    + "It is removed in the next release");
        if (System.getProperty("synaxis.event.multiple-parents") != null)
            LOGGER.warn("synaxis.event.multiple-parents is deprecated and has no effect, an event node has at most one parent");
        return builder()
                .shutdownOnSignal(booleanProperty("synaxis.shutdown-on-signal", DEFAULTS.shutdownOnSignal))
                .maxTickCatchUp(intProperty(MAX_TICK_CATCH_UP, DEFAULTS.maxTickCatchUp))
                .chunkViewDistance(intProperty(CHUNK_VIEW_DISTANCE, DEFAULTS.chunkViewDistance))
                .entityViewDistance(intProperty(ENTITY_VIEW_DISTANCE, DEFAULTS.entityViewDistance))
                .entitySynchronizationTicks(intProperty("synaxis.entity-synchronization-ticks", DEFAULTS.entitySynchronizationTicks))
                .dispatcherThreads(intProperty(DISPATCHER_THREADS, DEFAULTS.dispatcherThreads))
                .threadProvider(threadProviderProperty("synaxis.thread-provider", DEFAULTS.threadProvider))
                .threadGridExponent(intProperty(THREAD_GRID_EXPONENT, DEFAULTS.threadGridExponent))
                .sendLightAfterBlockPlacementDelay(intProperty("synaxis.send-light-after-block-placement-delay", DEFAULTS.sendLightAfterBlockPlacementDelay))
                .loginTimeout(longProperty("synaxis.login-timeout", DEFAULTS.loginTimeout))
                .loginPluginMessageTimeout(longProperty("synaxis.login-plugin-message-timeout", DEFAULTS.loginPluginMessageTimeout))
                .knownPacksResponseTimeout(longProperty("synaxis.known-packs-response-timeout", DEFAULTS.knownPacksResponseTimeout))
                .acceptTransfers(booleanProperty("synaxis.accept-transfers", DEFAULTS.acceptTransfers))
                .playerPacketPerTick(intProperty("synaxis.packet-per-tick", DEFAULTS.playerPacketPerTick))
                .playerPacketQueueSize(intProperty("synaxis.packet-queue-size", DEFAULTS.playerPacketQueueSize))
                .keepAliveDelay(longProperty("synaxis.keep-alive-delay", DEFAULTS.keepAliveDelay))
                .keepAliveKick(longProperty("synaxis.keep-alive-kick", DEFAULTS.keepAliveKick))
                .playerChunkUpdateLimiterHistorySize(intProperty(CHUNK_UPDATE_LIMITER_HISTORY_SIZE, DEFAULTS.playerChunkUpdateLimiterHistorySize))
                .maxConnections(intProperty(MAX_CONNECTIONS, DEFAULTS.maxConnections))
                .acceptBackoff(longProperty(ACCEPT_BACKOFF, DEFAULTS.acceptBackoff))
                .maxConnectionsPerIp(intProperty(MAX_CONNECTIONS_PER_IP, DEFAULTS.maxConnectionsPerIp))
                .handshakeTimeout(longProperty(HANDSHAKE_TIMEOUT, DEFAULTS.handshakeTimeout))
                .loginRatePerIp(doubleProperty(LOGIN_RATE_PER_IP, DEFAULTS.loginRatePerIp))
                .loginBurstPerIp(intProperty(LOGIN_BURST_PER_IP, DEFAULTS.loginBurstPerIp))
                .statusRatePerIp(doubleProperty(STATUS_RATE_PER_IP, DEFAULTS.statusRatePerIp))
                .statusBurstPerIp(intProperty(STATUS_BURST_PER_IP, DEFAULTS.statusBurstPerIp))
                .transferRatePerIp(doubleProperty(TRANSFER_RATE_PER_IP, DEFAULTS.transferRatePerIp))
                .transferBurstPerIp(intProperty(TRANSFER_BURST_PER_IP, DEFAULTS.transferBurstPerIp))
                .connectionGateTrustedNetworks(System.getProperty("synaxis.connection-gate.trusted-networks",
                        DEFAULTS.connectionGateTrustedNetworks))
                .maxPlayers(intProperty(MAX_PLAYERS, DEFAULTS.maxPlayers))
                .maxJoinsPerTick(intProperty(MAX_JOINS_PER_TICK, DEFAULTS.maxJoinsPerTick))
                .motd(System.getProperty("synaxis.motd", DEFAULTS.motd))
                .serverIcon(System.getProperty("synaxis.server-icon", DEFAULTS.serverIcon))
                .hideOnlinePlayers(booleanProperty("synaxis.hide-online-players", DEFAULTS.hideOnlinePlayers))
                .chatRatePerSecond(intProperty(CHAT_RATE_PER_SECOND, DEFAULTS.chatRatePerSecond))
                .chatBurst(intProperty(CHAT_BURST, DEFAULTS.chatBurst))
                .packetLimitWindow(longProperty(PACKET_LIMIT_WINDOW, DEFAULTS.packetLimitWindow))
                .packetLimitMaxPackets(intProperty(PACKET_LIMIT_MAX_PACKETS, DEFAULTS.packetLimitMaxPackets))
                .packetLimitAction(actionProperty("synaxis.packet-limit.action", DEFAULTS.packetLimitAction))
                .packetLimitOverrides(System.getProperty("synaxis.packet-limit.overrides", DEFAULTS.packetLimitOverrides))
                .maxInflatedBytesPerSecond(longProperty(MAX_INFLATED_BYTES_PER_SECOND,
                        DEFAULTS.maxInflatedBytesPerSecond))
                .tabCompleteRatePerSecond(intProperty(TAB_COMPLETE_RATE_PER_SECOND, DEFAULTS.tabCompleteRatePerSecond))
                .maxOutboundBacklogBytes(longProperty(MAX_OUTBOUND_BACKLOG_BYTES, DEFAULTS.maxOutboundBacklogBytes))
                .tabLatencyInterval(longProperty(TAB_LATENCY_INTERVAL, DEFAULTS.tabLatencyInterval))
                .compressionThreshold(intProperty(COMPRESSION_THRESHOLD, DEFAULTS.compressionThreshold))
                .suppressConnectionAcceptErrors(booleanProperty("synaxis.suppress-connection-accept-errors", DEFAULTS.suppressConnectionAcceptErrors))
                .suppressConnectionIoErrors(booleanProperty("synaxis.suppress-connection-io-errors", DEFAULTS.suppressConnectionIoErrors))
                .suppressMalformedPacketErrorLevel(intProperty("synaxis.suppress-malformed-packet-error-level", DEFAULTS.suppressMalformedPacketErrorLevel))
                .suppressMisusedPacketErrorLevel(intProperty("synaxis.suppress-misused-packet-error-level", DEFAULTS.suppressMisusedPacketErrorLevel))
                .rejectMalformedPacket(booleanProperty("synaxis.reject-malformed-packet", DEFAULTS.rejectMalformedPacket))
                .rejectMisusedPacket(booleanProperty("synaxis.reject-misused-packet", DEFAULTS.rejectMisusedPacket))
                .socketSendBufferSize(intProperty(SEND_BUFFER_SIZE, DEFAULTS.socketSendBufferSize))
                .socketReceiveBufferSize(intProperty(RECEIVE_BUFFER_SIZE, DEFAULTS.socketReceiveBufferSize))
                .socketNoDelay(booleanProperty("synaxis.tcp-no-delay", DEFAULTS.socketNoDelay))
                .socketTimeout(intProperty("synaxis.socket-timeout", DEFAULTS.socketTimeout))
                .pooledBufferSize(intProperty(POOLED_BUFFER_SIZE, DEFAULTS.pooledBufferSize))
                .minChunksPerTick(floatProperty("synaxis.chunk-queue.min-per-tick", DEFAULTS.minChunksPerTick))
                .maxChunksPerTick(floatProperty("synaxis.chunk-queue.max-per-tick", DEFAULTS.maxChunksPerTick))
                .chunksPerTickMultiplier(floatProperty("synaxis.chunk-queue.multiplier", DEFAULTS.chunksPerTickMultiplier))
                .viewablePacket(booleanProperty("synaxis.viewable-packet", DEFAULTS.viewablePacket))
                .enforceInteractionLimit(booleanProperty("synaxis.enforce-entity-interaction-range", DEFAULTS.enforceInteractionLimit))
                .maxVisibleEntitiesPerPlayer(intProperty(MAX_VISIBLE_ENTITIES_PER_PLAYER, DEFAULTS.maxVisibleEntitiesPerPlayer))
                .watchdogEarlyWarning(longProperty(WATCHDOG_EARLY_WARNING, DEFAULTS.watchdogEarlyWarning))
                .watchdogEarlyWarningEvery(longProperty(WATCHDOG_EARLY_WARNING_EVERY, DEFAULTS.watchdogEarlyWarningEvery))
                .watchdogTimeout(longProperty(WATCHDOG_TIMEOUT, DEFAULTS.watchdogTimeout))
                .watchdogHaltAfter(longProperty(WATCHDOG_HALT_AFTER, DEFAULTS.watchdogHaltAfter))
                .registryUnsafeOps(booleanProperty("synaxis.registry.unsafe-ops", DEFAULTS.registryUnsafeOps))
                .insideTest(booleanProperty("synaxis.inside-test", DEFAULTS.insideTest))
                .fasterSocketWrites(booleanProperty("synaxis.new-socket-write-lock", DEFAULTS.fasterSocketWrites))
                .proxyProtocol(booleanProperty("synaxis.proxy-protocol", DEFAULTS.proxyProtocol))
                .proxyProtocolRequired(booleanProperty("synaxis.proxy-protocol.required", DEFAULTS.proxyProtocolRequired))
                .proxyProtocolTrustedNetworks(System.getProperty(PROXY_PROTOCOL_TRUSTED_NETWORKS,
                        DEFAULTS.proxyProtocolTrustedNetworks))
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder holding every value of this configuration, to change some of them.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    private static final ServerConfig DEFAULTS = new Builder().build();

    /**
     * Reads a boolean key as it is written: {@code true} or {@code false}, lower case, nothing
     * around it ({@code docs/DESIGN-PROD.md} {@code API-PROD-023}). Any other value refuses the
     * start, since reading it as false would turn a guard that is on by default off without a word,
     * e.g. {@code true} with a trailing blank in {@code synaxis.properties}.
     */
    private static boolean booleanProperty(String name, boolean defaultValue) {
        final String value = System.getProperty(name);
        if (value == null) return defaultValue;
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Property '" + name + "' is not true or false: \"" + value + "\"");
        };
    }

    /**
     * Reads a key with {@code parser}, refusing a value it cannot parse with
     * {@code Property '<name>' <complaint>: <value>}.
     */
    private static <T> T parsedProperty(String name, T defaultValue, Function<String, T> parser, String complaint) {
        final String value = System.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Property '" + name + "' " + complaint + ": " + value, e);
        }
    }

    private static int intProperty(String name, int defaultValue) {
        return parsedProperty(name, defaultValue, Integer::parseInt, "is not an integer");
    }

    private static ThreadProviderKind threadProviderProperty(String name, ThreadProviderKind defaultValue) {
        final String value = System.getProperty(name);
        return value == null ? defaultValue : threadProvider(name, value);
    }

    static ThreadProviderKind threadProvider(String name, String value) {
        return switch (value) {
            case "counter" -> ThreadProviderKind.COUNTER;
            case "grid" -> ThreadProviderKind.GRID;
            default -> throw new IllegalArgumentException("Property '" + name + "' is not counter or grid: " + value);
        };
    }

    private static long longProperty(String name, long defaultValue) {
        return parsedProperty(name, defaultValue, Long::parseLong, "is not a long");
    }

    private static double doubleProperty(String name, double defaultValue) {
        return parsedProperty(name, defaultValue, Double::parseDouble, "is not a number");
    }

    private static PacketLimitAction actionProperty(String name, PacketLimitAction defaultValue) {
        return parsedProperty(name, defaultValue, PacketLimitAction::valueOf, "is neither DROP nor KICK");
    }

    private static float floatProperty(String name, float defaultValue) {
        return parsedProperty(name, defaultValue, Float::parseFloat, "is not a float");
    }

    @SuppressWarnings("MissingSummary") // the setters carry no information the component names do not
    public static final class Builder {
        private boolean shutdownOnSignal = true;
        private int maxTickCatchUp = 5;
        private int chunkViewDistance = 8;
        private int entityViewDistance = 5;
        private int entitySynchronizationTicks = 20;
        private int dispatcherThreads = 1;
        private ThreadProviderKind threadProvider = ThreadProviderKind.COUNTER;
        private int threadGridExponent = 4; // cells of 16 by 16 chunks
        private int sendLightAfterBlockPlacementDelay = 100;
        private long loginTimeout = 30_000; // 30s from TCP accept to the configuration state
        private long loginPluginMessageTimeout = 5_000;
        private long knownPacksResponseTimeout = 5 * 60_000;
        private boolean acceptTransfers = false;
        private int playerPacketPerTick = 50;
        private int playerPacketQueueSize = 1000;
        private long keepAliveDelay = 10_000;
        private long keepAliveKick = 15_000;
        private int playerChunkUpdateLimiterHistorySize = 5;
        private int maxConnections = 1024;
        private long acceptBackoff = 100;
        private int maxConnectionsPerIp = 16;
        private long handshakeTimeout = 5_000;
        private double loginRatePerIp = 0.5;
        private int loginBurstPerIp = 3;
        private double statusRatePerIp = 1;
        private int statusBurstPerIp = 10;
        private double transferRatePerIp = 2;
        private int transferBurstPerIp = 10;
        private String connectionGateTrustedNetworks = "";
        private int maxPlayers = 0; // 0 means no limit
        private int maxJoinsPerTick = 0; // 0 means no limit
        private String motd = "synaxis";
        private String serverIcon = ""; // no icon
        private boolean hideOnlinePlayers = false;
        private int chatRatePerSecond = 1;
        private int chatBurst = 3;
        // Twice the highest 7 s P99 of the packets one bot sent in the load tiers of RUN-057, 374
        // (ride, 200 bots; docs/test-runs/RUN-057/10-limit-readings.txt), rounded up to 750
        private long packetLimitWindow = 7_000;
        private int packetLimitMaxPackets = 750;
        private PacketLimitAction packetLimitAction = PacketLimitAction.KICK;
        private String packetLimitOverrides = "";
        private long maxInflatedBytesPerSecond = 4L << 20;
        private int tabCompleteRatePerSecond = 20;
        private long maxOutboundBacklogBytes = 4L << 20;
        private long tabLatencyInterval = 30_000;
        private int compressionThreshold = 256;
        private boolean suppressConnectionAcceptErrors = true;
        private boolean suppressConnectionIoErrors = true; // Like peer resets etc
        private int suppressMalformedPacketErrorLevel = 0;
        private int suppressMisusedPacketErrorLevel = 0;
        private boolean rejectMalformedPacket = true;
        private boolean rejectMisusedPacket = false;
        private int socketSendBufferSize = 262_143;
        private int socketReceiveBufferSize = 32_767;
        private boolean socketNoDelay = true;
        private int socketTimeout = 15_000;
        private int pooledBufferSize = DEFAULT_POOLED_BUFFER_SIZE;
        private float minChunksPerTick = 0.01f;
        private float maxChunksPerTick = 64.0f;
        private float chunksPerTickMultiplier = 1f;
        private boolean viewablePacket = true;
        private boolean enforceInteractionLimit = true;
        private int maxVisibleEntitiesPerPlayer = 0; // 0 means no limit
        private long watchdogEarlyWarning = 10_000;
        private long watchdogEarlyWarningEvery = 5_000;
        private long watchdogTimeout = 60_000;
        private long watchdogHaltAfter = 15_000;
        private boolean registryUnsafeOps = false;
        private boolean insideTest = false;
        private boolean fasterSocketWrites = false;
        private boolean proxyProtocol = false;
        private boolean proxyProtocolRequired = false;
        private String proxyProtocolTrustedNetworks = "";

        private Builder() {
        }

        private Builder(ServerConfig config) {
            this.shutdownOnSignal = config.shutdownOnSignal;
            this.maxTickCatchUp = config.maxTickCatchUp;
            this.chunkViewDistance = config.chunkViewDistance;
            this.entityViewDistance = config.entityViewDistance;
            this.entitySynchronizationTicks = config.entitySynchronizationTicks;
            this.dispatcherThreads = config.dispatcherThreads;
            this.threadProvider = config.threadProvider;
            this.threadGridExponent = config.threadGridExponent;
            this.sendLightAfterBlockPlacementDelay = config.sendLightAfterBlockPlacementDelay;
            this.loginTimeout = config.loginTimeout;
            this.loginPluginMessageTimeout = config.loginPluginMessageTimeout;
            this.knownPacksResponseTimeout = config.knownPacksResponseTimeout;
            this.acceptTransfers = config.acceptTransfers;
            this.playerPacketPerTick = config.playerPacketPerTick;
            this.playerPacketQueueSize = config.playerPacketQueueSize;
            this.keepAliveDelay = config.keepAliveDelay;
            this.keepAliveKick = config.keepAliveKick;
            this.playerChunkUpdateLimiterHistorySize = config.playerChunkUpdateLimiterHistorySize;
            this.maxConnections = config.maxConnections;
            this.acceptBackoff = config.acceptBackoff;
            this.maxConnectionsPerIp = config.maxConnectionsPerIp;
            this.handshakeTimeout = config.handshakeTimeout;
            this.loginRatePerIp = config.loginRatePerIp;
            this.loginBurstPerIp = config.loginBurstPerIp;
            this.statusRatePerIp = config.statusRatePerIp;
            this.statusBurstPerIp = config.statusBurstPerIp;
            this.transferRatePerIp = config.transferRatePerIp;
            this.transferBurstPerIp = config.transferBurstPerIp;
            this.connectionGateTrustedNetworks = config.connectionGateTrustedNetworks;
            this.maxPlayers = config.maxPlayers;
            this.maxJoinsPerTick = config.maxJoinsPerTick;
            this.motd = config.motd;
            this.serverIcon = config.serverIcon;
            this.hideOnlinePlayers = config.hideOnlinePlayers;
            this.chatRatePerSecond = config.chatRatePerSecond;
            this.chatBurst = config.chatBurst;
            this.packetLimitWindow = config.packetLimitWindow;
            this.packetLimitMaxPackets = config.packetLimitMaxPackets;
            this.packetLimitAction = config.packetLimitAction;
            this.packetLimitOverrides = config.packetLimitOverrides;
            this.maxInflatedBytesPerSecond = config.maxInflatedBytesPerSecond;
            this.tabCompleteRatePerSecond = config.tabCompleteRatePerSecond;
            this.maxOutboundBacklogBytes = config.maxOutboundBacklogBytes;
            this.tabLatencyInterval = config.tabLatencyInterval;
            this.compressionThreshold = config.compressionThreshold;
            this.suppressConnectionAcceptErrors = config.suppressConnectionAcceptErrors;
            this.suppressConnectionIoErrors = config.suppressConnectionIoErrors;
            this.suppressMalformedPacketErrorLevel = config.suppressMalformedPacketErrorLevel;
            this.suppressMisusedPacketErrorLevel = config.suppressMisusedPacketErrorLevel;
            this.rejectMalformedPacket = config.rejectMalformedPacket;
            this.rejectMisusedPacket = config.rejectMisusedPacket;
            this.socketSendBufferSize = config.socketSendBufferSize;
            this.socketReceiveBufferSize = config.socketReceiveBufferSize;
            this.socketNoDelay = config.socketNoDelay;
            this.socketTimeout = config.socketTimeout;
            this.pooledBufferSize = config.pooledBufferSize;
            this.minChunksPerTick = config.minChunksPerTick;
            this.maxChunksPerTick = config.maxChunksPerTick;
            this.chunksPerTickMultiplier = config.chunksPerTickMultiplier;
            this.viewablePacket = config.viewablePacket;
            this.enforceInteractionLimit = config.enforceInteractionLimit;
            this.maxVisibleEntitiesPerPlayer = config.maxVisibleEntitiesPerPlayer;
            this.watchdogEarlyWarning = config.watchdogEarlyWarning;
            this.watchdogEarlyWarningEvery = config.watchdogEarlyWarningEvery;
            this.watchdogTimeout = config.watchdogTimeout;
            this.watchdogHaltAfter = config.watchdogHaltAfter;
            this.registryUnsafeOps = config.registryUnsafeOps;
            this.insideTest = config.insideTest;
            this.fasterSocketWrites = config.fasterSocketWrites;
            this.proxyProtocol = config.proxyProtocol;
            this.proxyProtocolRequired = config.proxyProtocolRequired;
            this.proxyProtocolTrustedNetworks = config.proxyProtocolTrustedNetworks;
        }

        public Builder shutdownOnSignal(boolean shutdownOnSignal) {
            this.shutdownOnSignal = shutdownOnSignal;
            return this;
        }

        public Builder maxTickCatchUp(int maxTickCatchUp) {
            this.maxTickCatchUp = maxTickCatchUp;
            return this;
        }

        public Builder chunkViewDistance(int chunkViewDistance) {
            this.chunkViewDistance = chunkViewDistance;
            return this;
        }

        public Builder entityViewDistance(int entityViewDistance) {
            this.entityViewDistance = entityViewDistance;
            return this;
        }

        public Builder entitySynchronizationTicks(int entitySynchronizationTicks) {
            this.entitySynchronizationTicks = entitySynchronizationTicks;
            return this;
        }

        public Builder dispatcherThreads(int dispatcherThreads) {
            this.dispatcherThreads = dispatcherThreads;
            return this;
        }

        public Builder threadProvider(ThreadProviderKind threadProvider) {
            this.threadProvider = threadProvider;
            return this;
        }

        public Builder threadGridExponent(int threadGridExponent) {
            this.threadGridExponent = threadGridExponent;
            return this;
        }

        public Builder sendLightAfterBlockPlacementDelay(int sendLightAfterBlockPlacementDelay) {
            this.sendLightAfterBlockPlacementDelay = sendLightAfterBlockPlacementDelay;
            return this;
        }

        public Builder loginTimeout(long loginTimeout) {
            this.loginTimeout = loginTimeout;
            return this;
        }

        public Builder loginPluginMessageTimeout(long loginPluginMessageTimeout) {
            this.loginPluginMessageTimeout = loginPluginMessageTimeout;
            return this;
        }

        public Builder knownPacksResponseTimeout(long knownPacksResponseTimeout) {
            this.knownPacksResponseTimeout = knownPacksResponseTimeout;
            return this;
        }

        public Builder acceptTransfers(boolean acceptTransfers) {
            this.acceptTransfers = acceptTransfers;
            return this;
        }

        public Builder playerPacketPerTick(int playerPacketPerTick) {
            this.playerPacketPerTick = playerPacketPerTick;
            return this;
        }

        public Builder playerPacketQueueSize(int playerPacketQueueSize) {
            this.playerPacketQueueSize = playerPacketQueueSize;
            return this;
        }

        public Builder keepAliveDelay(long keepAliveDelay) {
            this.keepAliveDelay = keepAliveDelay;
            return this;
        }

        public Builder keepAliveKick(long keepAliveKick) {
            this.keepAliveKick = keepAliveKick;
            return this;
        }

        public Builder playerChunkUpdateLimiterHistorySize(int playerChunkUpdateLimiterHistorySize) {
            this.playerChunkUpdateLimiterHistorySize = playerChunkUpdateLimiterHistorySize;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder acceptBackoff(long acceptBackoff) {
            this.acceptBackoff = acceptBackoff;
            return this;
        }

        public Builder maxConnectionsPerIp(int maxConnectionsPerIp) {
            this.maxConnectionsPerIp = maxConnectionsPerIp;
            return this;
        }

        public Builder handshakeTimeout(long handshakeTimeout) {
            this.handshakeTimeout = handshakeTimeout;
            return this;
        }

        public Builder loginRatePerIp(double loginRatePerIp) {
            this.loginRatePerIp = loginRatePerIp;
            return this;
        }

        public Builder loginBurstPerIp(int loginBurstPerIp) {
            this.loginBurstPerIp = loginBurstPerIp;
            return this;
        }

        public Builder statusRatePerIp(double statusRatePerIp) {
            this.statusRatePerIp = statusRatePerIp;
            return this;
        }

        public Builder statusBurstPerIp(int statusBurstPerIp) {
            this.statusBurstPerIp = statusBurstPerIp;
            return this;
        }

        public Builder transferRatePerIp(double transferRatePerIp) {
            this.transferRatePerIp = transferRatePerIp;
            return this;
        }

        public Builder transferBurstPerIp(int transferBurstPerIp) {
            this.transferBurstPerIp = transferBurstPerIp;
            return this;
        }

        public Builder connectionGateTrustedNetworks(String connectionGateTrustedNetworks) {
            this.connectionGateTrustedNetworks = connectionGateTrustedNetworks;
            return this;
        }

        public Builder maxPlayers(int maxPlayers) {
            this.maxPlayers = maxPlayers;
            return this;
        }

        public Builder maxJoinsPerTick(int maxJoinsPerTick) {
            this.maxJoinsPerTick = maxJoinsPerTick;
            return this;
        }

        public Builder motd(String motd) {
            this.motd = motd;
            return this;
        }

        public Builder serverIcon(String serverIcon) {
            this.serverIcon = serverIcon;
            return this;
        }

        public Builder hideOnlinePlayers(boolean hideOnlinePlayers) {
            this.hideOnlinePlayers = hideOnlinePlayers;
            return this;
        }

        public Builder chatRatePerSecond(int chatRatePerSecond) {
            this.chatRatePerSecond = chatRatePerSecond;
            return this;
        }

        public Builder chatBurst(int chatBurst) {
            this.chatBurst = chatBurst;
            return this;
        }

        public Builder packetLimitWindow(long packetLimitWindow) {
            this.packetLimitWindow = packetLimitWindow;
            return this;
        }

        public Builder packetLimitMaxPackets(int packetLimitMaxPackets) {
            this.packetLimitMaxPackets = packetLimitMaxPackets;
            return this;
        }

        public Builder packetLimitAction(PacketLimitAction packetLimitAction) {
            this.packetLimitAction = packetLimitAction;
            return this;
        }

        public Builder packetLimitOverrides(String packetLimitOverrides) {
            this.packetLimitOverrides = packetLimitOverrides;
            return this;
        }

        public Builder maxInflatedBytesPerSecond(long maxInflatedBytesPerSecond) {
            this.maxInflatedBytesPerSecond = maxInflatedBytesPerSecond;
            return this;
        }

        public Builder tabCompleteRatePerSecond(int tabCompleteRatePerSecond) {
            this.tabCompleteRatePerSecond = tabCompleteRatePerSecond;
            return this;
        }

        public Builder maxOutboundBacklogBytes(long maxOutboundBacklogBytes) {
            this.maxOutboundBacklogBytes = maxOutboundBacklogBytes;
            return this;
        }

        public Builder tabLatencyInterval(long tabLatencyInterval) {
            this.tabLatencyInterval = tabLatencyInterval;
            return this;
        }

        public Builder compressionThreshold(int compressionThreshold) {
            this.compressionThreshold = compressionThreshold;
            return this;
        }

        public Builder suppressConnectionAcceptErrors(boolean suppressConnectionAcceptErrors) {
            this.suppressConnectionAcceptErrors = suppressConnectionAcceptErrors;
            return this;
        }

        public Builder suppressConnectionIoErrors(boolean suppressConnectionIoErrors) {
            this.suppressConnectionIoErrors = suppressConnectionIoErrors;
            return this;
        }

        public Builder suppressMalformedPacketErrorLevel(int suppressMalformedPacketErrorLevel) {
            this.suppressMalformedPacketErrorLevel = suppressMalformedPacketErrorLevel;
            return this;
        }

        public Builder suppressMisusedPacketErrorLevel(int suppressMisusedPacketErrorLevel) {
            this.suppressMisusedPacketErrorLevel = suppressMisusedPacketErrorLevel;
            return this;
        }

        public Builder rejectMalformedPacket(boolean rejectMalformedPacket) {
            this.rejectMalformedPacket = rejectMalformedPacket;
            return this;
        }

        public Builder rejectMisusedPacket(boolean rejectMisusedPacket) {
            this.rejectMisusedPacket = rejectMisusedPacket;
            return this;
        }

        public Builder socketSendBufferSize(int socketSendBufferSize) {
            this.socketSendBufferSize = socketSendBufferSize;
            return this;
        }

        public Builder socketReceiveBufferSize(int socketReceiveBufferSize) {
            this.socketReceiveBufferSize = socketReceiveBufferSize;
            return this;
        }

        public Builder socketNoDelay(boolean socketNoDelay) {
            this.socketNoDelay = socketNoDelay;
            return this;
        }

        /**
         * @deprecated has no effect, see {@link ServerConfig#socketTimeout()}
         */
        @Deprecated
        public Builder socketTimeout(int socketTimeout) {
            this.socketTimeout = socketTimeout;
            return this;
        }

        public Builder pooledBufferSize(int pooledBufferSize) {
            this.pooledBufferSize = pooledBufferSize;
            return this;
        }

        public Builder minChunksPerTick(float minChunksPerTick) {
            this.minChunksPerTick = minChunksPerTick;
            return this;
        }

        public Builder maxChunksPerTick(float maxChunksPerTick) {
            this.maxChunksPerTick = maxChunksPerTick;
            return this;
        }

        public Builder chunksPerTickMultiplier(float chunksPerTickMultiplier) {
            this.chunksPerTickMultiplier = chunksPerTickMultiplier;
            return this;
        }

        public Builder viewablePacket(boolean viewablePacket) {
            this.viewablePacket = viewablePacket;
            return this;
        }

        public Builder enforceInteractionLimit(boolean enforceInteractionLimit) {
            this.enforceInteractionLimit = enforceInteractionLimit;
            return this;
        }

        public Builder maxVisibleEntitiesPerPlayer(int maxVisibleEntitiesPerPlayer) {
            this.maxVisibleEntitiesPerPlayer = maxVisibleEntitiesPerPlayer;
            return this;
        }

        public Builder watchdogEarlyWarning(long watchdogEarlyWarning) {
            this.watchdogEarlyWarning = watchdogEarlyWarning;
            return this;
        }

        public Builder watchdogEarlyWarningEvery(long watchdogEarlyWarningEvery) {
            this.watchdogEarlyWarningEvery = watchdogEarlyWarningEvery;
            return this;
        }

        public Builder watchdogTimeout(long watchdogTimeout) {
            this.watchdogTimeout = watchdogTimeout;
            return this;
        }

        public Builder watchdogHaltAfter(long watchdogHaltAfter) {
            this.watchdogHaltAfter = watchdogHaltAfter;
            return this;
        }

        public Builder registryUnsafeOps(boolean registryUnsafeOps) {
            this.registryUnsafeOps = registryUnsafeOps;
            return this;
        }

        public Builder insideTest(boolean insideTest) {
            this.insideTest = insideTest;
            return this;
        }

        public Builder fasterSocketWrites(boolean fasterSocketWrites) {
            this.fasterSocketWrites = fasterSocketWrites;
            return this;
        }

        public Builder proxyProtocol(boolean proxyProtocol) {
            this.proxyProtocol = proxyProtocol;
            return this;
        }

        public Builder proxyProtocolRequired(boolean proxyProtocolRequired) {
            this.proxyProtocolRequired = proxyProtocolRequired;
            return this;
        }

        public Builder proxyProtocolTrustedNetworks(String proxyProtocolTrustedNetworks) {
            this.proxyProtocolTrustedNetworks = proxyProtocolTrustedNetworks;
            return this;
        }

        public ServerConfig build() {
            return new ServerConfig(shutdownOnSignal, maxTickCatchUp, chunkViewDistance, entityViewDistance,
                    entitySynchronizationTicks, dispatcherThreads, threadProvider, threadGridExponent,
                    sendLightAfterBlockPlacementDelay, loginTimeout,
                    loginPluginMessageTimeout, knownPacksResponseTimeout, acceptTransfers, playerPacketPerTick,
                    playerPacketQueueSize, keepAliveDelay, keepAliveKick, playerChunkUpdateLimiterHistorySize,
                    maxConnections, acceptBackoff, maxConnectionsPerIp, handshakeTimeout, loginRatePerIp,
                    loginBurstPerIp, statusRatePerIp, statusBurstPerIp, transferRatePerIp, transferBurstPerIp,
                    connectionGateTrustedNetworks, maxPlayers, maxJoinsPerTick,
                    motd, serverIcon, hideOnlinePlayers,
                    chatRatePerSecond, chatBurst,
                    packetLimitWindow, packetLimitMaxPackets, packetLimitAction, packetLimitOverrides,
                    maxInflatedBytesPerSecond, tabCompleteRatePerSecond, maxOutboundBacklogBytes, tabLatencyInterval,
                    compressionThreshold,
                    suppressConnectionAcceptErrors, suppressConnectionIoErrors, suppressMalformedPacketErrorLevel,
                    suppressMisusedPacketErrorLevel, rejectMalformedPacket, rejectMisusedPacket, socketSendBufferSize,
                    socketReceiveBufferSize, socketNoDelay, socketTimeout, pooledBufferSize, minChunksPerTick,
                    maxChunksPerTick, chunksPerTickMultiplier, viewablePacket, enforceInteractionLimit,
                    maxVisibleEntitiesPerPlayer, watchdogEarlyWarning, watchdogEarlyWarningEvery, watchdogTimeout, watchdogHaltAfter,
                    registryUnsafeOps, insideTest, fasterSocketWrites, proxyProtocol, proxyProtocolRequired,
                    proxyProtocolTrustedNetworks);
        }
    }
}
