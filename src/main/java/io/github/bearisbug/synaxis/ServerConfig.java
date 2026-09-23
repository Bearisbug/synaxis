package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.network.ConnectionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // Chat (docs/DESIGN-PROD.md API-PROD-008): a token bucket per connection
        int chatRatePerSecond,
        int chatBurst,

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
        boolean proxyProtocolRequired,
        String proxyProtocolTrustedNetworks
) {
    /** Initial size of the pooled network buffers, also used by the buffer pools shared by every process. */
    public static final int DEFAULT_POOLED_BUFFER_SIZE = 16_383;

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConfig.class);

    private static final String PROXY_PROTOCOL_TRUSTED_NETWORKS = "synaxis.proxy-protocol.trusted-networks";

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

    public ServerConfig {
        if (dispatcherThreads < 1)
            throw new IllegalArgumentException("dispatcherThreads must be at least 1 but was " + dispatcherThreads);
        if (playerChunkUpdateLimiterHistorySize < 0)
            throw new IllegalArgumentException(
                    "playerChunkUpdateLimiterHistorySize must not be negative but was " + playerChunkUpdateLimiterHistorySize);
        if (chatRatePerSecond < 1 || chatBurst < 1)
            throw new IllegalArgumentException("the chat rate and burst must be at least 1 but were "
                    + chatRatePerSecond + " and " + chatBurst);
        if (watchdogEarlyWarning < 0 || watchdogEarlyWarningEvery < 0 || watchdogTimeout < 0 || watchdogHaltAfter < 0)
            throw new IllegalArgumentException("the watchdog durations must not be negative but were " + watchdogEarlyWarning
                    + ", " + watchdogEarlyWarningEvery + ", " + watchdogTimeout + " and " + watchdogHaltAfter);
        if (maxConnections < 1 || maxConnectionsPerIp < 1 || handshakeTimeout < 1)
            throw new IllegalArgumentException("maxConnections, maxConnectionsPerIp and handshakeTimeout must be at least 1 "
                    + "but were " + maxConnections + ", " + maxConnectionsPerIp + " and " + handshakeTimeout);
        if (acceptBackoff < 0 || maxPlayers < 0 || maxJoinsPerTick < 0)
            throw new IllegalArgumentException("acceptBackoff, maxPlayers and maxJoinsPerTick must not be negative but were "
                    + acceptBackoff + ", " + maxPlayers + " and " + maxJoinsPerTick);
        if (!(loginRatePerIp > 0) || !(statusRatePerIp > 0) || !(transferRatePerIp > 0)
                || loginBurstPerIp < 1 || statusBurstPerIp < 1 || transferBurstPerIp < 1)
            throw new IllegalArgumentException("the per address rates must be positive and the bursts at least 1 but were "
                    + loginRatePerIp + "/" + loginBurstPerIp + ", " + statusRatePerIp + "/" + statusBurstPerIp
                    + " and " + transferRatePerIp + "/" + transferBurstPerIp);
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
        return builder()
                .shutdownOnSignal(booleanProperty("synaxis.shutdown-on-signal", DEFAULTS.shutdownOnSignal))
                .maxTickCatchUp(intProperty("synaxis.max-tick-catch-up", DEFAULTS.maxTickCatchUp))
                .chunkViewDistance(intProperty("synaxis.chunk-view-distance", DEFAULTS.chunkViewDistance))
                .entityViewDistance(intProperty("synaxis.entity-view-distance", DEFAULTS.entityViewDistance))
                .entitySynchronizationTicks(intProperty("synaxis.entity-synchronization-ticks", DEFAULTS.entitySynchronizationTicks))
                .dispatcherThreads(intProperty("synaxis.dispatcher-threads", DEFAULTS.dispatcherThreads))
                .sendLightAfterBlockPlacementDelay(intProperty("synaxis.send-light-after-block-placement-delay", DEFAULTS.sendLightAfterBlockPlacementDelay))
                .loginTimeout(longProperty("synaxis.login-timeout", DEFAULTS.loginTimeout))
                .loginPluginMessageTimeout(longProperty("synaxis.login-plugin-message-timeout", DEFAULTS.loginPluginMessageTimeout))
                .knownPacksResponseTimeout(longProperty("synaxis.known-packs-response-timeout", DEFAULTS.knownPacksResponseTimeout))
                .acceptTransfers(booleanProperty("synaxis.accept-transfers", DEFAULTS.acceptTransfers))
                .playerPacketPerTick(intProperty("synaxis.packet-per-tick", DEFAULTS.playerPacketPerTick))
                .playerPacketQueueSize(intProperty("synaxis.packet-queue-size", DEFAULTS.playerPacketQueueSize))
                .keepAliveDelay(longProperty("synaxis.keep-alive-delay", DEFAULTS.keepAliveDelay))
                .keepAliveKick(longProperty("synaxis.keep-alive-kick", DEFAULTS.keepAliveKick))
                .playerChunkUpdateLimiterHistorySize(intProperty("synaxis.player.chunk-update-limiter-history-size", DEFAULTS.playerChunkUpdateLimiterHistorySize))
                .maxConnections(intProperty("synaxis.max-connections", DEFAULTS.maxConnections))
                .acceptBackoff(longProperty("synaxis.accept-backoff-ms", DEFAULTS.acceptBackoff))
                .maxConnectionsPerIp(intProperty("synaxis.max-connections-per-ip", DEFAULTS.maxConnectionsPerIp))
                .handshakeTimeout(longProperty("synaxis.handshake-timeout-ms", DEFAULTS.handshakeTimeout))
                .loginRatePerIp(doubleProperty("synaxis.login-rate-per-ip", DEFAULTS.loginRatePerIp))
                .loginBurstPerIp(intProperty("synaxis.login-burst-per-ip", DEFAULTS.loginBurstPerIp))
                .statusRatePerIp(doubleProperty("synaxis.status-rate-per-ip", DEFAULTS.statusRatePerIp))
                .statusBurstPerIp(intProperty("synaxis.status-burst-per-ip", DEFAULTS.statusBurstPerIp))
                .transferRatePerIp(doubleProperty("synaxis.transfer-rate-per-ip", DEFAULTS.transferRatePerIp))
                .transferBurstPerIp(intProperty("synaxis.transfer-burst-per-ip", DEFAULTS.transferBurstPerIp))
                .connectionGateTrustedNetworks(System.getProperty("synaxis.connection-gate.trusted-networks",
                        DEFAULTS.connectionGateTrustedNetworks))
                .maxPlayers(intProperty("synaxis.max-players", DEFAULTS.maxPlayers))
                .maxJoinsPerTick(intProperty("synaxis.max-joins-per-tick", DEFAULTS.maxJoinsPerTick))
                .chatRatePerSecond(intProperty("synaxis.chat-rate-per-second", DEFAULTS.chatRatePerSecond))
                .chatBurst(intProperty("synaxis.chat-burst", DEFAULTS.chatBurst))
                .suppressConnectionAcceptErrors(booleanProperty("synaxis.suppress-connection-accept-errors", DEFAULTS.suppressConnectionAcceptErrors))
                .suppressConnectionIoErrors(booleanProperty("synaxis.suppress-connection-io-errors", DEFAULTS.suppressConnectionIoErrors))
                .suppressMalformedPacketErrorLevel(intProperty("synaxis.suppress-malformed-packet-error-level", DEFAULTS.suppressMalformedPacketErrorLevel))
                .suppressMisusedPacketErrorLevel(intProperty("synaxis.suppress-misused-packet-error-level", DEFAULTS.suppressMisusedPacketErrorLevel))
                .rejectMalformedPacket(booleanProperty("synaxis.reject-malformed-packet", DEFAULTS.rejectMalformedPacket))
                .rejectMisusedPacket(booleanProperty("synaxis.reject-misused-packet", DEFAULTS.rejectMisusedPacket))
                .socketSendBufferSize(intProperty("synaxis.send-buffer-size", DEFAULTS.socketSendBufferSize))
                .socketReceiveBufferSize(intProperty("synaxis.receive-buffer-size", DEFAULTS.socketReceiveBufferSize))
                .socketNoDelay(booleanProperty("synaxis.tcp-no-delay", DEFAULTS.socketNoDelay))
                .socketTimeout(intProperty("synaxis.socket-timeout", DEFAULTS.socketTimeout))
                .pooledBufferSize(intProperty("synaxis.pooled-buffer-size", DEFAULTS.pooledBufferSize))
                .minChunksPerTick(floatProperty("synaxis.chunk-queue.min-per-tick", DEFAULTS.minChunksPerTick))
                .maxChunksPerTick(floatProperty("synaxis.chunk-queue.max-per-tick", DEFAULTS.maxChunksPerTick))
                .chunksPerTickMultiplier(floatProperty("synaxis.chunk-queue.multiplier", DEFAULTS.chunksPerTickMultiplier))
                .viewablePacket(booleanProperty("synaxis.viewable-packet", DEFAULTS.viewablePacket))
                .enforceInteractionLimit(booleanProperty("synaxis.enforce-entity-interaction-range", DEFAULTS.enforceInteractionLimit))
                .watchdogEarlyWarning(longProperty("synaxis.watchdog.early-warning-ms", DEFAULTS.watchdogEarlyWarning))
                .watchdogEarlyWarningEvery(longProperty("synaxis.watchdog.early-warning-every-ms", DEFAULTS.watchdogEarlyWarningEvery))
                .watchdogTimeout(longProperty("synaxis.watchdog.timeout-ms", DEFAULTS.watchdogTimeout))
                .watchdogHaltAfter(longProperty("synaxis.watchdog.halt-after-ms", DEFAULTS.watchdogHaltAfter))
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
     * A builder of this configuration, starting from the engine defaults.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    private static final ServerConfig DEFAULTS = new Builder().build();

    private static boolean booleanProperty(String name, boolean defaultValue) {
        final String value = System.getProperty(name);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }

    private static int intProperty(String name, int defaultValue) {
        final String value = System.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + name + "' is not an integer: " + value, e);
        }
    }

    private static long longProperty(String name, long defaultValue) {
        final String value = System.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + name + "' is not a long: " + value, e);
        }
    }

    private static double doubleProperty(String name, double defaultValue) {
        final String value = System.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + name + "' is not a number: " + value, e);
        }
    }

    private static float floatProperty(String name, float defaultValue) {
        final String value = System.getProperty(name);
        if (value == null) return defaultValue;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property '" + name + "' is not a float: " + value, e);
        }
    }

    @SuppressWarnings("MissingSummary") // the setters carry no information the component names do not
    public static final class Builder {
        private boolean shutdownOnSignal = true;
        private int maxTickCatchUp = 5;
        private int chunkViewDistance = 8;
        private int entityViewDistance = 5;
        private int entitySynchronizationTicks = 20;
        private int dispatcherThreads = 1;
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
        private int chatRatePerSecond = 1;
        private int chatBurst = 3;
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
            this.chatRatePerSecond = config.chatRatePerSecond;
            this.chatBurst = config.chatBurst;
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

        public Builder chatRatePerSecond(int chatRatePerSecond) {
            this.chatRatePerSecond = chatRatePerSecond;
            return this;
        }

        public Builder chatBurst(int chatBurst) {
            this.chatBurst = chatBurst;
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
                    entitySynchronizationTicks, dispatcherThreads, sendLightAfterBlockPlacementDelay, loginTimeout,
                    loginPluginMessageTimeout, knownPacksResponseTimeout, acceptTransfers, playerPacketPerTick,
                    playerPacketQueueSize, keepAliveDelay, keepAliveKick, playerChunkUpdateLimiterHistorySize,
                    maxConnections, acceptBackoff, maxConnectionsPerIp, handshakeTimeout, loginRatePerIp,
                    loginBurstPerIp, statusRatePerIp, statusBurstPerIp, transferRatePerIp, transferBurstPerIp,
                    connectionGateTrustedNetworks, maxPlayers, maxJoinsPerTick,
                    chatRatePerSecond, chatBurst,
                    suppressConnectionAcceptErrors, suppressConnectionIoErrors, suppressMalformedPacketErrorLevel,
                    suppressMisusedPacketErrorLevel, rejectMalformedPacket, rejectMisusedPacket, socketSendBufferSize,
                    socketReceiveBufferSize, socketNoDelay, socketTimeout, pooledBufferSize, minChunksPerTick,
                    maxChunksPerTick, chunksPerTickMultiplier, viewablePacket, enforceInteractionLimit,
                    watchdogEarlyWarning, watchdogEarlyWarningEvery, watchdogTimeout, watchdogHaltAfter,
                    registryUnsafeOps, insideTest, fasterSocketWrites, proxyProtocol, proxyProtocolRequired,
                    proxyProtocolTrustedNetworks);
        }
    }
}
