package io.github.bearisbug.synaxis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerConfigTest {

    /**
     * REQ-014: tests and production share one default table. Nothing may inject an engine setting
     * into the test JVM, so reading the system properties here has to give the plain defaults back.
     * The exceptions are the thread count and the thread provider, which the multi-threaded CI
     * rounds hand to every test JVM (REQ-PROD-019, REQ-PROD-020).
     */
    @Test
    public void testJvmUsesTheProductionDefaults() {
        final ServerConfig defaults = ServerConfig.defaults();
        final ServerConfig fromJvm = ServerConfig.fromSystemProperties();
        assertEquals(defaults, fromJvm.toBuilder().dispatcherThreads(defaults.dispatcherThreads())
                .threadProvider(defaults.threadProvider()).build());
    }

    @Test
    public void defaults() {
        final ServerConfig config = ServerConfig.defaults();
        assertEquals(1, config.dispatcherThreads());
        // docs/DESIGN-PROD.md API-PROD-016
        assertEquals(ServerConfig.ThreadProviderKind.COUNTER, config.threadProvider());
        assertEquals(4, config.threadGridExponent());
        assertEquals(8, config.chunkViewDistance());
        assertEquals(5, config.entityViewDistance());
        assertEquals(30_000, config.loginTimeout());
        assertTrue(config.viewablePacket());
        assertFalse(config.insideTest());
        // docs/DESIGN-PROD.md API-PROD-013
        assertEquals(10_000, config.watchdogEarlyWarning());
        assertEquals(5_000, config.watchdogEarlyWarningEvery());
        assertEquals(60_000, config.watchdogTimeout());
        assertEquals(15_000, config.watchdogHaltAfter());
        // docs/DESIGN-PROD.md API-PROD-008
        assertEquals(1, config.chatRatePerSecond());
        assertEquals(3, config.chatBurst());
        // docs/DESIGN-PROD.md API-PROD-019: no visible entity limit
        assertEquals(0, config.maxVisibleEntitiesPerPlayer());
        // docs/DESIGN-PROD.md API-PROD-024: the MOTD, no icon, the sample shown
        assertEquals("synaxis", config.motd());
        assertEquals("", config.serverIcon());
        assertFalse(config.hideOnlinePlayers());
        // docs/DESIGN-PROD.md API-PROD-020: vanilla's threshold
        assertEquals(256, config.compressionThreshold());
    }

    @Test
    public void theProcessStartsAtTheCompressionThresholdOfItsConfiguration() {
        assertEquals(512, ServerProcess.of(ServerConfig.builder().compressionThreshold(512).build()).compressionThreshold());
        assertEquals(256, ServerProcess.of(ServerConfig.defaults()).compressionThreshold());
    }

    @Test
    public void builderOnlyChangesWhatItIsGiven() {
        final ServerConfig config = ServerConfig.builder().dispatcherThreads(4).build();
        assertEquals(4, config.dispatcherThreads());
        assertEquals(ServerConfig.defaults().chunkViewDistance(), config.chunkViewDistance());
        assertEquals(config, config.toBuilder().build());
    }

    @Test
    public void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().dispatcherThreads(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().playerChunkUpdateLimiterHistorySize(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().chatRatePerSecond(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().chatBurst(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().maxVisibleEntitiesPerPlayer(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().threadGridExponent(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().threadGridExponent(32).build());
        assertThrows(NullPointerException.class, () -> ServerConfig.builder().threadProvider(null).build());
        assertThrows(NullPointerException.class, () -> ServerConfig.builder().motd(null).build());
        assertThrows(NullPointerException.class, () -> ServerConfig.builder().serverIcon(null).build());
        assertEquals("compressionThreshold must not be negative but was -1, 0 turns compression off",
                assertThrows(IllegalArgumentException.class,
                        () -> ServerConfig.builder().compressionThreshold(-1).build()).getMessage());
    }

    /**
     * {@code synaxis.thread-provider} takes the two lower case names and nothing else. Read through
     * the parser rather than the JVM properties: every test JVM shares them with the tests running
     * next to this one.
     */
    @Test
    public void threadProviderNames() {
        assertEquals(ServerConfig.ThreadProviderKind.COUNTER, ServerConfig.threadProvider("p", "counter"));
        assertEquals(ServerConfig.ThreadProviderKind.GRID, ServerConfig.threadProvider("p", "grid"));
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.threadProvider("synaxis.thread-provider", "GRID"));
        assertEquals("Property 'synaxis.thread-provider' is not counter or grid: GRID", e.getMessage());
    }

    @Test
    public void everyProcessKeepsItsOwnConfiguration() {
        final ServerConfig first = ServerConfig.builder().chunkViewDistance(2).build();
        final ServerConfig second = ServerConfig.builder().chunkViewDistance(16).build();
        final ServerProcess a = ServerProcess.of(first);
        final ServerProcess b = ServerProcess.of(second);
        assertNotSame(a.config(), b.config());
        assertEquals(2, a.config().chunkViewDistance());
        assertEquals(16, b.config().chunkViewDistance());
    }

    /**
     * The values a process starts with but then cannot serve: the start is refused, the message
     * naming the key, instead of every player being disconnected or every login failing later.
     */
    @Test
    public void valuesThatBreakAStartedProcessAreRefusedByKey() {
        refused("synaxis.keep-alive-kick (10000 ms) must be greater than synaxis.keep-alive-delay (10000 ms)",
                ServerConfig.builder().keepAliveDelay(10_000).keepAliveKick(10_000));
        refused("synaxis.keep-alive-kick (15000 ms) must be greater than synaxis.keep-alive-delay (20000 ms)",
                ServerConfig.builder().keepAliveDelay(20_000));
        refused("synaxis.keep-alive-delay must be at least 1 ms but was 0", ServerConfig.builder().keepAliveDelay(0));
        refused("synaxis.login-timeout must be at least 1 ms but was 0", ServerConfig.builder().loginTimeout(0));
        refused("synaxis.packet-per-tick must be at least 1 but was 0", ServerConfig.builder().playerPacketPerTick(0));
        refused("synaxis.packet-queue-size must be at least 2", ServerConfig.builder().playerPacketQueueSize(1));
        refused("synaxis.packet-queue-size must be at least 2 and at least synaxis.packet-per-tick (50) but was 40",
                ServerConfig.builder().playerPacketQueueSize(40));
        refused("synaxis.send-buffer-size", ServerConfig.builder().socketSendBufferSize(0));
        refused("synaxis.receive-buffer-size", ServerConfig.builder().socketReceiveBufferSize(0));
        refused("synaxis.pooled-buffer-size", ServerConfig.builder().pooledBufferSize(0));
        refused("synaxis.watchdog.timeout-ms must be 0 (off) or at least 1000 ms",
                ServerConfig.builder().watchdogTimeout(60));
        refused("synaxis.chunk-queue.min-per-tick (8.0) must not be above synaxis.chunk-queue.max-per-tick (4.0)",
                ServerConfig.builder().minChunksPerTick(8f).maxChunksPerTick(4f));
        refused("synaxis.chunk-queue.min-per-tick (NaN)", ServerConfig.builder().minChunksPerTick(Float.NaN));
        refused("synaxis.entity-synchronization-ticks must be at least 1 but was 0",
                ServerConfig.builder().entitySynchronizationTicks(0));
        refused("synaxis.entity-view-distance and synaxis.max-tick-catch-up must not be negative",
                ServerConfig.builder().entityViewDistance(-1));
        refused("synaxis.entity-view-distance and synaxis.max-tick-catch-up must not be negative",
                ServerConfig.builder().maxTickCatchUp(-1));
        refused("chunkViewDistance must not be negative but was -1", ServerConfig.builder().chunkViewDistance(-1));
        assertEquals("connectionGateTrustedNetworks", assertThrows(NullPointerException.class,
                () -> ServerConfig.builder().connectionGateTrustedNetworks(null).build()).getMessage());
        assertEquals("packetLimitOverrides", assertThrows(NullPointerException.class,
                () -> ServerConfig.builder().packetLimitOverrides(null).build()).getMessage());
        assertEquals("proxyProtocolTrustedNetworks", assertThrows(NullPointerException.class,
                () -> ServerConfig.builder().proxyProtocolTrustedNetworks(null).build()).getMessage());

        // The edges that still start
        ServerConfig.builder().keepAliveDelay(1).keepAliveKick(2).loginTimeout(1).playerPacketPerTick(1)
                .playerPacketQueueSize(2).socketSendBufferSize(1).socketReceiveBufferSize(1).pooledBufferSize(1)
                .watchdogTimeout(1_000).minChunksPerTick(4f).maxChunksPerTick(4f).entitySynchronizationTicks(1)
                .entityViewDistance(0).maxTickCatchUp(0).chunkViewDistance(0).build();
        ServerConfig.builder().watchdogTimeout(0).build();
    }

    private static void refused(String message, ServerConfig.Builder builder) {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, builder::build, message);
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}
