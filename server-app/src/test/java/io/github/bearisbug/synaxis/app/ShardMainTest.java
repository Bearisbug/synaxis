package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code docs/DESIGN-PROD.md} {@code REQ-PROD-020}: a shard spreads its chunks by the grid over all
 * cores but two, and what the command line gave wins. Read through the property lookup rather than
 * the JVM properties, which every test of this JVM shares.
 */
public class ShardMainTest {

    @Test
    void anEightCoreShardRunsTheGridOnSixThreads() {
        assertEquals(Map.of("synaxis.thread-provider", "grid", "synaxis.dispatcher-threads", "6"),
                ShardMain.threadDefaults(Map.<String, String>of()::get, 8));
    }

    @Test
    void theCommandLineWins() {
        assertEquals(Map.of("synaxis.thread-provider", "grid"),
                ShardMain.threadDefaults(Map.of("synaxis.dispatcher-threads", "8")::get, 8));
        assertEquals(Map.of("synaxis.dispatcher-threads", "6"),
                ShardMain.threadDefaults(Map.of("synaxis.thread-provider", "counter")::get, 8));
    }

    @Test
    void aSmallMachineKeepsOneThread() {
        assertEquals("1", ShardMain.threadDefaults(Map.<String, String>of()::get, 2).get("synaxis.dispatcher-threads"));
        assertEquals("1", ShardMain.threadDefaults(Map.<String, String>of()::get, 1).get("synaxis.dispatcher-threads"));
    }
}
