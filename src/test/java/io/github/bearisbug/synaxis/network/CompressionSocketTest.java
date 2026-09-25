package io.github.bearisbug.synaxis.network;

import io.github.bearisbug.synaxis.JvmFlags;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.event.player.AsyncPlayerPreLoginEvent;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.PacketWriting;
import io.github.bearisbug.synaxis.network.packet.client.ClientPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginPluginRequestPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The compression settings on a real socket ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-038},
 * {@code API-PROD-020}): {@code SetCompression} announces the threshold of the process's
 * {@link ServerConfig}, a packet body below it goes out with data length 0 and one at it is
 * compressed, written at {@link JvmFlags#COMPRESSION_LEVEL}.
 * <p>
 * The packets of known sizes are login plugin requests the test sends itself while the login waits
 * in {@link AsyncPlayerPreLoginEvent}, which comes after compression is switched on. Nothing waits
 * for their answers, so the client does not give any.
 */
public class CompressionSocketTest {

    private static final String CHANNEL = "synaxis:probe";
    private static final int MESSAGE_ID = 1;
    /** The packet id, the message id and the length of the channel take a byte each. */
    private static final int OVERHEAD = 3 + CHANNEL.length();

    private static final int LOGIN_PLUGIN_REQUEST =
            PacketVanilla.SERVER_PACKET_PARSER.login().packetInfo(LoginPluginRequestPacket.class).id();
    private static final int LOGIN_SUCCESS =
            PacketVanilla.SERVER_PACKET_PARSER.login().packetInfo(LoginSuccessPacket.class).id();
    private static final int SET_COMPRESSION =
            PacketVanilla.SERVER_PACKET_PARSER.login().packetInfo(SetCompressionPacket.class).id();

    @Test
    public void aBodyOneByteShortOfTheThresholdGoesAsItIsAndOneAtTheThresholdIsCompressed() throws Exception {
        try (Login login = Login.start(ServerConfig.builder().compressionThreshold(512).build(), 511, 512)) {
            assertEquals(512, login.setCompression());

            final Frame below = login.compressedFrame();
            assertEquals(0, below.dataLength(), "511 bytes are below the threshold");
            assertEquals(511, below.packet().length);
            assertEquals(LOGIN_PLUGIN_REQUEST, below.packetId());

            final Frame at = login.compressedFrame();
            assertEquals(512, at.dataLength(), "512 bytes are at the threshold");
            assertEquals(512, at.packet().length);
            assertEquals(LOGIN_PLUGIN_REQUEST, at.packetId());
            assertWrittenAt(JvmFlags.COMPRESSION_LEVEL, at);

            assertEquals(LOGIN_SUCCESS, login.compressedFrame().packetId());
        }
    }

    @Test
    public void theEngineDefaultsAreTwoHundredFiftySixAndLevelSix() throws Exception {
        assertEquals(6, JvmFlags.COMPRESSION_LEVEL, "the test JVM is given no synaxis.compression-level");
        try (Login login = Login.start(ServerConfig.defaults(), 255, 256)) {
            assertEquals(256, login.setCompression());
            assertEquals(0, login.compressedFrame().dataLength());
            final Frame at = login.compressedFrame();
            assertEquals(256, at.dataLength());
            assertWrittenAt(6, at);
            // Level 6 is what a deflater without a level writes: the default changed no byte on the wire
            assertArrayEquals(deflate(at.packet(), Deflater.DEFAULT_COMPRESSION), at.compressed());
        }
    }

    @Test
    public void thresholdZeroSendsNoSetCompressionAndNothingIsCompressed() throws Exception {
        try (Login login = Login.start(ServerConfig.builder().compressionThreshold(0).build(), 511, 512)) {
            // Without compression a frame is the packet id and the body, no data length
            final byte[] first = login.frame();
            assertEquals(LOGIN_PLUGIN_REQUEST, first[0], "the first packet is the probe, not SetCompression");
            assertEquals(511, first.length);
            assertEquals(512, login.frame().length);
            assertEquals(LOGIN_SUCCESS, login.frame()[0]);
        }
    }

    /** The zlib stream carries the level in its header, and deflating the body again at that level gives the same bytes. */
    private static void assertWrittenAt(int level, Frame frame) {
        assertEquals(0x78, frame.compressed()[0] & 0xFF, "a zlib stream with a 32 KiB window");
        assertEquals(zlibHeaderLevelByte(level), frame.compressed()[1] & 0xFF, "the level bits of the zlib header");
        assertArrayEquals(deflate(frame.packet(), level), frame.compressed());
    }

    /** RFC 1950: FLEVEL 0 for levels 0 and 1, 1 for 2 to 5, 2 for 6, 3 for 7 to 9, with the check bits. */
    private static int zlibHeaderLevelByte(int level) {
        if (level <= 1) return 0x01;
        if (level <= 5) return 0x5E;
        if (level == 6) return 0x9C;
        return 0xDA;
    }

    private static byte[] deflate(byte[] input, int level) {
        final Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(input);
            deflater.finish();
            final byte[] output = new byte[input.length + 64];
            final int length = deflater.deflate(output);
            return Arrays.copyOf(output, length);
        } finally {
            deflater.end();
        }
    }

    /**
     * One frame once compression is on.
     *
     * @param dataLength the length of the packet before compression, 0 when it is not compressed
     * @param compressed the bytes after the data length
     * @param packet     the packet id and body
     */
    private record Frame(int dataLength, byte[] compressed, byte[] packet) {
        int packetId() {
            return packet[0];
        }
    }

    /** A process listening on a free loopback port and one client logging into it. */
    private record Login(ServerProcess process, SocketChannel channel, InputStream in) implements AutoCloseable {

        /**
         * Starts the process, has it send two probes of these body sizes while the login waits, and
         * sends the handshake and the login start.
         */
        static Login start(ServerConfig config, int... bodies) throws IOException {
            final ServerProcess process = ServerProcess.of(config);
            process.eventHandler().addListener(AsyncPlayerPreLoginEvent.class, event -> {
                for (int body : bodies)
                    event.getConnection().sendPacket(new LoginPluginRequestPacket(MESSAGE_ID, CHANNEL, new byte[body - OVERHEAD]));
            });
            process.server().init(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            process.server().start();
            final SocketChannel channel = SocketChannel.open(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), process.server().getPort()));
            channel.socket().setSoTimeout(10_000);
            final Login login = new Login(process, channel, channel.socket().getInputStream());
            login.send(ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", process.server().getPort(), Intent.LOGIN));
            login.send(ConnectionState.LOGIN, new ClientLoginStartPacket("Compressed", UUID.randomUUID()));
            return login;
        }

        void send(ConnectionState state, ClientPacket packet) throws IOException {
            final NetworkBuffer framed = PacketWriting.allocateTrimmedPacket(state, packet, null, 0);
            while (!framed.writeChannel(channel)) {
                // A blocking channel writes everything
            }
        }

        /** The threshold of the SetCompression packet, which is the last frame without a data length. */
        int setCompression() throws IOException {
            final InputStream frame = new ByteArrayInputStream(frame());
            assertEquals(SET_COMPRESSION, readVarInt(frame));
            return readVarInt(frame);
        }

        /** The bytes of one frame after its length. */
        byte[] frame() throws IOException {
            final int length = readVarInt(in);
            final byte[] frame = in.readNBytes(length);
            if (frame.length < length) throw new EOFException("the server closed the connection");
            return frame;
        }

        Frame compressedFrame() throws IOException, DataFormatException {
            final byte[] frame = frame();
            final ByteArrayInputStream rest = new ByteArrayInputStream(frame);
            final int dataLength = readVarInt(rest);
            final byte[] compressed = rest.readAllBytes();
            if (dataLength == 0) return new Frame(0, compressed, compressed);
            final Inflater inflater = new Inflater();
            try {
                inflater.setInput(compressed);
                final byte[] packet = new byte[dataLength];
                assertEquals(dataLength, inflater.inflate(packet));
                return new Frame(dataLength, compressed, packet);
            } finally {
                inflater.end();
            }
        }

        private static int readVarInt(InputStream in) throws IOException {
            int value = 0;
            for (int shift = 0; ; shift += 7) {
                final int b;
                try {
                    b = in.read();
                } catch (SocketTimeoutException e) {
                    throw new AssertionError("the server sent nothing more", e);
                }
                if (b < 0) throw new EOFException("the server closed the connection");
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
            process.stop();
        }
    }
}
