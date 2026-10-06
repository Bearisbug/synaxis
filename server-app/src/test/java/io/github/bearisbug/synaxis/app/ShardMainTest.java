package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code docs/DESIGN-PROD.md} {@code REQ-PROD-020}: a shard spreads its chunks by the grid over all
 * cores but two; {@code docs/DESIGN-M3.md} {@code API-M3-006}: it sees 12 chunks, sends at most 4 a
 * tick and tracks at most 100 entities for a player, and takes transfers when it stores in
 * PostgreSQL; {@code docs/DESIGN-PROD.md} {@code API-PROD-003}: its connection gate follows its
 * capacity. What the command line gave wins. Read through the property lookup rather than the JVM
 * properties, which every test of this JVM shares.
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

    @Test
    void aShardSeesTwelveChunksSendsFourAndTracksAHundredEntities() {
        assertEquals(Map.of("synaxis.chunk-view-distance", "12", "synaxis.chunk-queue.max-per-tick", "4",
                        "synaxis.max-visible-entities-per-player", "100"),
                ShardMain.viewDefaults(Map.<String, String>of()::get));
    }

    @Test
    void theCommandLineWinsForTheViewSettings() {
        assertEquals(Map.of("synaxis.chunk-queue.max-per-tick", "4", "synaxis.max-visible-entities-per-player", "100"),
                ShardMain.viewDefaults(Map.of("synaxis.chunk-view-distance", "16")::get));
        assertEquals(Map.of("synaxis.chunk-view-distance", "12", "synaxis.max-visible-entities-per-player", "100"),
                ShardMain.viewDefaults(Map.of("synaxis.chunk-queue.max-per-tick", "64")::get));
        assertEquals(Map.of("synaxis.chunk-view-distance", "12", "synaxis.chunk-queue.max-per-tick", "4"),
                ShardMain.viewDefaults(Map.of("synaxis.max-visible-entities-per-player", "0")::get));
    }

    @Test
    void aMemoryShardTakesNoTransfers() {
        assertEquals(Map.of(), ShardMain.transferDefaults(Map.<String, String>of()::get));
        assertEquals(Map.of(), ShardMain.transferDefaults(Map.of("synaxis.app.store", "memory")::get));
    }

    @Test
    void aPostgresShardTakesTransfers() {
        assertEquals(Map.of("synaxis.accept-transfers", "true"),
                ShardMain.transferDefaults(Map.of("synaxis.app.store", "postgres")::get));
    }

    @Test
    void aShardMountingAMapServesTheCoreMode() {
        assertEquals(Map.of("synaxis.app.core-mode", "on"),
                ShardMain.mapDefaults(Map.of("synaxis.map.root", "maps", "synaxis.map.build", "b")::get));
    }

    /** Without both map keys nothing is written; a core mode on the command line wins, and Main refuses it. */
    @Test
    void aShardWithoutAMapOrWithACoreModeKeepsItsCoreMode() {
        assertEquals(Map.of(), ShardMain.mapDefaults(Map.<String, String>of()::get));
        assertEquals(Map.of(), ShardMain.mapDefaults(Map.of("synaxis.map.build", "b")::get));
        assertEquals(Map.of(), ShardMain.mapDefaults(Map.of("synaxis.map.root", "maps", "synaxis.map.build", "b",
                "synaxis.app.core-mode", "auto")::get));
    }

    /**
     * {@code docs/DESIGN-PROD.md} {@code API-PROD-003}: a shard holds 3 sockets a seat of its capacity,
     * {@code synaxis.max-players}, and takes 5 players into the game a tick.
     */
    @Test
    void aShardHoldsThreeSocketsASeatAndTakesFiveJoinsATick() {
        assertEquals(Map.of("synaxis.max-connections", "1500", "synaxis.max-joins-per-tick", "5"),
                ShardMain.gateDefaults(Map.of("synaxis.max-players", "500")::get));
        assertEquals(Map.of("synaxis.max-connections", "600", "synaxis.max-joins-per-tick", "5"),
                ShardMain.gateDefaults(Map.of("synaxis.max-players", "200")::get));
    }

    /** Without a capacity, or with one that is no number of seats, the socket cap stays the engine's. */
    @Test
    void aShardWithoutACapacityKeepsTheEngineSocketCap() {
        assertEquals(Map.of("synaxis.max-joins-per-tick", "5"), ShardMain.gateDefaults(Map.<String, String>of()::get));
        assertEquals(Map.of("synaxis.max-joins-per-tick", "5"),
                ShardMain.gateDefaults(Map.of("synaxis.max-players", "0")::get));
        assertEquals(Map.of("synaxis.max-joins-per-tick", "5"),
                ShardMain.gateDefaults(Map.of("synaxis.max-players", "many")::get));
    }

    @Test
    void theCommandLineWinsForTheGate() {
        assertEquals(Map.of("synaxis.max-joins-per-tick", "5"), ShardMain.gateDefaults(
                Map.of("synaxis.max-players", "500", "synaxis.max-connections", "4096")::get));
        assertEquals(Map.of("synaxis.max-connections", "1500"), ShardMain.gateDefaults(
                Map.of("synaxis.max-players", "500", "synaxis.max-joins-per-tick", "0")::get));
    }

    @Test
    void theCommandLineWinsForTransfers() {
        assertEquals(Map.of(), ShardMain.transferDefaults(
                Map.of("synaxis.app.store", "postgres", "synaxis.accept-transfers", "false")::get));
        assertEquals(Map.of(), ShardMain.transferDefaults(
                Map.of("synaxis.app.store", "memory", "synaxis.accept-transfers", "true")::get));
    }
}
