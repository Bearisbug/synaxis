package io.github.bearisbug.synaxis.network.socket;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.network.packet.PacketParser;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.player.PlayerSocketConnection;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.UnknownNullability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class Server {
    private static final Logger LOGGER = LoggerFactory.getLogger(Server.class);

    private volatile boolean stop;

    private final ServerProcess process;
    private final PacketParser.Client packetParser;

    private @UnknownNullability ServerSocketChannel serverSocket;
    private @UnknownNullability SocketAddress socketAddress;
    private @UnknownNullability String address;
    private int port;

    public Server(ServerProcess process, PacketParser.Client packetParser) {
        this.process = process;
        this.packetParser = packetParser;
    }

    @ApiStatus.Internal
    public void init(SocketAddress address) throws IOException {
        ProtocolFamily family;
        switch (address) {
            case InetSocketAddress inetSocketAddress -> {
                this.address = inetSocketAddress.getHostString();
                this.port = inetSocketAddress.getPort();
                family = inetSocketAddress.getAddress().getAddress().length == 4 ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6;
            }
            case UnixDomainSocketAddress unixDomainSocketAddress -> {
                this.address = "unix://" + unixDomainSocketAddress.getPath();
                this.port = 0;
                family = StandardProtocolFamily.UNIX;
            }
            default ->
                    throw new IllegalArgumentException("Address must be an InetSocketAddress or a UnixDomainSocketAddress");
        }

        ServerSocketChannel server = ServerSocketChannel.open(family);
        server.bind(address);
        this.serverSocket = server;
        this.socketAddress = address;

        if (address instanceof InetSocketAddress && port == 0) {
            port = server.socket().getLocalPort();
        }
    }

    @ApiStatus.Internal
    public void start() {
        Thread.ofVirtual().name("Syn-Socket-Server").start(() -> {
            // Use named thread builders for logging
            var readBuilder = Thread.ofVirtual().name("Syn-Socket-Reader-", 0);
            var writeBuilder = Thread.ofVirtual().name("Syn-Socket-Writer-", 0);
            var loginTimeoutBuilder = Thread.ofVirtual().name("Syn-Login-Timeout-", 0);
            final var serverSocket = Objects.requireNonNull(this.serverSocket, "Not bound did you forget to call #init?");
            while (!stop) {
                final SocketChannel client;
                try {
                    client = serverSocket.accept();
                } catch (ClosedChannelException _) {
                    break; // We are exiting, bye bye!
                } catch (IOException e) {
                    if (!process.config().suppressConnectionAcceptErrors())
                        process.exception().handleException(e);
                    continue;
                }

                AtomicReference<@UnknownNullability PlayerSocketConnection> reference = new AtomicReference<>(null);
                try {
                    configureSocket(client);
                    Thread readThread = readBuilder.unstarted(() -> playerReadLoop(reference.get()));
                    Thread writeThread = writeBuilder.unstarted(() -> playerWriteLoop(reference.get()));
                    PlayerSocketConnection connection = new PlayerSocketConnection(process, client, client.getRemoteAddress(), readThread, writeThread);
                    reference.set(connection);
                    readThread.start();
                    writeThread.start();
                    loginTimeoutBuilder.start(() -> loginTimeoutWatch(connection));
                } catch (IOException e) {
                    if (!process.config().suppressConnectionAcceptErrors())
                        process.exception().handleException(e);
                    try {
                        client.close();
                    } catch (IOException _) {
                        // Nothing more we can do, drop the connection
                    }
                }
            }
        });
    }

    /**
     * Closes a connection that never reached the configuration state, see the internal task
     * {@code connection.login-timeout}. No disconnect packet is sent, the socket is simply closed.
     */
    private static void loginTimeoutWatch(PlayerSocketConnection connection) {
        try {
            Thread.sleep(connection.process().config().loginTimeout());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!connection.loginTimedOut(System.currentTimeMillis())) return;
        LOGGER.info("Login timeout: connection_id={} identifier={} reason={}",
                connection.connectionId(), connection.getIdentifier(),
                DisconnectReasons.LOGIN_TIMEOUT.key());
        connection.disconnect();
    }

    private void configureSocket(SocketChannel channel) throws IOException {
        if (channel.getLocalAddress() instanceof InetSocketAddress) {
            final ServerConfig config = process.config();
            Socket socket = channel.socket();
            socket.setSendBufferSize(config.socketSendBufferSize());
            socket.setReceiveBufferSize(config.socketReceiveBufferSize());
            socket.setTcpNoDelay(config.socketNoDelay());
            socket.setSoTimeout(config.socketTimeout());
        }
    }

    private void playerReadLoop(PlayerSocketConnection connection) {
        Objects.requireNonNull(connection, "connection cannot be null");
        while (!stop) {
            try {
                // Read & process packets
                connection.read(packetParser);
            } catch (ClosedChannelException | EOFException _) {
                connection.disconnect(); // We closed the socket during read, just exit.
                break;
            } catch (IOException e) {
                if (!process.config().suppressConnectionIoErrors())
                    process.exception().handleException(e);
                connection.disconnect();
                break;
            } catch (Throwable e) {
                process.exception().handleException(e);
                connection.disconnect();
                break;
            }
        }
    }

    private void playerWriteLoop(PlayerSocketConnection connection) {
        Objects.requireNonNull(connection, "connection cannot be null");
        try {
            while (!stop) {
                try {
                    connection.flushSync();
                } catch (ClosedChannelException | EOFException _) {
                    connection.disconnect();
                } catch (IOException e) {
                    if (!process.config().suppressConnectionIoErrors())
                        process.exception().handleException(e);
                    connection.disconnect();
                } catch (Throwable e) {
                    process.exception().handleException(e);
                    connection.disconnect();
                }
                if (!connection.isOnline()) {
                    try {
                        connection.flushSync();
                    } catch (IOException _) {
                        // Ignore IO errors
                    } finally {
                        try {
                            connection.getChannel().close();
                        } catch (IOException _) {
                            // May error if it was disconnect client side
                        }
                    }
                    break; // Disconnect
                }
            }
        } finally {
            connection.cleanup(); // Cleanup pooling
        }
    }

    public boolean isOpen() {
        return !stop;
    }

    public void stop() {
        this.stop = true;
        try {
            final var serverSocket = this.serverSocket;
            if (serverSocket != null) {
                serverSocket.close();
            }

            if (socketAddress instanceof UnixDomainSocketAddress unixDomainSocketAddress) {
                Files.deleteIfExists(unixDomainSocketAddress.getPath());
            }
        } catch (IOException e) {
            process.exception().handleException(e);
        }
    }

    @ApiStatus.Internal
    public PacketParser.Client packetParser() {
        return packetParser;
    }

    public SocketAddress socketAddress() {
        return socketAddress;
    }

    public String getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }
}
