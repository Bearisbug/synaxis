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
     * The one exception is the thread count, which the multi-threaded CI round hands to every test
     * JVM (REQ-PROD-019).
     */
    @Test
    public void testJvmUsesTheProductionDefaults() {
        final ServerConfig defaults = ServerConfig.defaults();
        final ServerConfig fromJvm = ServerConfig.fromSystemProperties();
        assertEquals(defaults, fromJvm.toBuilder().dispatcherThreads(defaults.dispatcherThreads()).build());
    }

    @Test
    public void defaults() {
        final ServerConfig config = ServerConfig.defaults();
        assertEquals(1, config.dispatcherThreads());
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
}
