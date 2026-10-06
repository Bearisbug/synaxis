package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code docs/DESIGN-PROD.md} {@code API-PROD-003}: the connection gate of a shard holds 3 sockets a seat
 * of its capacity, {@code synaxis.max-players}, and takes 5 players into the game a tick. What the
 * command line gave wins. Read through the property lookup rather than the JVM properties, which every
 * test of this JVM shares.
 */
public class ShardMainGateTest {

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
}
