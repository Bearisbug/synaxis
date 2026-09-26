package io.github.bearisbug.synaxis.synx;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code :synx} keys of a map shard ({@code docs/DESIGN-M3.md} {@code API-M3-006}, §9): read through
 * a lookup rather than the JVM properties, which every test of this JVM shares.
 */
public class SynxOptionsTest {

    @Test
    public void theDefaultsAreAWritableWorldAndA256MiBCache() {
        assertEquals(new SynxOptions(true, 268_435_456), SynxOptions.from(Map.<String, String>of()::get));
    }

    @Test
    public void theKeysAreRead() {
        assertEquals(new SynxOptions(false, 1), SynxOptions.from(Map.of(
                "synaxis.world-writable", "false", "synaxis.synx.section-cache-bytes", "1")::get));
    }

    /** As the boolean keys of ServerConfig: lower case true or false, nothing around it. */
    @Test
    public void aBooleanOtherThanLowerCaseTrueOrFalseIsRefused() {
        for (String value : new String[]{"TRUE", "true ", "yes", ""}) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> SynxOptions.from(Map.of("synaxis.world-writable", value)::get));
            assertEquals("Property 'synaxis.world-writable' is not true or false: \"" + value + "\"", refused.getMessage());
        }
    }

    @Test
    public void aCacheOfNoByteOrNoNumberIsRefused() {
        for (String value : new String[]{"0", "-1", "256MiB"}) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> SynxOptions.from(Map.of("synaxis.synx.section-cache-bytes", value)::get));
            assertTrue(refused.getMessage().startsWith("Property 'synaxis.synx.section-cache-bytes'"), refused.getMessage());
        }
    }
}
