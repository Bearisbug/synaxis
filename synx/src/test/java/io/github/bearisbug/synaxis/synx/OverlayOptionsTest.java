package io.github.bearisbug.synaxis.synx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The two keys of the overlay store ({@code docs/DESIGN-M3.md} §9, API-M3-006 configuration keys). */
public class OverlayOptionsTest {

    @AfterEach
    public void clearProperties() {
        System.clearProperty(OverlayOptions.CAP_PROPERTY);
        System.clearProperty(OverlayOptions.FLUSH_INTERVAL_PROPERTY);
    }

    @Test
    public void withoutTheKeysTheDefaultsApply() {
        assertEquals("synaxis.overlay.cap", OverlayOptions.CAP_PROPERTY);
        assertEquals("synaxis.overlay.flush-interval-s", OverlayOptions.FLUSH_INTERVAL_PROPERTY);
        assertEquals(new OverlayOptions(16_384, 10), OverlayOptions.fromSystemProperties());
    }

    @Test
    public void theKeysAreRead() {
        System.setProperty(OverlayOptions.CAP_PROPERTY, "24576");
        System.setProperty(OverlayOptions.FLUSH_INTERVAL_PROPERTY, "60");
        assertEquals(new OverlayOptions(24_576, 60), OverlayOptions.fromSystemProperties());
        System.setProperty(OverlayOptions.CAP_PROPERTY, "1");
        System.setProperty(OverlayOptions.FLUSH_INTERVAL_PROPERTY, "1");
        assertEquals(new OverlayOptions(1, 1), OverlayOptions.fromSystemProperties());
    }

    @Test
    public void aValueThatIsNotAnIntegerIsRefusedWithItsKey() {
        assertRefused(OverlayOptions.CAP_PROPERTY, "16k", "Property 'synaxis.overlay.cap' is not an integer: \"16k\"");
        // A trailing blank from the config file is not taken away, as with the ServerConfig keys
        assertRefused(OverlayOptions.CAP_PROPERTY, "100 ", "Property 'synaxis.overlay.cap' is not an integer: \"100 \"");
        assertRefused(OverlayOptions.FLUSH_INTERVAL_PROPERTY, "",
                "Property 'synaxis.overlay.flush-interval-s' is not an integer: \"\"");
    }

    @Test
    public void aValueOutOfRangeIsRefusedWithItsKey() {
        assertRefused(OverlayOptions.CAP_PROPERTY, "0", "Property 'synaxis.overlay.cap' must be at least 1: 0");
        assertRefused(OverlayOptions.FLUSH_INTERVAL_PROPERTY, "0",
                "Property 'synaxis.overlay.flush-interval-s' must be between 1 and 60: 0");
        assertRefused(OverlayOptions.FLUSH_INTERVAL_PROPERTY, "61",
                "Property 'synaxis.overlay.flush-interval-s' must be between 1 and 60: 61");
    }

    @Test
    public void theConstructorHoldsTheSameRanges() {
        assertThrows(IllegalArgumentException.class, () -> new OverlayOptions(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new OverlayOptions(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OverlayOptions(1, 61));
    }

    private static void assertRefused(String key, String value, String message) {
        clear();
        System.setProperty(key, value);
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                OverlayOptions::fromSystemProperties);
        assertEquals(message, refused.getMessage());
        clear();
    }

    private static void clear() {
        System.clearProperty(OverlayOptions.CAP_PROPERTY);
        System.clearProperty(OverlayOptions.FLUSH_INTERVAL_PROPERTY);
    }
}
