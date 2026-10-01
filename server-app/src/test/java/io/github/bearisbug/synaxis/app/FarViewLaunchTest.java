package io.github.bearisbug.synaxis.app;

import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Frame;
import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import io.github.bearisbug.synaxis.app.shard.ShardSettings;
import io.github.bearisbug.synaxis.app.shard.ShardWorldFarViewTest;
import io.github.bearisbug.synaxis.game.protocol.FarViewAnnouncement;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import io.github.bearisbug.synaxis.synx.SynxManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.frame;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.freePort;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.id;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.readUntil;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.readVarInt;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.send;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The far view announcement of a map shard on the wire ({@code docs/DESIGN-M3.md} {@code API-M3-015}
 * 远景宣告, §19 「分片在 configuration 阶段宣告本大区的瓦片」「分片不宣告远景的情形」): {@link ShardMain} in a JVM of
 * its own mounts a release with far view packs, a client logs in and goes through the configuration,
 * and what {@code Main#serveMap} sent before {@code FinishConfiguration} is read off the socket.
 */
@Timeout(180)
public class FarViewLaunchTest {

    private static final String URL_ROOT = "http://cdn.test/synaxis";

    /** The payloads of {@code synaxis:farview} among the frames, in order. */
    private static List<byte[]> farViewMessages(List<Frame> frames) throws Exception {
        final int pluginMessage = id(ConnectionState.CONFIGURATION, PluginMessagePacket.class);
        final List<byte[]> messages = new ArrayList<>();
        for (Frame frame : frames) {
            final InputStream packet = new ByteArrayInputStream(frame.packet());
            if (readVarInt(packet) != pluginMessage) continue;
            final String channel = new String(packet.readNBytes(readVarInt(packet)), StandardCharsets.UTF_8);
            if (channel.equals(FarViewAnnouncement.CHANNEL)) messages.add(packet.readAllBytes());
        }
        return messages;
    }

    /**
     * Logs in and goes through the configuration, the known packs answered with none.
     *
     * @return every frame after SetCompression up to and with FinishConfiguration
     */
    private static List<Frame> loginAndConfigure(int port) throws Exception {
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port))) {
            channel.socket().setSoTimeout(30_000);
            final InputStream in = channel.socket().getInputStream();
            send(channel, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", port, Intent.LOGIN), 0);
            send(channel, ConnectionState.LOGIN, new ClientLoginStartPacket("FarView", UUID.randomUUID()), 0);
            final InputStream setCompression = new ByteArrayInputStream(frame(in));
            assertEquals(id(ConnectionState.LOGIN, SetCompressionPacket.class), readVarInt(setCompression));
            final int threshold = readVarInt(setCompression);
            final List<Frame> frames = new ArrayList<>();
            readUntil(in, frames, id(ConnectionState.LOGIN, LoginSuccessPacket.class));
            send(channel, ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket(), threshold);
            frames.clear();
            readUntil(in, frames, id(ConnectionState.CONFIGURATION, SelectKnownPacksPacket.class));
            send(channel, ConnectionState.CONFIGURATION, new ClientSelectKnownPacksPacket(List.of()), threshold);
            readUntil(in, frames, id(ConnectionState.CONFIGURATION, FinishConfigurationPacket.class));
            return frames;
        }
    }

    private static String[] launch(int port, ShardSettings release, String... more) {
        final List<String> properties = new ArrayList<>(List.of("-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline",
                "-Dsynaxis.app.port=" + port, "-Dsynaxis.app.metrics.bind=127.0.0.1:0",
                "-Dsynaxis.map.root=" + release.mapRoot(), "-Dsynaxis.map.build=" + release.mapBuild()));
        properties.addAll(List.of(more));
        return properties.toArray(String[]::new);
    }

    /**
     * The shard mounts the four tile release without a shard directory, so it serves the release's
     * bounds 250,-8,262,7 and announces all four tiles whole: one {@code synaxis:farview} before
     * {@code FinishConfiguration}, which decodes to them.
     */
    @Test
    public void aMapShardAnnouncesItsTilesBeforeFinishConfiguration(@TempDir Path directory) throws Exception {
        final ShardSettings release = ShardWorldFarViewTest.fourTileRelease(directory.resolve("maps"), URL_ROOT);
        final SynxManifest manifest = SynxManifest.fromJson(Files.readString(release.buildDir().resolve(SynxManifest.FILE_NAME)));
        final int port = freePort();
        try (Launched shard = Launched.start(directory, ShardMain.class, launch(port, release,
                "-D" + ShardSettings.FARVIEW_URL_ROOT + "=" + URL_ROOT))) {
            final String log = shard.awaitLine("synaxis server-app listening on");
            assertTrue(log.contains("Far view: 4 tile(s), 0 coarse, from " + URL_ROOT), log);

            final List<Frame> frames = loginAndConfigure(port);
            final List<byte[]> messages = farViewMessages(frames);
            assertEquals(1, messages.size(), "one announcement per connection");
            final int finish = id(ConnectionState.CONFIGURATION, FinishConfigurationPacket.class);
            assertEquals(finish, frames.getLast().packetId());
            assertEquals(1, farViewMessages(frames.subList(0, frames.size() - 1)).size(),
                    "the announcement comes before FinishConfiguration");

            final MsgBuf buf = MsgBuf.reading(messages.getFirst());
            final FarViewAnnouncement announcement = FarViewAnnouncement.read(buf);
            assertEquals(0, buf.remaining());
            assertEquals(manifest.buildId(), announcement.buildId());
            assertEquals(List.of("0,-1:0", "0,0:0", "1,-1:0", "1,0:0"), announcement.tiles().stream()
                    .map(tile -> tile.tileX() + "," + tile.tileZ() + ":" + tile.layer()).toList());
            final Map<String, SynxManifest.FarView> packs = new TreeMap<>();
            manifest.farview().forEach(pack -> packs.put(pack.tileX() + "," + pack.tileZ(), pack));
            for (FarViewAnnouncement.Tile tile : announcement.tiles()) {
                final SynxManifest.FarView pack = packs.get(tile.tileX() + "," + tile.tileZ());
                assertEquals(pack.bytes(), tile.bytes());
                assertTrue(tile.url().startsWith(URL_ROOT + "/" + manifest.mapId() + "/farview/"), tile.url());
            }
        }
    }

    /** The same release without a url root: the shard starts and sends no {@code synaxis:farview}. */
    @Test
    public void aMapShardWithoutAUrlRootAnnouncesNothing(@TempDir Path directory) throws Exception {
        final ShardSettings release = ShardWorldFarViewTest.fourTileRelease(directory.resolve("maps"), URL_ROOT);
        final int port = freePort();
        try (Launched shard = Launched.start(directory, ShardMain.class, launch(port, release))) {
            final String log = shard.awaitLine("synaxis server-app listening on");
            assertTrue(log.contains("Far view off: " + ShardSettings.FARVIEW_URL_ROOT + " is empty"), log);
            final List<Frame> frames = loginAndConfigure(port);
            assertEquals(id(ConnectionState.CONFIGURATION, FinishConfigurationPacket.class), frames.getLast().packetId());
            assertEquals(0, farViewMessages(frames).size());
        }
    }
}
