package io.github.bearisbug.synaxis.network.socket;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.status.StatusRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.status.ResponsePacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The accept loop outlives a connection that fails to be set up with something else than an
 * {@link IOException}: the failure is reported, the gate gets its ticket back and the next client is
 * served. The failure is made the way the engine would hit it, inside the construction of the
 * connection: the process the server is given fails once there.
 */
public class AcceptLoopSocketTest {

    @Test
    @Timeout(60)
    public void aConnectionThatFailsToBeSetUpLeavesTheAcceptLoopServingTheNextOne() throws Exception {
        final ServerProcess real = ServerProcess.of(ServerConfig.builder().insideTest(true).shutdownOnSignal(false).build());
        final List<Throwable> reported = new CopyOnWriteArrayList<>();
        real.exception().setExceptionHandler(reported::add);
        final IllegalStateException failure = new IllegalStateException("setting the connection up failed");
        final AtomicBoolean failed = new AtomicBoolean();
        final ServerProcess failingOnce = (ServerProcess) Proxy.newProxyInstance(ServerProcess.class.getClassLoader(),
                new Class<?>[]{ServerProcess.class}, (_, method, args) -> {
                    // The connection is built on the accept thread and asks for the event handler
                    if (method.getName().equals("eventHandler") && Thread.currentThread().getName().equals("Syn-Socket-Server")
                            && failed.compareAndSet(false, true)) throw failure;
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        final Server server = new Server(failingOnce, PacketVanilla.CLIENT_PACKET_PARSER);
        server.init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        server.start();
        try {
            try (SocketChannel first = connect(server)) {
                final InputStream in = first.socket().getInputStream();
                assertEquals(-1, in.read(), "the connection that failed is closed");
            }
            await(() -> real.connection().gate().connections() == 0, "the failed connection gave its ticket back");
            assertEquals(List.of(failure), reported);

            try (SocketChannel second = connect(server)) {
                send(second, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                        "localhost", server.getPort(), ClientHandshakePacket.Intent.STATUS));
                send(second, ConnectionState.STATUS, new StatusRequestPacket());
                assertInstanceOf(ResponsePacket.class, read(second, real), "the next client is answered");
            }
        } finally {
            server.stop();
        }
    }

    private static SocketChannel connect(Server server) throws IOException {
        final SocketChannel channel = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()));
        channel.socket().setSoTimeout(5_000);
        return channel;
    }

    private static void send(SocketChannel channel, ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
        while (!framed.writeChannel(channel)) {
            // A blocking channel writes everything, the loop only documents the contract
        }
    }

    private static ServerPacket read(SocketChannel channel, ServerProcess process) throws Exception {
        final InputStream in = channel.socket().getInputStream();
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int length = 0;
        for (int shift = 0; ; shift += 7) {
            final int b = in.read();
            if (b < 0) throw new AssertionError("closed before the length of the answer");
            frame.write(b);
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
        }
        frame.write(in.readNBytes(length));
        final byte[] bytes = frame.toByteArray();
        final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, process.registries());
        if (!(PacketReading.readServers(buffer, ConnectionState.STATUS, false)
                instanceof PacketReading.Result.Success<ServerPacket> success))
            throw new AssertionError("not a whole packet: " + bytes.length + " bytes");
        return success.packets().getFirst().packet();
    }

    private static void await(BooleanSupplier condition, String what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("not within 10 s: " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }
}
