package io.github.bearisbug.synaxis.app;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        System.clearProperty("synaxis.entity-view-distance");
        System.clearProperty("synaxis.chunk-view-distance");
        System.clearProperty("synaxis.player.chunk-update-limiter-history-size");
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
}
