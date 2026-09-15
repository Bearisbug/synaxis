package io.github.bearisbug.synaxis;

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

        // Experimental/unstable
        boolean registryUnsafeOps,
        boolean insideTest,
        boolean fasterSocketWrites,
        boolean proxyProtocol,
        boolean proxyProtocolRequired
) {
    /** Initial size of the pooled network buffers, also used by the buffer pools shared by every process. */
    public static final int DEFAULT_POOLED_BUFFER_SIZE = 16_383;

    public ServerConfig {
        if (dispatcherThreads < 1)
            throw new IllegalArgumentException("dispatcherThreads must be at least 1 but was " + dispatcherThreads);
        if (playerChunkUpdateLimiterHistorySize < 0)
            throw new IllegalArgumentException(
                    "playerChunkUpdateLimiterHistorySize must not be negative but was " + playerChunkUpdateLimiterHistorySize);
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
                .registryUnsafeOps(booleanProperty("synaxis.registry.unsafe-ops", DEFAULTS.registryUnsafeOps))
                .insideTest(booleanProperty("synaxis.inside-test", DEFAULTS.insideTest))
                .fasterSocketWrites(booleanProperty("synaxis.new-socket-write-lock", DEFAULTS.fasterSocketWrites))
                .proxyProtocol(booleanProperty("synaxis.proxy-protocol", DEFAULTS.proxyProtocol))
                .proxyProtocolRequired(booleanProperty("synaxis.proxy-protocol.required", DEFAULTS.proxyProtocolRequired))
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
        private boolean registryUnsafeOps = false;
        private boolean insideTest = false;
        private boolean fasterSocketWrites = false;
        private boolean proxyProtocol = false;
        private boolean proxyProtocolRequired = false;

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
            this.registryUnsafeOps = config.registryUnsafeOps;
            this.insideTest = config.insideTest;
            this.fasterSocketWrites = config.fasterSocketWrites;
            this.proxyProtocol = config.proxyProtocol;
            this.proxyProtocolRequired = config.proxyProtocolRequired;
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

        public ServerConfig build() {
            return new ServerConfig(shutdownOnSignal, maxTickCatchUp, chunkViewDistance, entityViewDistance,
                    entitySynchronizationTicks, dispatcherThreads, sendLightAfterBlockPlacementDelay, loginTimeout,
                    loginPluginMessageTimeout, knownPacksResponseTimeout, acceptTransfers, playerPacketPerTick,
                    playerPacketQueueSize, keepAliveDelay, keepAliveKick, playerChunkUpdateLimiterHistorySize,
                    suppressConnectionAcceptErrors, suppressConnectionIoErrors, suppressMalformedPacketErrorLevel,
                    suppressMisusedPacketErrorLevel, rejectMalformedPacket, rejectMisusedPacket, socketSendBufferSize,
                    socketReceiveBufferSize, socketNoDelay, socketTimeout, pooledBufferSize, minChunksPerTick,
                    maxChunksPerTick, chunksPerTickMultiplier, viewablePacket, enforceInteractionLimit,
                    registryUnsafeOps, insideTest, fasterSocketWrites, proxyProtocol, proxyProtocolRequired);
        }
    }
}
