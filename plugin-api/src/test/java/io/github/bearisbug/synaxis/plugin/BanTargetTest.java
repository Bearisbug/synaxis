package io.github.bearisbug.synaxis.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two targets of a ban ({@code docs/DESIGN-PROD.md} API-PROD-009, §9 {@code ban.subject_ip}):
 * a network is a literal address with a prefix length from /8 to /32 for IPv4 and from /32 to /128
 * for IPv6, kept with its host bits cleared, and covers exactly the addresses under that prefix.
 */
class BanTargetTest {

    private static final UUID SOMEONE = UUID.randomUUID();

    @Test
    void aNetworkIsKeptWithItsHostBitsCleared() {
        assertEquals("10.20.0.0/16", new BanTarget.ByNetwork("10.20.30.40/16").cidr());
        assertEquals("203.0.113.7/32", new BanTarget.ByNetwork("203.0.113.7").cidr(), "one address without a prefix");
        assertEquals("10.0.0.0/8", new BanTarget.ByNetwork("10.255.255.255/8").cidr());
        assertEquals("203.0.112.0/23", new BanTarget.ByNetwork("203.0.113.1/23").cidr(), "a prefix inside a byte");
        assertEquals("2001:db8:0:0:0:0:0:0/32", new BanTarget.ByNetwork("2001:db8:ffff::1/32").cidr());
        assertEquals("2001:db8:0:0:0:0:0:1/128", new BanTarget.ByNetwork("2001:db8::1").cidr());
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.org", "10.0.0.0/7", "10.0.0.0/33", "2001:db8::/31", "2001:db8::/129",
            "10.0.0/8", "10.0.0.0/x", "", "10.0.0.0/"})
    void anythingElseIsRefused(String cidr) {
        assertThrows(IllegalArgumentException.class, () -> new BanTarget.ByNetwork(cidr));
    }

    @Test
    void aNetworkCoversTheAddressesUnderItsPrefixOnly() throws Exception {
        final BanTarget network = new BanTarget.ByNetwork("203.0.112.0/23");
        assertTrue(network.covers(SOMEONE, InetAddress.ofLiteral("203.0.112.0")));
        assertTrue(network.covers(SOMEONE, InetAddress.ofLiteral("203.0.113.255")));
        assertFalse(network.covers(SOMEONE, InetAddress.ofLiteral("203.0.114.0")));
        assertFalse(network.covers(SOMEONE, InetAddress.ofLiteral("203.0.111.255")));
        assertFalse(network.covers(SOMEONE, null), "no address, nothing to cover");
        assertFalse(network.covers(SOMEONE, InetAddress.ofLiteral("2001:db8::1")), "another family");
        final BanTarget v6 = new BanTarget.ByNetwork("2001:db8::/32");
        assertTrue(v6.covers(SOMEONE, InetAddress.ofLiteral("2001:db8:abcd::1")));
        assertFalse(v6.covers(SOMEONE, InetAddress.ofLiteral("2001:db9::1")));
    }

    @Test
    void anAccountCoversItselfWhereverItConnectsFrom() {
        final BanTarget account = new BanTarget.ByUuid(SOMEONE);
        assertTrue(account.covers(SOMEONE, null));
        assertTrue(account.covers(SOMEONE, InetAddress.getLoopbackAddress()));
        assertFalse(account.covers(UUID.randomUUID(), InetAddress.getLoopbackAddress()));
    }
}
