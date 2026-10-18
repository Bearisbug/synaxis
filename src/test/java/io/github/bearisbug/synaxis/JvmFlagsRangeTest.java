package io.github.bearisbug.synaxis;

import io.github.bearisbug.synaxis.timer.Tick;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JVM-wide integer keys with a range ({@code docs/DESIGN-PROD.md} {@code API-PROD-023}): a value
 * out of it fails the class initialization of {@link JvmFlags}, naming the key and the range, at the
 * start rather than on the first packet that reaches the limit. The class is initialized once per
 * JVM, so each value is tried in a JVM of its own, running {@link #main}.
 */
public class JvmFlagsRangeTest {

    @Test
    public void theDefaultsAreInTheirRanges() {
        assertEquals(20, JvmFlags.SERVER_TICKS_PER_SECOND, "the test JVM is given no synaxis.tps");
        assertEquals(JvmFlags.SERVER_TICKS_PER_SECOND, Tick.SERVER_TICKS.getTicksPerSecond());
        assertEquals(2_097_151, JvmFlags.MAX_PACKET_SIZE);
        assertEquals(8_192, JvmFlags.MAX_PACKET_SIZE_PRE_AUTH);
    }

    @Test
    public void aValueOutOfItsRangeRefusesTheStartWithTheKeyAndTheRange() throws Exception {
        assertRefused("-Dsynaxis.max-packet-size=4194304",
                "Property 'synaxis.max-packet-size' value must be in range [16383..2097151] but was 4194304");
        assertRefused("-Dsynaxis.max-packet-size=8192",
                "Property 'synaxis.max-packet-size' value must be in range [16383..2097151] but was 8192");
        assertRefused("-Dsynaxis.max-packet-size-pre-auth=4194304",
                "Property 'synaxis.max-packet-size-pre-auth' value must be in range [1024..2097151] but was 4194304");
        assertRefused("-Dsynaxis.tps=0", "Property 'synaxis.tps' value must be in range [1..1000] but was 0");
        assertRefused("-Dsynaxis.tps=30", "Property 'synaxis.tps' value must divide 1000");
    }

    /** Initializes {@link JvmFlags} and prints what came of it. */
    public static void main(String[] args) {
        try {
            System.out.println("initialized " + JvmFlags.describe());
        } catch (ExceptionInInitializerError e) {
            System.out.println("refused " + e.getCause().getMessage());
        }
    }

    private static void assertRefused(String property, String message) throws IOException, InterruptedException {
        final Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        final Process child = new ProcessBuilder(java.toString(), "-Xmx64m", property,
                "-cp", System.getProperty("java.class.path"), JvmFlagsRangeTest.class.getName())
                .redirectErrorStream(true)
                .start();
        final String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(60, TimeUnit.SECONDS), "the child JVM did not end");
        assertTrue(output.contains("refused " + message), property + ": " + output);
    }
}
