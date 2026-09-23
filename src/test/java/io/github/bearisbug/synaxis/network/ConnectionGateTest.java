package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counts of the connection gate ({@code docs/DESIGN-PROD.md} {@code API-PROD-003}) on a clock
 * the test moves by hand. {@code ConnectionGateSocketTest} drives the same gate through a real
 * server.
 */
public class ConnectionGateTest {

    private static final SocketAddress PEER = address("203.0.113.7");
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final AtomicLong clock = new AtomicLong(1_000 * SECOND);

    private ConnectionGate gate(ServerConfig.Builder config) {
        return new ConnectionGate(config.build(), clock::get);
    }

    private static SocketAddress address(String literal) {
        return new InetSocketAddress(literal, 25565);
    }

    @Test
    public void theEngineDefaultsOfTheGate() {
        final ServerConfig config = ServerConfig.defaults();
        assertEquals(1_024, config.maxConnections());
        assertEquals(100, config.acceptBackoff());
        assertEquals(16, config.maxConnectionsPerIp());
        assertEquals(5_000, config.handshakeTimeout());
        assertEquals(0.5, config.loginRatePerIp());
        assertEquals(3, config.loginBurstPerIp());
        assertEquals(1, config.statusRatePerIp());
        assertEquals(10, config.statusBurstPerIp());
        assertEquals(2, config.transferRatePerIp());
        assertEquals(10, config.transferBurstPerIp());
        assertEquals("", config.connectionGateTrustedNetworks());
        assertEquals(0, config.maxPlayers(), "no seat limit unless a host sets one");
        assertEquals(0, config.maxJoinsPerTick(), "no join limit unless a host sets one");
    }

    /** L2: three logins at once, the fourth waits for the bucket, which refills one every 2 s. */
    @Test
    public void theFourthLoginOfAnAddressIsThrottledUntilTheBucketRefills() {
        final ConnectionGate gate = gate(ServerConfig.builder());
        final ConnectionGate.Ticket ticket = gate.untracked();
        for (int i = 0; i < 3; i++)
            assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, PEER, Intent.LOGIN), "login " + (i + 1));
        assertEquals(ConnectionGate.Verdict.THROTTLED, gate.handshake(ticket, PEER, Intent.LOGIN), "login 4");

        clock.addAndGet(SECOND * 1_999 / 1_000);
        assertEquals(ConnectionGate.Verdict.THROTTLED, gate.handshake(ticket, PEER, Intent.LOGIN),
                "1.999 s later the bucket is still short of a token");
        clock.addAndGet(SECOND * 2 / 1_000);
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, PEER, Intent.LOGIN),
                "2 s later the bucket has one token again");
        assertEquals(2, gate.rejected(ConnectionGate.Reason.THROTTLED));
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, address("203.0.113.8"), Intent.LOGIN),
                "another address has a bucket of its own");
    }

    /** L2: status and transfer have buckets of their own, neither takes from the login bucket. */
    @Test
    public void statusAndTransferCountOnTheirOwnBuckets() {
        final ConnectionGate gate = gate(ServerConfig.builder());
        final ConnectionGate.Ticket ticket = gate.untracked();
        for (int i = 0; i < 10; i++) {
            assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, PEER, Intent.STATUS));
            assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, PEER, Intent.TRANSFER));
        }
        assertEquals(ConnectionGate.Verdict.THROTTLED, gate.handshake(ticket, PEER, Intent.STATUS), "status 11");
        assertEquals(ConnectionGate.Verdict.THROTTLED, gate.handshake(ticket, PEER, Intent.TRANSFER), "transfer 11");
        for (int i = 0; i < 3; i++)
            assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, PEER, Intent.LOGIN),
                    "the login bucket is still full");

        clock.addAndGet(SECOND + 1);
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, PEER, Intent.STATUS), "1/s");
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, PEER, Intent.TRANSFER), "2/s");
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, PEER, Intent.TRANSFER), "2/s");
        assertEquals(ConnectionGate.Verdict.THROTTLED, gate.handshake(ticket, PEER, Intent.TRANSFER));
    }

    /** L0: the socket past the limit is turned away, and one closing makes room again. */
    @Test
    public void theSocketPastTheProcessLimitIsTurnedAwayAtAccept() {
        final ConnectionGate gate = gate(ServerConfig.builder().maxConnections(2));
        final ConnectionGate.Ticket first = gate.admit(address("203.0.113.1"));
        assertNotNull(first);
        assertNotNull(gate.admit(address("203.0.113.2")));
        assertNull(gate.admit(address("203.0.113.3")), "the third socket is over the limit");
        assertEquals(1, gate.rejected(ConnectionGate.Reason.CLOSED_AT_ACCEPT));
        assertEquals(2, gate.connections());

        first.release();
        first.release(); // once only
        assertEquals(1, gate.connections());
        assertNotNull(gate.admit(address("203.0.113.3")), "a socket closing makes room");
    }

    /** L1 without the PROXY protocol: counted at accept, IPv4 per address, IPv6 per /64. */
    @Test
    public void anAddressPastItsLimitIsTurnedAwayAtAccept() {
        final ConnectionGate gate = gate(ServerConfig.builder().maxConnectionsPerIp(2));
        final ConnectionGate.Ticket first = gate.admit(PEER);
        assertNotNull(first);
        assertNotNull(gate.admit(PEER));
        assertNull(gate.admit(PEER), "the third connection of the address");
        assertEquals(1, gate.rejected(ConnectionGate.Reason.TOO_MANY));
        assertEquals(2, gate.connections(), "a socket turned away by L1 is not left in L0");
        assertNotNull(gate.admit(address("203.0.113.8")), "another address");

        first.release();
        assertNotNull(gate.admit(PEER), "a connection closing makes room for its address");

        assertNotNull(gate.admit(address("2001:db8:0:1::1")));
        assertNotNull(gate.admit(address("2001:db8:0:1::2")));
        assertNull(gate.admit(address("2001:db8:0:1:ffff::3")), "the same /64 is one address");
        assertNotNull(gate.admit(address("2001:db8:0:2::1")), "another /64");
    }

    /**
     * L1 with the PROXY protocol: the address is only known at the handshake, so nothing is counted
     * at accept, and a handshake turned away by L1 leaves the bucket alone.
     */
    @Test
    public void withTheProxyProtocolTheAddressIsCountedAtTheHandshake() {
        final ConnectionGate gate = gate(ServerConfig.builder().proxyProtocol(true).proxyProtocolRequired(true)
                .proxyProtocolTrustedNetworks("10.0.0.0/8").maxConnectionsPerIp(2));
        final SocketAddress proxy = address("10.0.0.1");
        final List<ConnectionGate.Ticket> tickets = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final ConnectionGate.Ticket ticket = gate.admit(proxy);
            assertNotNull(ticket, "every connection of the proxy is accepted");
            tickets.add(ticket);
        }
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(tickets.get(0), PEER, Intent.LOGIN));
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(tickets.get(1), PEER, Intent.LOGIN));
        assertEquals(ConnectionGate.Verdict.TOO_MANY, gate.handshake(tickets.get(2), PEER, Intent.LOGIN));
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(tickets.get(3), address("203.0.113.8"), Intent.LOGIN));

        tickets.getFirst().release();
        assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(tickets.get(4), PEER, Intent.LOGIN),
                "the third token of the bucket was not spent on the connection L1 turned away");
    }

    @Test
    public void trustedNetworksSkipTheAddressLimitAndTheBuckets() {
        final ConnectionGate gate = gate(ServerConfig.builder().maxConnectionsPerIp(1)
                .connectionGateTrustedNetworks("127.0.0.0/8, 2001:db8::/32"));
        for (int i = 0; i < 20; i++) {
            final ConnectionGate.Ticket ticket = gate.admit(address("127.0.0.1"));
            assertNotNull(ticket);
            assertEquals(ConnectionGate.Verdict.PASS, gate.handshake(ticket, address("127.0.0.1"), Intent.LOGIN));
            assertNotNull(gate.admit(address("2001:db8:1::1")));
        }
        assertNotNull(gate.admit(PEER));
        assertNull(gate.admit(PEER), "an address outside the trusted networks is still limited");
    }

    @Test
    public void aTrustedNetworkIsALiteralWithAnOptionalPrefixLength() {
        assertEquals(3, ConnectionGate.parseNetworks(" 10.0.0.0/8,192.168.1.7 ,fd00::/8,").size());
        for (String bad : List.of("localhost", "10.0.0.0/33", "fd00::/129", "10.0.0.0/x", "10.0.0.0/-1"))
            assertThrows(IllegalArgumentException.class, () -> ConnectionGate.parseNetworks(bad), bad);
        assertThrows(IllegalArgumentException.class,
                () -> ServerConfig.builder().connectionGateTrustedNetworks("example.com").build(),
                "a host name is refused when the configuration is built, not resolved");
    }

    @Test
    public void invalidGateValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().maxConnections(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().maxConnectionsPerIp(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().handshakeTimeout(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().acceptBackoff(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().maxPlayers(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().maxJoinsPerTick(-1).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().loginRatePerIp(0).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().statusRatePerIp(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.builder().transferBurstPerIp(0).build());
    }

    /** L3: the seats are what login starts hold, whatever state their connection is in. */
    @Test
    public void aSeatIsHeldUntilItsConnectionCloses() {
        final ConnectionGate gate = gate(ServerConfig.builder().maxPlayers(2));
        final ConnectionGate.Ticket first = gate.untracked();
        assertTrue(gate.takeSeat(first));
        assertTrue(gate.takeSeat(first), "taking the seat again takes nothing more");
        assertTrue(gate.takeSeat(gate.untracked()));
        assertFalse(gate.takeSeat(gate.untracked()), "the third login start finds the process full");
        assertEquals(1, gate.rejected(ConnectionGate.Reason.FULL));
        assertEquals(2, gate.seats());

        first.release();
        assertEquals(1, gate.seats());
        assertTrue(gate.takeSeat(gate.untracked()));

        // A connection that is already closed never gets a seat it would not give back
        final ConnectionGate.Ticket closed = gate.untracked();
        closed.release();
        final int seats = gate.seats();
        assertTrue(gate.takeSeat(closed));
        assertEquals(seats, gate.seats());
        assertFalse(closed.holdsSeat());
    }

    /** L3 counts seats, not players in play: twenty login starts at once never take six seats of five. */
    @Test
    public void concurrentLoginStartsNeverTakeMoreSeatsThanThereAre() throws Exception {
        final ConnectionGate gate = gate(ServerConfig.builder().maxPlayers(5));
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger seated = new AtomicInteger();
        final List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final ConnectionGate.Ticket ticket = gate.untracked();
            threads.add(Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                if (gate.takeSeat(ticket)) seated.incrementAndGet();
            }));
        }
        start.countDown();
        for (Thread thread : threads) thread.join(10_000);
        assertEquals(5, seated.get());
        assertEquals(5, gate.seats());
        assertEquals(15, gate.rejected(ConnectionGate.Reason.FULL));
    }

    /** The bucket table is keyed on what the peer picks: bounded, and emptied of full buckets. */
    @Test
    public void theBucketTableIsBoundedAndSweptOfFullBuckets() {
        final ConnectionGate gate = gate(ServerConfig.builder());
        final ConnectionGate.Ticket ticket = gate.untracked();
        for (int i = 0; i <= ConnectionGate.MAX_BUCKET_KEYS; i++) {
            final String literal = "10." + (i >> 16 & 0xFF) + "." + (i >> 8 & 0xFF) + "." + (i & 0xFF);
            gate.handshake(ticket, address(literal), Intent.LOGIN);
        }
        assertEquals(ConnectionGate.MAX_BUCKET_KEYS, gate.bucketKeys(), "the least recently used key is dropped");

        clock.addAndGet(59 * SECOND);
        gate.tick(clock.get());
        assertEquals(ConnectionGate.MAX_BUCKET_KEYS, gate.bucketKeys(), "the sweep runs every 60 s");
        clock.addAndGet(SECOND);
        gate.tick(clock.get());
        assertEquals(0, gate.bucketKeys(), "every bucket is full again after 60 s and is dropped");
    }
}
