package io.github.bearisbug.synaxis.synx;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The overlay keys of a map shard ({@code docs/DESIGN-M3.md} {@code API-M3-006}, §9).
 */
public class OverlayOptionsTest {

    @Test
    public void theDefaultsAreTheTestTierCapAndTenSeconds() {
        assertEquals(new OverlayOptions(16_384, 10), OverlayOptions.from(Map.<String, String>of()::get));
    }

    @Test
    public void theKeysAreRead() {
        assertEquals(new OverlayOptions(24_576, 60), OverlayOptions.from(Map.of(
                "synaxis.overlay.cap", "24576", "synaxis.overlay.flush-interval-s", "60")::get));
    }

    @Test
    public void theOverlayDiskCapIsFourGibibytesUnlessGiven() {
        assertEquals(4_294_967_296L, OverlayOptions.from(Map.<String, String>of()::get).maxBytes());
        assertEquals(new OverlayOptions(16_384, 10, 65_536), OverlayOptions.from(Map.of(
                "synaxis.overlay.max-bytes", "65536")::get));
        assertEquals("Property 'synaxis.overlay.max-bytes' is below 1: 0", assertThrows(IllegalArgumentException.class,
                () -> OverlayOptions.from(Map.of("synaxis.overlay.max-bytes", "0")::get)).getMessage());
        assertEquals("Property 'synaxis.overlay.max-bytes' is not a number of bytes: 4G", assertThrows(
                IllegalArgumentException.class,
                () -> OverlayOptions.from(Map.of("synaxis.overlay.max-bytes", "4G")::get)).getMessage());
    }

    @Test
    public void aValueOutOfRangeOrNotAnIntegerIsRefused() {
        assertEquals("Property 'synaxis.overlay.flush-interval-s' is not from 1 to 60: 0", assertThrows(
                IllegalArgumentException.class,
                () -> OverlayOptions.from(Map.of("synaxis.overlay.flush-interval-s", "0")::get)).getMessage());
        assertEquals("Property 'synaxis.overlay.flush-interval-s' is not from 1 to 60: 61", assertThrows(
                IllegalArgumentException.class,
                () -> OverlayOptions.from(Map.of("synaxis.overlay.flush-interval-s", "61")::get)).getMessage());
        assertEquals("Property 'synaxis.overlay.cap' is not from 1 up: 0", assertThrows(IllegalArgumentException.class,
                () -> OverlayOptions.from(Map.of("synaxis.overlay.cap", "0")::get)).getMessage());
        assertEquals("Property 'synaxis.overlay.cap' is not an integer: 16k", assertThrows(IllegalArgumentException.class,
                () -> OverlayOptions.from(Map.of("synaxis.overlay.cap", "16k")::get)).getMessage());
    }
}
