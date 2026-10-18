package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.exception.ExceptionManager;
import io.github.bearisbug.synaxis.listener.preplay.LoginListener;
import io.github.bearisbug.synaxis.network.ConnectionGate;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginPluginResponsePacket;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.network.socket.Server;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the connection gate ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-001}) and the deprecated
 * {@code synaxis.socket-timeout} ({@code REQ-PROD-043}) write to the host log, read through the
 * host's logback.
 */
public class ConnectionGateLogTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    /**
     * Rejections are never a line each, their number is up to the peers. They are written as one
     * line per 10 seconds with the count of every reason, and not at all when there were none.
     */
    @Test
    public void rejectionsAreOneLinePerTenSeconds() {
        final ConnectionGate gate = new ConnectionGate(ServerConfig.builder()
                .maxConnections(1).maxConnectionsPerIp(1).maxPlayers(1).build());
        final SocketAddress peer = new InetSocketAddress(InetAddress.getLoopbackAddress(), 40000);
        try (LogCapture log = LogCapture.of(ConnectionGate.class)) {
            final long start = 5 * SECOND;
            gate.tick(start);

            final ConnectionGate.Ticket ticket = gate.admit(peer);
            for (int i = 0; i < 100; i++) assertNull(gate.admit(peer)); // L0
            for (int i = 0; i < 3; i++) gate.handshake(ticket, peer, Intent.LOGIN);
            for (int i = 0; i < 7; i++) gate.handshake(ticket, peer, Intent.LOGIN); // L2
            assertTrue(gate.takeSeat(ticket));
            for (int i = 0; i < 4; i++) gate.takeSeat(gate.untracked()); // L3
            gate.handshakeTimedOut();
            for (int i = 0; i < 2; i++) gate.proxyHeaderRejected();

            gate.tick(start + 10 * SECOND - 1);
            assertEquals(List.of(), log.messagesAt(Level.WARN), "nothing before the 10 s are over");

            gate.tick(start + 10 * SECOND);
            assertEquals(List.of("Connection gate rejected 114 connections in the last 10 s: too_many=0 "
                    + "throttled=7 full=4 closed_at_accept=100 handshake_timeout=1 proxy_header=2"),
                    log.messagesAt(Level.WARN));

            gate.tick(start + 20 * SECOND);
            assertEquals(1, log.messagesAt(Level.WARN).size(), "a window without rejections writes nothing");
        }
    }

    /**
     * A connection the gate turns away at the handshake is closed without the line every other
     * connection gets when it closes, otherwise the log would still grow with the peers' rate.
     */
    @Test
    public void aConnectionTurnedAwayAtTheHandshakeWritesNoLine() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true).statusBurstPerIp(1).build());
        final Server server = process.server();
        server.init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
        final ConnectionGate gate = process.connection().gate();
        try (LogCapture log = LogCapture.of(PlayerConnection.class)) {
            final List<SocketChannel> peers = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                final SocketChannel peer = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()));
                final NetworkBuffer handshake = PacketWriting.allocateTrimmedPacket(ConnectionState.HANDSHAKE,
                        new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION, "localhost", server.getPort(), Intent.STATUS),
                        null, 0);
                while (!handshake.writeChannel(peer)) {
                    // A blocking channel writes everything, the loop only documents the contract
                }
                peers.add(peer);
            }
            for (int i = 0; i < 200 && gate.rejected(ConnectionGate.Reason.THROTTLED) < 5; i++) Thread.sleep(25);
            assertEquals(5, gate.rejected(ConnectionGate.Reason.THROTTLED));
            for (SocketChannel peer : peers) peer.close();
            for (int i = 0; i < 200 && gate.connections() > 0; i++) Thread.sleep(25);
            assertEquals(0, gate.connections(), "every connection is closed");

            final List<String> closed = log.messagesAt(Level.INFO).stream()
                    .filter(line -> line.startsWith("Connection closed")).toList();
            assertEquals(1, closed.size(), "only the connection the gate let through: " + closed);
        } finally {
            process.stop();
        }
    }

    /**
     * An oversized server address takes a connection no further than the gate: twenty such handshakes
     * from one address, each with a login plugin response nobody asked for behind it, are all counted
     * by the gate, which turns away the 17 past the login bucket of 3, and none gets to the login
     * listener, which would report that response to the exception handler ({@code docs/TEST.md}
     * TC-PROD-579).
     */
    @Test
    public void oversizedServerAddressesAreCountedByTheGateAndGoNoFurther() throws Exception {
        // Room for the twenty at once, and a token per 1,000 s: neither L1 nor the wall clock changes
        // how many reach the login bucket
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true)
                .handshakeTimeout(60_000).maxConnectionsPerIp(32).loginRatePerIp(0.001).build());
        final List<Throwable> handled = new CopyOnWriteArrayList<>();
        process.exception().setExceptionHandler(handled::add);
        try {
            sendTwenty(process, "a".repeat(256));
            assertEquals(17, process.connection().gate().rejected(ConnectionGate.Reason.THROTTLED),
                    "oversized handshakes the login bucket turned away");
            assertEquals(List.of(), handled, "reports of the exception handler");
        } finally {
            process.stop();
        }
    }

    /**
     * A login plugin response nobody asked for is reported to the exception handler, whose lines
     * are bounded, and gets no line of the login listener's own: the peer is not authenticated and
     * decides how many connections send one ({@code docs/TEST.md} TC-PROD-579).
     */
    @Test
    public void aLoginPluginResponseNobodyAskedForIsReportedByTheExceptionHandlerAlone() throws Exception {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder().insideTest(true)
                .handshakeTimeout(60_000).connectionGateTrustedNetworks("127.0.0.0/8").build());
        try (LogCapture listener = LogCapture.of(LoginListener.class);
             LogCapture handler = LogCapture.of(ExceptionManager.class)) {
            sendTwenty(process, "localhost");
            assertEquals(List.of(), listener.messagesAt(Level.ERROR), "the login listener wrote lines of its own");
            assertTrue(handler.messagesAt(Level.ERROR).size() <= 1,
                    "the exception handler writes one line per 10 s: " + handler.messagesAt(Level.ERROR));
        } finally {
            process.stop();
        }
    }

    /**
     * Opens twenty connections that each send a login handshake with {@code serverAddress} and a
     * login plugin response nobody asked for, and waits until the server closed every one.
     */
    private static void sendTwenty(ServerProcess process, String serverAddress) throws Exception {
        final Server server = process.server();
        server.init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
        final List<SocketChannel> peers = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                final SocketChannel peer = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()));
                peers.add(peer);
                for (NetworkBuffer frame : List.of(
                        PacketWriting.allocateTrimmedPacket(ConnectionState.HANDSHAKE, new ClientHandshakePacket(
                                MinecraftServer.PROTOCOL_VERSION, serverAddress, server.getPort(), Intent.LOGIN), null, 0),
                        PacketWriting.allocateTrimmedPacket(ConnectionState.LOGIN,
                                new ClientLoginPluginResponsePacket(0, null), null, 0))) {
                    while (!frame.writeChannel(peer)) {
                        // A blocking channel writes everything, the loop only documents the contract
                    }
                }
            }
            for (SocketChannel peer : peers) {
                peer.socket().setSoTimeout(10_000);
                final InputStream in = peer.socket().getInputStream();
                try {
                    while (in.read() >= 0) {
                        // Whatever the server answers, until it closes
                    }
                } catch (IOException _) {
                    // Reset by the server, as good as closed
                }
            }
        } finally {
            for (SocketChannel peer : peers) peer.close();
        }
    }

    /** {@code REQ-PROD-043}: the key still parses, but whoever sets it is told it does nothing. */
    @Test
    public void settingTheSocketTimeoutWarnsThatItIsDeprecated() {
        try (LogCapture log = LogCapture.of(ServerConfig.class)) {
            var _ = ServerConfig.fromSystemProperties();
            assertEquals(List.of(), log.messagesAt(Level.WARN), "nothing while the key is not set");

            System.setProperty("synaxis.socket-timeout", "30000");
            try {
                var _ = ServerConfig.fromSystemProperties();
            } finally {
                System.clearProperty("synaxis.socket-timeout");
            }
            final List<String> warnings = log.messagesAt(Level.WARN);
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.getFirst().startsWith("synaxis.socket-timeout is deprecated and has no effect"),
                    warnings.getFirst());
        }
    }

    /** Event nodes have one parent at most: the key is still taken, whoever sets it is told it does nothing. */
    @Test
    public void settingTheEventNodeParentsKeyWarnsThatItIsDeprecated() {
        try (LogCapture log = LogCapture.of(ServerConfig.class)) {
            System.setProperty("synaxis.event.multiple-parents", "true");
            try {
                var _ = ServerConfig.fromSystemProperties();
            } finally {
                System.clearProperty("synaxis.event.multiple-parents");
            }
            assertEquals(List.of("synaxis.event.multiple-parents is deprecated and has no effect, "
                    + "an event node has at most one parent"), log.messagesAt(Level.WARN));
        }
    }
}
