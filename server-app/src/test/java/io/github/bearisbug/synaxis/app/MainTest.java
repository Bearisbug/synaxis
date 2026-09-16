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
