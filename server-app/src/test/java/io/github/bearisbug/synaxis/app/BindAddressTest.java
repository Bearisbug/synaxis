package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.app.management.ManagementSettings;
import io.github.bearisbug.synaxis.app.metrics.HostMetrics;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two keys the host binds a port on take one address and one port and nothing else
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-015}, {@code API-PROD-022}, §19 the scenario of the
 * bind keys): a value that a URI parser reads as a loopback address with a user info in front must
 * not bind every interface, and every refusal names its key.
 */
class BindAddressTest {

    private static final List<String> KEYS = List.of(ManagementSettings.BIND_PROPERTY, HostMetrics.BIND_PROPERTY);

    @Test
    void anAddressAndAPortAreTakenAsWritten() {
        assertEquals(new InetSocketAddress(InetAddress.getLoopbackAddress(), 25585),
                BindAddress.parse(ManagementSettings.BIND_PROPERTY, "127.0.0.1:25585"));
        assertEquals(new InetSocketAddress(InetAddress.ofLiteral("10.0.0.5"), 0),
                BindAddress.parse(HostMetrics.BIND_PROPERTY, " 10.0.0.5:0 "), "blanks around the value");
        assertEquals(65_535, BindAddress.parse(HostMetrics.BIND_PROPERTY, "0.0.0.0:65535").getPort());
        assertEquals(new InetSocketAddress(InetAddress.ofLiteral("::1"), 9464),
                BindAddress.parse(HostMetrics.BIND_PROPERTY, "[::1]:9464"));
        assertEquals(new InetSocketAddress(InetAddress.ofLiteral("::"), 1),
                BindAddress.parse(HostMetrics.BIND_PROPERTY, "[::]:1"));
        final InetSocketAddress named = BindAddress.parse(ManagementSettings.BIND_PROPERTY, "localhost:25585");
        assertEquals("localhost", named.getHostString());
        assertTrue(named.getAddress().isLoopbackAddress(), named.toString());
    }

    @Test
    void anythingButHostAndPortIsRefusedWithTheKeyNamed() {
        final List<String> wrong = List.of(
                // What a URI parser reads past: user info, a query, a fragment, a path
                "127.0.0.1@0.0.0.0:25585", "user:secret@127.0.0.1:25585", "127.0.0.1:25585?x", "127.0.0.1:25585#y",
                "127.0.0.1:25585/", "127.0.0.1:25585/metrics", "ws://127.0.0.1:25585",
                // No port, no host, a port that is not a number
                "127.0.0.1", "127.0.0.1:", ":25585", "127.0.0.1:port", "127.0.0.1:+1", "127.0.0.1:123456",
                // An IPv6 address without brackets, with a zone, or not one at all
                "::1:25585", "[::1]", "[fe80::1%en0]:25585", "[1.2.3.4]:25585", "[gg::1]:25585",
                // An IPv4 address that is not four decimal parts from 0 to 255, a malformed host name
                "256.0.0.1:1", "999.1.1.1:1", "127.1:1", "010.0.0.1:1", "1.2.3:1", "-host:1", "host-:1",
                "a..b:1", "host.:1", "not a host:1", "a_b:1", "", " ");
        for (String key : KEYS) {
            for (String value : wrong) {
                final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                        () -> BindAddress.parse(key, value), key + "=" + value);
                assertTrue(refused.getMessage().startsWith(key + "=" + value + " is not host:port"), refused.getMessage());
            }
        }
    }

    @Test
    void aPortPastTheRangeIsRefusedWithTheKeyNamed() {
        for (String key : KEYS) {
            final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> BindAddress.parse(key, "127.0.0.1:70000"));
            assertEquals(key + "=127.0.0.1:70000: the port 70000 is not 0 to 65535", refused.getMessage());
        }
    }
}
