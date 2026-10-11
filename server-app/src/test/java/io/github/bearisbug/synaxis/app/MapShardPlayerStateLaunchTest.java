package io.github.bearisbug.synaxis.app;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.bearisbug.synaxis.MinecraftServer;
import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Frame;
import io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.Launched;
import io.github.bearisbug.synaxis.app.shard.MapFixture;
import io.github.bearisbug.synaxis.app.shard.ShardDirectory;
import io.github.bearisbug.synaxis.app.shard.ShardSettings;
import io.github.bearisbug.synaxis.app.store.PlayerStateSection;
import io.github.bearisbug.synaxis.app.store.StoreSettings;
import io.github.bearisbug.synaxis.app.store.TestDatabase;
import io.github.bearisbug.synaxis.baker.Baker;
import io.github.bearisbug.synaxis.baker.GeneratorPreset;
import io.github.bearisbug.synaxis.baker.GeneratorSource;
import io.github.bearisbug.synaxis.baker.LightVersions;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.RelativeFlags;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.NetworkBuffer;
import io.github.bearisbug.synaxis.network.packet.PacketVanilla;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientKeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientPongPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientFinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket;
import io.github.bearisbug.synaxis.network.packet.client.handshake.ClientHandshakePacket.Intent;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginAcknowledgedPacket;
import io.github.bearisbug.synaxis.network.packet.client.login.ClientLoginStartPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientChunkBatchReceivedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerLoadedPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.KeepAlivePacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PingPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.FinishConfigurationPacket;
import io.github.bearisbug.synaxis.network.packet.server.configuration.SelectKnownPacksPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.LoginSuccessPacket;
import io.github.bearisbug.synaxis.network.packet.server.login.SetCompressionPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkBatchFinishedPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.registry.Registries;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.compressedFrame;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.frame;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.freePort;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.id;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.readUntil;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.readVarInt;
import static io.github.bearisbug.synaxis.app.ConfigFileLaunchTest.send;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@code synaxis:player} on a map shard in a JVM of its own ({@code docs/DESIGN-PROD.md} {@code REQ-PROD-047},
 * {@code API-PROD-032}, v0.9.73): {@link ShardMain} mounts {@link MapFixture}'s release in the directory mode, storing in
 * PostgreSQL, and a client logs in over the loopback without a ticket. The section is registered, the logout writes it
 * with the map and the build of the release; a position saved on that release inside the bounds is where the player is
 * put back; a shard on another build of the same map leaves the player at the spawn of the region and writes its own
 * build. The database is a {@link TestDatabase}: without {@code SYNAXIS_TEST_PG_URL} a machine that has none skips this.
 */
@Timeout(300)
public class MapShardPlayerStateLaunchTest {

    private static final String SHARD_ID = "r0-c1";
    private static final String NAME = "Keeper";
    private static final UUID ACCOUNT = UUID.fromString("5b2a7c1e-0d4f-4a8e-9c3b-6f1e2d7a8b90");
    /** Inside the bounds, chunk 2, 2, away from the spawn of the region on its center column. */
    private static final Pos SAVED = new Pos(40.5, MapFixture.FLOOR, 40.5, 90, 0);

    @TempDir
    Path directory;

    private @Nullable TestDatabase database;

    @AfterEach
    void drop() {
        if (database != null) database.close();
    }

    @Test
    public void theSectionComesBackOnItsOwnBuildAndNotOnAnother() throws Exception {
        final TestDatabase store = TestDatabase.create();
        database = store;
        final Path maps = directory.resolve("maps");
        final ShardSettings built = MapFixture.bake(maps);
        final ShardSettings rebuilt = bakeAnotherBuild(maps);
        assertNotEquals(built.mapBuild(), rebuilt.mapBuild(), "two builds of one map");
        writeDirectory(built.mapRoot(), freePort());
        final Map<String, String> environment = environment(store.settings());
        assertEquals(0, Main.migrateOnly(new String[]{Main.MIGRATE_ONLY, Main.AUTH_MODE_ARGUMENT + "offline"}, environment));

        final int builtPort = freePort();
        final Pos spawn;
        try (Launched shard = Launched.start(Files.createDirectories(directory.resolve("built")),
                System.getProperty("java.class.path"), environment, ShardMain.class, launch(builtPort, built))) {
            spawn = against(shard, () -> {
                shard.awaitLine("synaxis server-app listening on");
                final Pos spawned;
                try (Client client = new Client(builtPort)) {
                    spawned = client.nextPosition();
                    client.awaitChunks();
                }
                final JsonObject first = awaitSection(store, _ -> true, "the logout wrote synaxis:player");
                assertEquals(world(built), first.getAsJsonObject("world"), "the map and the build of the release");
                assertEquals(List.of(spawned.x(), spawned.y(), spawned.z()), position(first),
                        "where it left: the spawn of the region");
                assertNotEquals(SAVED.chunkX(), spawned.chunkX(), "the spawn is elsewhere than the saved position");

                store.execute("UPDATE character_section SET data = jsonb_set(data, '{pos}', '{\"x\": " + SAVED.x()
                        + ", \"y\": " + SAVED.y() + ", \"z\": " + SAVED.z() + ", \"yaw\": " + SAVED.yaw()
                        + ", \"pitch\": " + SAVED.pitch() + "}') WHERE section_id = '" + PlayerStateSection.ID + "'");
                try (Client client = new Client(builtPort)) {
                    client.awaitPosition(SAVED, "put back where it was saved on this build inside the bounds");
                }
                awaitNoLease(store);
                return spawned;
            });
        }

        final int rebuiltPort = freePort();
        try (Launched shard = Launched.start(Files.createDirectories(directory.resolve("rebuilt")),
                System.getProperty("java.class.path"), environment, ShardMain.class, launch(rebuiltPort, rebuilt))) {
            against(shard, () -> {
                shard.awaitLine("synaxis server-app listening on");
                final Pos respawned;
                try (Client client = new Client(rebuiltPort)) {
                    respawned = client.nextPosition();
                    client.awaitChunks();
                }
                assertEquals(List.of(spawn.x(), spawn.z()), List.of(respawned.x(), respawned.z()), "the spawn of the region");
                final JsonObject last = awaitSection(store, section -> world(rebuilt).equals(
                        section.getAsJsonObject("world")), "the logout on another build wrote its build");
                assertEquals(List.of(respawned.x(), respawned.y(), respawned.z()), position(last),
                        "left at the spawn of the region, not taken to the position saved on the other build");
                return respawned;
            });
        }
    }

    /** Runs {@code body} against a shard; a failure carries the shard's log. */
    private static <T> T against(Launched shard, Callable<T> body) throws Exception {
        try {
            return body.call();
        } catch (Exception | AssertionError e) {
            e.addSuppressed(new AssertionError("the log of the shard:\n" + shard.log()));
            throw e;
        }
    }

    /** {@link MapFixture}'s map with cobblestone for its stone: the same map id, bounds and floor, another build. */
    private static ShardSettings bakeAnotherBuild(Path mapsRoot) {
        final GeneratorSource source = new GeneratorSource(new GeneratorPreset(
                unit -> unit.modifier().fillHeight(0, MapFixture.FLOOR, Block.COBBLESTONE), 0, 64, MapFixture.BOUNDS),
                Registries.vanilla());
        final Baker.Result result = Baker.bake(new Baker.Request(MapFixture.MAP_ID, source, mapsRoot, 1,
                mapsRoot.resolve("logs"), Registries.vanilla(), _ -> null, LightVersions.verified(), 1));
        assertEquals(Baker.EXIT_PUBLISHED, result.exitCode(), result.toString());
        return new ShardSettings(mapsRoot.resolve(MapFixture.MAP_ID),
                Objects.requireNonNull(result.directory()).getFileName().toString(), 4, 100);
    }

    /**
     * One region over the whole release and its one channel. The port is only what the heartbeat announces: nothing
     * here connects through the directory, and both shards take a port of their own.
     */
    private static void writeDirectory(Path mapRoot, int port) throws IOException {
        final Path file = mapRoot.resolve(ShardDirectory.FILE_NAME);
        Files.writeString(file, """
                {"format_version": 1, "map_id": "%s",
                 "regions": [{"region_id": "r0", "bounds": {"min_x": %d, "min_z": %d, "max_x": %d, "max_z": %d}, "gates": []}],
                 "channels": [{"shard_id": "%s", "region_id": "r0", "channel": 1, "host": "127.0.0.1", "port": %d,
                               "capacity": 10}]}
                """.formatted(MapFixture.MAP_ID, MapFixture.BOUNDS.minX(), MapFixture.BOUNDS.minZ(),
                MapFixture.BOUNDS.maxX(), MapFixture.BOUNDS.maxZ(), SHARD_ID, port));
        // What the shard accepts whatever the umask of this JVM (docs/DESIGN-M3.md API-M3-002 文件信任)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    }

    /** The store and the transfer keys of a shard in the directory mode, which takes transfers. */
    private static Map<String, String> environment(StoreSettings settings) {
        return Map.of("SYNAXIS_STATE_DB_URL", settings.jdbcUrl(), "SYNAXIS_STATE_DB_USER", settings.user(),
                "SYNAXIS_STATE_DB_PASSWORD", settings.password(),
                "SYNAXIS_TRANSFER_HMAC_KEYS", "1:" + Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static String[] launch(int port, ShardSettings release) {
        return new String[]{"-Dsynaxis.app.log=stdout", "-Dsynaxis.app.auth=offline", "-Dsynaxis.app.port=" + port,
                "-Dsynaxis.app.metrics.bind=127.0.0.1:0", "-Dsynaxis.app.store=postgres",
                "-Dsynaxis.app.shard-id=" + SHARD_ID, "-Dsynaxis.map.root=" + release.mapRoot(),
                "-Dsynaxis.map.build=" + release.mapBuild()};
    }

    private static JsonObject world(ShardSettings release) {
        final JsonObject world = new JsonObject();
        world.addProperty("kind", "map");
        world.addProperty("map_id", MapFixture.MAP_ID);
        world.addProperty("build_id", release.mapBuild());
        return world;
    }

    private static List<Double> position(JsonObject section) {
        final JsonObject pos = section.getAsJsonObject("pos");
        return List.of(pos.get("x").getAsDouble(), pos.get("y").getAsDouble(), pos.get("z").getAsDouble());
    }

    /** The account's {@code synaxis:player}, null while it has none. */
    private static @Nullable JsonObject section(TestDatabase store) throws SQLException {
        try (Connection connection = store.connect(); PreparedStatement select = connection.prepareStatement("""
                SELECT s.data::text
                  FROM account a JOIN character c ON c.account_uuid = a.account_uuid
                  JOIN character_section s ON s.character_id = c.character_id AND s.section_id = ?
                 WHERE a.last_name = ?""")) {
            select.setString(1, PlayerStateSection.ID);
            select.setString(2, NAME);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? JsonParser.parseString(row.getString(1)).getAsJsonObject() : null;
            }
        }
    }

    private static JsonObject awaitSection(TestDatabase store, Predicate<JsonObject> written, String what)
            throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            final JsonObject section = section(store);
            if (section != null && written.test(section)) return section;
            if (System.nanoTime() > deadline)
                throw new AssertionError("not within 20 s: " + what + "; the section is " + section);
            Thread.sleep(100);
        }
    }

    /** Waits for the logout save to give the lease back, so the next shard loads what it wrote. */
    private static void awaitNoLease(TestDatabase store) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (true) {
            try (Connection connection = store.connect(); PreparedStatement select = connection.prepareStatement(
                    "SELECT count(*) FROM character WHERE lease_session_id IS NOT NULL");
                 ResultSet row = select.executeQuery()) {
                row.next();
                if (row.getInt(1) == 0) return;
            }
            if (System.nanoTime() > deadline) throw new AssertionError("not within 20 s: the lease given back");
            Thread.sleep(100);
        }
    }

    /**
     * A client on the loopback, logged in without a ticket and through the configuration into the play state. It
     * answers what a vanilla client answers there: keep alives, pings, every teleport, the end of every chunk batch,
     * and it reports itself loaded after its first teleport.
     */
    private static final class Client implements AutoCloseable {
        private final SocketChannel channel;
        private final InputStream in;
        private final int threshold;
        private boolean loaded;

        Client(int port) throws Exception {
            channel = SocketChannel.open(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            channel.socket().setSoTimeout(30_000);
            in = channel.socket().getInputStream();
            send(channel, ConnectionState.HANDSHAKE, new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION,
                    "localhost", port, Intent.LOGIN), 0);
            send(channel, ConnectionState.LOGIN, new ClientLoginStartPacket(NAME, ACCOUNT), 0);
            final InputStream setCompression = new ByteArrayInputStream(frame(in));
            assertEquals(id(ConnectionState.LOGIN, SetCompressionPacket.class), readVarInt(setCompression));
            threshold = readVarInt(setCompression);
            final List<Frame> frames = new ArrayList<>();
            readUntil(in, frames, id(ConnectionState.LOGIN, LoginSuccessPacket.class));
            send(channel, ConnectionState.LOGIN, new ClientLoginAcknowledgedPacket(), threshold);
            readUntil(in, frames, id(ConnectionState.CONFIGURATION, SelectKnownPacksPacket.class));
            send(channel, ConnectionState.CONFIGURATION, new ClientSelectKnownPacksPacket(List.of()), threshold);
            readUntil(in, frames, id(ConnectionState.CONFIGURATION, FinishConfigurationPacket.class));
            send(channel, ConnectionState.CONFIGURATION, new ClientFinishConfigurationPacket(), threshold);
        }

        /** The next absolute position the server puts the player at. */
        Pos nextPosition() throws Exception {
            while (true) {
                final PlayerPositionAndLookPacket position = until(PlayerPositionAndLookPacket.class);
                if ((position.flags() & RelativeFlags.COORD) == 0) {
                    return new Pos(position.position().x(), position.position().y(), position.position().z(),
                            position.yaw(), position.pitch());
                }
            }
        }

        /** Reads positions until the server puts the player at {@code expected}, for 30 s at most. */
        void awaitPosition(Pos expected, String what) throws Exception {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            final List<Pos> seen = new ArrayList<>();
            while (System.nanoTime() < deadline) {
                final Pos position = nextPosition();
                if (position.equals(expected)) return;
                seen.add(position);
            }
            throw new AssertionError(what + ": not within 30 s, the positions were " + seen);
        }

        /** Reads up to the end of the first chunk batch: the player has spawned, its first spawn is handled. */
        void awaitChunks() throws Exception {
            until(ChunkBatchFinishedPacket.class);
        }

        /** Reads the play state up to the next packet of {@code type}, answering on the way. */
        private <T extends ServerPacket> T until(Class<T> type) throws Exception {
            final int wanted = id(ConnectionState.PLAY, type);
            while (true) {
                final byte[] packet = compressedFrame(in).packet();
                final NetworkBuffer buffer = NetworkBuffer.wrap(packet, 0, packet.length);
                final int packetId = buffer.read(NetworkBuffer.VAR_INT);
                final ServerPacket read;
                if (packetId == wanted || packetId == id(ConnectionState.PLAY, KeepAlivePacket.class)
                        || packetId == id(ConnectionState.PLAY, PingPacket.class)
                        || packetId == id(ConnectionState.PLAY, PlayerPositionAndLookPacket.class)
                        || packetId == id(ConnectionState.PLAY, ChunkBatchFinishedPacket.class)) {
                    read = PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(ConnectionState.PLAY).packetInfo(packetId)
                            .serializer().read(buffer);
                } else if (packetId == id(ConnectionState.PLAY, DisconnectPacket.class)) {
                    throw new AssertionError("the server disconnected the client: " + reason(buffer));
                } else {
                    continue;
                }
                switch (read) {
                    case KeepAlivePacket keepAlive ->
                            send(channel, ConnectionState.PLAY, new ClientKeepAlivePacket(keepAlive.id()), threshold);
                    case PingPacket ping -> send(channel, ConnectionState.PLAY, new ClientPongPacket(ping.id()), threshold);
                    case PlayerPositionAndLookPacket teleport -> {
                        send(channel, ConnectionState.PLAY, new ClientTeleportConfirmPacket(teleport.teleportId()), threshold);
                        if (!loaded) send(channel, ConnectionState.PLAY, new ClientPlayerLoadedPacket(), threshold);
                        loaded = true;
                    }
                    case ChunkBatchFinishedPacket _ ->
                            send(channel, ConnectionState.PLAY, new ClientChunkBatchReceivedPacket(25f), threshold);
                    default -> {
                        // nothing to answer
                    }
                }
                if (type.isInstance(read)) return type.cast(read);
            }
        }

        private static String reason(NetworkBuffer buffer) {
            try {
                return DisconnectPacket.SERIALIZER.read(buffer).message().toString();
            } catch (RuntimeException e) {
                return "a reason that does not decode here: " + e;
            }
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
