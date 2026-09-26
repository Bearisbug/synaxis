package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.app.metrics.HostMetrics;
import io.github.bearisbug.synaxis.app.store.PlayerStore;
import io.github.bearisbug.synaxis.app.store.RedisLink;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The addresses the host prints of its bind keys, in the refusal, the self-check and the ready
 * lines, are written the way the keys read them ({@code docs/DESIGN-PROD.md} {@code API-PROD-022}
 * transport, {@code API-PROD-015}, §19 the scenario of IPv6 in the log): an IPv6 address in
 * brackets, so that it stays apart from its port and the value read back is the same address.
 */
class BindAddressFormatTest {

    @Test
    void anAddressIsWrittenTheWayTheKeysReadIt() {
        for (String value : List.of("127.0.0.1:25585", "0.0.0.0:0", "localhost:9464", "[::1]:9464", "[::]:1",
                "[fe80::1]:25585")) {
            final InetSocketAddress address = BindAddress.parse(HostMetrics.BIND_PROPERTY, value);
            assertEquals(address, BindAddress.parse(HostMetrics.BIND_PROPERTY, BindAddress.format(address)), value);
        }
        assertEquals("[0:0:0:0:0:0:0:1]:9464", BindAddress.format(BindAddress.parse(HostMetrics.BIND_PROPERTY, "[::1]:9464")));
        assertEquals("127.0.0.1:25585", BindAddress.format(BindAddress.parse(HostMetrics.BIND_PROPERTY, "127.0.0.1:25585")));
        assertEquals("localhost:9464", BindAddress.format(BindAddress.parse(HostMetrics.BIND_PROPERTY, "localhost:9464")));
    }

    /** The refusal and the ready line of the metrics endpoint on IPv6: brackets, the ready line a URL. */
    @Test
    void theMetricsLinesWriteAnIpv6AddressInBrackets() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).build());
        final InetAddress loopback6 = InetAddress.ofLiteral("::1");
        try (ServerSocket taken = new ServerSocket(0, 0, loopback6)) {
            // Closed by serveMetrics when it refuses
            final HostMetrics refused = HostMetrics.register(process, new HostMetrics.Sources(() -> 0,
                    PlayerStore.inMemory(process, (_, _) -> {
                    }), null, null, RedisLink.disabled("ipv6")));
            final IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> Main.serveMetrics(refused, new InetSocketAddress(loopback6, taken.getLocalPort())));
            assertTrue(refusal.getMessage().startsWith(HostMetrics.BIND_PROPERTY + "=[0:0:0:0:0:0:0:1]:"
                    + taken.getLocalPort() + " cannot be bound: "), refusal.getMessage());
        }
        try (LogCapture log = LogCapture.of(HostMetrics.class);
             HostMetrics served = HostMetrics.register(process, new HostMetrics.Sources(() -> 0,
                     PlayerStore.inMemory(process, (_, _) -> {
                     }), null, null, RedisLink.disabled("ipv6")))) {
            final InetSocketAddress bound = served.serve(new InetSocketAddress(loopback6, 0));
            assertEquals(List.of("Metrics endpoint listening on http://[0:0:0:0:0:0:0:1]:" + bound.getPort() + "/metrics"),
                    log.messagesAt(Level.INFO));
        }
    }
}
