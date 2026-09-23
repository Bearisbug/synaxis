package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.Auth;
import io.github.bearisbug.synaxis.SessionServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch arguments of the startup contract (API-M2-010): the two system properties
 * {@code docs/TEST.md} §3 reads back as preconditions.
 */
class MainTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty("synaxis.app.port");
        System.clearProperty("synaxis.app.plugins");
        System.clearProperty("synaxis.app.shard-id");
        System.clearProperty("synaxis.app.ledger-max-bytes-per-day");
        System.clearProperty("synaxis.app.auth");
        System.clearProperty("synaxis.app.auth.session-server");
        System.clearProperty("synaxis.entity-view-distance");
        System.clearProperty("synaxis.chunk-view-distance");
        System.clearProperty("synaxis.player.chunk-update-limiter-history-size");
    }

    @Test
    void theDefaultLoginModeIsOnlineAgainstMojang() {
        final Auth.Online online = assertInstanceOf(Auth.Online.class, Main.auth());
        assertEquals(SessionServer.mojang().hasJoinedUrl(), online.sessionServer().hasJoinedUrl());
        assertTrue(online.sessionServer().isMojang());
    }

    @Test
    void offlineModesAreAskedForByName() {
        System.setProperty("synaxis.app.auth", "offline");
        assertEquals(new Auth.Offline(false), Main.auth());
        System.setProperty("synaxis.app.auth", "offline-encrypted");
        assertEquals(new Auth.Offline(true), Main.auth());
    }

    @Test
    void theSessionServerCanBeOverriddenForTesting() {
        System.setProperty("synaxis.app.auth.session-server", "http://127.0.0.1:25624/session/minecraft/hasJoined");
        final Auth.Online online = assertInstanceOf(Auth.Online.class, Main.auth());
        assertEquals(URI.create("http://127.0.0.1:25624/session/minecraft/hasJoined"), online.sessionServer().hasJoinedUrl());
        assertFalse(online.sessionServer().isMojang());
    }

    @Test
    void anUnknownLoginModeOrSessionServerIsRefused() {
        System.setProperty("synaxis.app.auth", "cracked");
        final IllegalArgumentException mode = assertThrows(IllegalArgumentException.class, Main::auth);
        assertTrue(mode.getMessage().contains("synaxis.app.auth=cracked"), mode.getMessage());
        System.setProperty("synaxis.app.auth", "online");
        System.setProperty("synaxis.app.auth.session-server", "localhost:25624");
        final IllegalArgumentException url = assertThrows(IllegalArgumentException.class, Main::auth);
        assertTrue(url.getMessage().contains("synaxis.app.auth.session-server=localhost:25624"), url.getMessage());
    }

    @Test
    void theDefaultPortIsUsedWhenThePropertyIsAbsent() {
        assertEquals(25565, Main.port());
    }

    @Test
    void thePropertyPicksThePort() {
        System.setProperty("synaxis.app.port", " 25570 ");
        assertEquals(25570, Main.port());
    }

    @Test
    void aPortThatIsNotANumberIsRefused() {
        System.setProperty("synaxis.app.port", "abc");
        final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, Main::port);
        assertTrue(refusal.getMessage().contains("synaxis.app.port=abc"), refusal.getMessage());
    }

    // §15: the RTS camera sees far more of the world than the engine defaults send, and two of
    // these three freeze while the process is being created
    @Test
    void theViewRangeOfTheRtsCameraIsSetBeforeTheProcessExists() {
        Main.applyViewRangeDefaults();

        assertEquals("8", System.getProperty("synaxis.entity-view-distance"));
        assertEquals("10", System.getProperty("synaxis.chunk-view-distance"));
        assertEquals("0", System.getProperty("synaxis.player.chunk-update-limiter-history-size"));
    }

    @Test
    void aViewRangeTheOperatorPassedIsLeftAlone() {
        System.setProperty("synaxis.chunk-view-distance", "6");

        Main.applyViewRangeDefaults();

        assertEquals("6", System.getProperty("synaxis.chunk-view-distance"));
        assertEquals("8", System.getProperty("synaxis.entity-view-distance"));
    }

    @Test
    void noSelectionPropertyMeansEveryDiscoveredPlugin() {
        assertEquals(Set.of(), Main.selectedPlugins());
    }

    @Test
    void theSelectionPropertyIsACommaSeparatedList() {
        System.setProperty("synaxis.app.plugins", "rts, probe ,");
        assertEquals(Set.of("rts", "probe"), Main.selectedPlugins());
    }

    @Test
    void theShardIdDefaultsToLocalAndTakesTheProperty() {
        assertEquals("local", Main.shardId());
        System.setProperty("synaxis.app.shard-id", "r01-c1");
        assertEquals("r01-c1", Main.shardId());
    }

    // The shard id is part of the ledger and crash report file names
    @Test
    void aShardIdThatCouldLeaveTheDirectoryIsRefused() {
        System.setProperty("synaxis.app.shard-id", "../r01");
        final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, Main::shardId);
        assertTrue(refusal.getMessage().contains("synaxis.app.shard-id=../r01"), refusal.getMessage());
    }

    @Test
    void theLedgerCapIsOneGibibyteUnlessThePropertySaysOtherwise() {
        assertEquals(1L << 30, Main.ledgerMaxBytesPerDay());
        System.setProperty("synaxis.app.ledger-max-bytes-per-day", "1048576");
        assertEquals(1_048_576L, Main.ledgerMaxBytesPerDay());
    }

    @Test
    void aLedgerCapThatIsNotAPositiveNumberIsRefused() {
        System.setProperty("synaxis.app.ledger-max-bytes-per-day", "0");
        assertThrows(IllegalArgumentException.class, Main::ledgerMaxBytesPerDay);
        System.setProperty("synaxis.app.ledger-max-bytes-per-day", "1G");
        final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, Main::ledgerMaxBytesPerDay);
        assertTrue(refusal.getMessage().contains("synaxis.app.ledger-max-bytes-per-day=1G"), refusal.getMessage());
    }
}
