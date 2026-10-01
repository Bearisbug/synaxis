package io.github.bearisbug.synaxis.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.crypto.KeyUtils;
import io.github.bearisbug.synaxis.crypto.LoginCrypt;
import io.github.bearisbug.synaxis.network.packet.PacketReading;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientFinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientEncryptionResponsePacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.KeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.EncryptionRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginDisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import io.github.bearisbug.synaxis.registry.Registries;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The client half of the login encryption, written against the JDK the way a 26.2 client does it
 * (RSA/PKCS#1 for the key and the challenge, AES/CFB8 both ways once the key is sent), to drive a
 * real server over loopback in tests.
 */
final class EncryptedLoginClient implements AutoCloseable {

    private final Socket socket;
    private final Registries registries;
    private final int port;
    private final ByteArrayOutputStream rawIncoming = new ByteArrayOutputStream();
    private InputStream in;
    private OutputStream out;
    private int compressionThreshold = -1;
    private @Nullable String serverHash;

    EncryptedLoginClient(int port, Registries registries) throws IOException {
        this.port = port;
        this.registries = registries;
        this.socket = new Socket();
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5_000);
        socket.setSoTimeout(20_000);
        this.in = new Recording(socket.getInputStream(), rawIncoming);
        this.out = socket.getOutputStream();
    }

    // -- the steps of a login ---------------------------------------------------------------

    void handshakeAndStart(String username) throws IOException {
        handshake(port);
        send(ConnectionState.LOGIN, new ClientLoginStartPacket(username, UUID.randomUUID()));
    }

    void handshake(int serverPort) throws IOException {
        handshake(serverPort, ClientHandshakePacket.Intent.LOGIN);
    }

    void handshake(int serverPort, ClientHandshakePacket.Intent intent) throws IOException {
        send(ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION, "localhost",
                serverPort, intent));
    }

    EncryptionRequestPacket readEncryptionRequest() throws IOException {
        final ServerPacket packet = read(ConnectionState.LOGIN);
        if (packet instanceof EncryptionRequestPacket request) return request;
        throw new AssertionError("expected an encryption request, got " + describe(packet));
    }

    /** Answers the request as a vanilla client does, and switches to the cipher right after. */
    void answer(EncryptionRequestPacket request) throws IOException {
        final byte[] secret = new byte[LoginCrypt.SHARED_SECRET_LENGTH];
        new SecureRandom().nextBytes(secret);
        final PublicKey publicKey = KeyUtils.publicRSAKeyFrom(request.publicKey());
        sendKey(encrypt(publicKey, secret), encrypt(publicKey, request.verifyToken()));
        encryptWith(secret);
        this.serverHash = LoginCrypt.serverHash(request.serverId(), secret, publicKey);
    }

    void sendKey(byte[] encryptedSecret, byte[] encryptedChallenge) throws IOException {
        send(ConnectionState.LOGIN, new ClientEncryptionResponsePacket(encryptedSecret, encryptedChallenge));
    }

    /** From here on everything is AES/CFB8 with {@code secret} as key and IV, both ways. */
    void encryptWith(byte[] secret) {
        final SecretKey key = LoginCrypt.sharedSecret(secret);
        this.in = new CipherInputStream(in, LoginCrypt.streamCipher(Cipher.DECRYPT_MODE, key));
        this.out = new CipherOutputStream(out, LoginCrypt.streamCipher(Cipher.ENCRYPT_MODE, key));
    }

    static byte[] encrypt(PublicKey key, byte[] data) {
        try {
            final Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads the rest of the login: the compression switch, then either the login success or a
     * login disconnect, whichever comes.
     */
    ServerPacket readLoginOutcome() throws IOException {
        while (true) {
            final ServerPacket packet = read(ConnectionState.LOGIN);
            if (packet instanceof SetCompressionPacket compression) {
                this.compressionThreshold = compression.threshold();
                continue;
            }
            return packet;
        }
    }

    /** A full login that is expected to succeed. */
    LoginSuccessPacket login(String username) throws IOException {
        handshakeAndStart(username);
        answer(readEncryptionRequest());
        final ServerPacket outcome = readLoginOutcome();
        if (outcome instanceof LoginSuccessPacket success) return success;
        throw new AssertionError("the login failed: " + describe(outcome));
    }

    /** Acknowledges the login and answers the configuration phase until the server lets it play. */
    void configure() throws IOException {
        send(ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket());
        final var configuration = PacketVanilla.SERVER_PACKET_PARSER.configuration();
        final int knownPacks = configuration.packetInfo(SelectKnownPacksPacket.class).id();
        final int keepAlive = configuration.packetInfo(KeepAlivePacket.class).id();
        final int finish = configuration.packetInfo(FinishConfigurationPacket.class).id();
        final int disconnect = configuration.packetInfo(DisconnectPacket.class).id();
        while (true) {
            final byte[] body = readBody();
            final int id = packetId(body);
            if (id == knownPacks) {
                final var request = (SelectKnownPacksPacket) parse(ConnectionState.CONFIGURATION, body);
                send(ConnectionState.CONFIGURATION, new ClientSelectKnownPacksPacket(request.entries()));
            } else if (id == keepAlive) {
                final var request = (KeepAlivePacket) parse(ConnectionState.CONFIGURATION, body);
                send(ConnectionState.CONFIGURATION, new ClientKeepAlivePacket(request.id()));
            } else if (id == finish) {
                send(ConnectionState.CONFIGURATION, new ClientFinishConfigurationPacket());
                return;
            } else if (id == disconnect) {
                throw new AssertionError("disconnected during configuration: "
                        + key(((DisconnectPacket) parse(ConnectionState.CONFIGURATION, body)).message()));
            }
        }
    }

    /**
     * Reads play packets until the server disconnects this client, answering keep alives.
     *
     * @return the reason key of the disconnect, or null if the connection just closed
     */
    @Nullable String readPlayUntilDisconnect() throws IOException {
        final var play = PacketVanilla.SERVER_PACKET_PARSER.play();
        final int keepAlive = play.packetInfo(KeepAlivePacket.class).id();
        final int disconnect = play.packetInfo(DisconnectPacket.class).id();
        while (true) {
            final byte[] body;
            try {
                body = readBody();
            } catch (EOFException _) {
                return null;
            }
            final int id = packetId(body);
            if (id == keepAlive) {
                send(ConnectionState.PLAY, new ClientKeepAlivePacket(((KeepAlivePacket) parse(ConnectionState.PLAY, body)).id()));
            } else if (id == disconnect) {
                return key(((DisconnectPacket) parse(ConnectionState.PLAY, body)).message());
            }
        }
    }

    /**
     * Reads play packets until one of {@code type} arrives, answering keep alives on the way, for at
     * most 10 seconds: the server keeps sending keep alives, so the socket timeout alone never ends
     * the wait.
     *
     * @param skipped given the id of every other packet read before it
     * @return the packet
     */
    <T extends ServerPacket> T readPlay(Class<T> type, IntConsumer skipped) throws IOException {
        final var play = PacketVanilla.SERVER_PACKET_PARSER.play();
        final int wanted = play.packetInfo(type).id();
        final int keepAlive = play.packetInfo(KeepAlivePacket.class).id();
        final int disconnect = play.packetInfo(DisconnectPacket.class).id();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            if (System.nanoTime() > deadline)
                throw new AssertionError("no " + type.getSimpleName() + " within 10 seconds");
            final byte[] body = readBody();
            final int id = packetId(body);
            if (id == wanted) return type.cast(parse(ConnectionState.PLAY, body));
            if (id == keepAlive) {
                send(ConnectionState.PLAY, new ClientKeepAlivePacket(((KeepAlivePacket) parse(ConnectionState.PLAY, body)).id()));
            } else if (id == disconnect) {
                throw new AssertionError("disconnected in play: " + key(((DisconnectPacket) parse(ConnectionState.PLAY, body)).message()));
            }
            skipped.accept(id);
        }
    }

    // -- what a test looks at ----------------------------------------------------------------

    /** The {@code serverId} this client would have joined with. */
    String serverHash() {
        if (serverHash == null) throw new IllegalStateException("no key was sent yet");
        return serverHash;
    }

    /** Every byte received so far, as it came off the socket. */
    byte[] rawIncoming() {
        synchronized (rawIncoming) {
            return rawIncoming.toByteArray();
        }
    }

    /** The translate key of the login disconnect the server answered with. */
    static String loginDisconnectKey(ServerPacket packet) {
        if (!(packet instanceof LoginDisconnectPacket disconnect))
            throw new AssertionError("expected a login disconnect, got " + describe(packet));
        return key(disconnect.kickMessage());
    }

    /** Whether the server closed the connection without sending anything more. */
    boolean closedWithoutAnotherPacket() throws IOException {
        try {
            return in.read() < 0;
        } catch (IOException _) {
            return true;
        }
    }

    static String key(Component component) {
        if (component instanceof TranslatableComponent translatable) return translatable.key();
        throw new AssertionError("not a reason key: " + component);
    }

    private static String describe(@Nullable ServerPacket packet) {
        if (packet instanceof LoginDisconnectPacket disconnect) return "login disconnect " + disconnect.kickMessage();
        return packet == null ? "a closed connection" : packet.getClass().getSimpleName();
    }

    // -- framing ---------------------------------------------------------------------------

    void send(ConnectionState state, ClientPacket packet) throws IOException {
        final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, registries,
                Math.max(compressionThreshold, 0));
        out.write(framed.read(NetworkBuffer.RAW_BYTES));
        out.flush();
    }

    /** Writes bytes as they are, for frames a vanilla client would never send. */
    void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /** The next packet, or null if the server closed the connection first. */
    @Nullable ServerPacket read(ConnectionState state) throws IOException {
        final byte[] body;
        try {
            body = readBody();
        } catch (EOFException _) {
            return null;
        }
        return parse(state, body);
    }

    private ServerPacket parse(ConnectionState state, byte[] body) {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, body.length);
        frame.writeBytes(body);
        final byte[] bytes = frame.toByteArray();
        final NetworkBuffer buffer = NetworkBuffer.wrap(bytes, 0, bytes.length, registries);
        try {
            final var result = PacketReading.readServers(buffer, state, false);
            if (result instanceof PacketReading.Result.Success<ServerPacket> success)
                return success.packets().getFirst().packet();
        } catch (DataFormatException e) {
            throw new AssertionError("not a packet", e);
        }
        throw new AssertionError("not a packet: " + body.length + " bytes");
    }

    private static int packetId(byte[] body) {
        int value = 0;
        for (int index = 0, shift = 0; index < body.length; index++, shift += 7) {
            value |= (body[index] & 0x7F) << shift;
            if ((body[index] & 0x80) == 0) return value;
        }
        throw new AssertionError("no packet id");
    }

    /** One frame with the compression envelope taken off: packet id and payload. */
    private byte[] readBody() throws IOException {
        final int length = readVarInt(in);
        // Not InputStream#readNBytes: once it has its bytes it still asks for zero more, and
        // CipherInputStream blocks on the socket for that when its buffer happens to be empty
        final byte[] frame = new byte[length];
        for (int read = 0; read < length; ) {
            final int chunk = in.read(frame, read, length - read);
            if (chunk < 0) throw new EOFException();
            read += chunk;
        }
        if (compressionThreshold < 0) return frame;
        final InputStream data = new ByteArrayInputStream(frame);
        final int dataLength = readVarInt(data);
        final byte[] rest = data.readAllBytes();
        if (dataLength == 0) return rest;
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(rest);
            final byte[] body = new byte[dataLength];
            int written = 0;
            while (written < dataLength) {
                final int read = inflater.inflate(body, written, dataLength - written);
                if (read == 0) throw new IOException("truncated compressed frame");
                written += read;
            }
            return body;
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    private static int readVarInt(InputStream stream) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            final int read = stream.read();
            if (read < 0) throw new EOFException();
            value |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) return value;
        }
        throw new IOException("var-int too long");
    }

    private static void writeVarInt(ByteArrayOutputStream stream, int value) {
        while ((value & ~0x7F) != 0) {
            stream.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        stream.write(value);
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException _) {
            // Already gone
        }
    }

    /** Keeps a copy of every byte read from the socket, below the cipher. */
    private static final class Recording extends FilterInputStream {
        private final ByteArrayOutputStream copy;

        Recording(InputStream in, ByteArrayOutputStream copy) {
            super(in);
            this.copy = copy;
        }

        @Override
        public int read() throws IOException {
            final int read = super.read();
            if (read >= 0) synchronized (copy) {
                copy.write(read);
            }
            return read;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            final int read = super.read(buffer, offset, length);
            if (read > 0) synchronized (copy) {
                copy.write(buffer, offset, read);
            }
            return read;
        }
    }
}
