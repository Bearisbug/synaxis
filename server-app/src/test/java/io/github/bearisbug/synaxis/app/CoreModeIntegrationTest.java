package io.github.bearisbug.synaxis.app;

import ch.qos.logback.classic.Level;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerPluginMessageEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.LightingChunk;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.instance.light.Light;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.common.PluginMessagePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.ChunkDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.data.LightData;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.Admission;
import io.github.bearisbug.synaxis.plugin.AdmissionProvider;
import io.github.bearisbug.synaxis.plugin.AdmissionRequest;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core mode of the startup contract (API-M2-010): the world, the default admission and the
 * diagnostic channel the host serves while no plugin provides gameplay.
 */
@EnvTest
class CoreModeIntegrationTest {

    private static final byte[] PAYLOAD = "ping".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path workingDirectory;

    private PluginHost host(Env env) {
        return new PluginHost(env.process(), workingDirectory.resolve("plugins"),
                new EventLedger(workingDirectory.resolve("logs"), "test", EventLedger.DEFAULT_MAX_BYTES_PER_DAY));
    }

    private static InstanceContainer core(Env env, PluginHost host) {
        return CoreMode.start(env.process(), host, Main.registerMatchDimensionType(env.process()),
                CoreMode.Setting.AUTO);
    }

    @Test
    void theCoreWorldIsRegisteredAndHasGroundToStandOn(Env env) {
        final PluginHost host = host(env);

        final InstanceContainer world = core(env, host);

        assertTrue(env.process().instance().getInstances().contains(world));
        world.loadChunk(0, 0).join();
        assertEquals(Block.STONE, world.getBlock(0, (int) CoreMode.SPAWN.y() - 1, 0));
        assertEquals(Block.AIR, world.getBlock(0, (int) CoreMode.SPAWN.y(), 0));
    }

    /**
     * The core world ends at a world border 4,096 blocks across, centred on the origin: a client cannot
     * walk its chunk ring out of it, so the chunks it makes the world hold are bounded
     * ({@code docs/DESIGN-PROD.md} {@code API-PROD-030}, {@code docs/DESIGN-M2.md} §28.2 G3). A move out
     * across the wall at x 2,048 is turned back, one up to it is taken.
     */
    @Test
    void theCoreWorldEndsAtAWorldBorder(Env env) {
        final InstanceContainer world = core(env, host(env));
        assertEquals(new WorldBorder(4096, 0, 0, 5, 15), world.getWorldBorder());

        // The chunk past the wall loaded, so that the move into it is turned back for the border alone
        world.loadChunk(128, 0).join();
        final Pos edge = new Pos(2040.5, CoreMode.SPAWN.y(), 0.5);
        final Player player = env.createPlayer(world, edge);
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(2050.5, edge.y(), 0.5), true, false));
        player.interpretPacketQueue();
        assertEquals(edge, player.getPosition(), "a move to x 2,050.5 across the border at x 2,048");
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(2047.5, edge.y(), 0.5), true, false));
        player.interpretPacketQueue();
        assertEquals(new Pos(2047.5, edge.y(), 0.5), player.getPosition(), "a move to x 2,047.5, inside the border");
    }

    @Test
    void everyConnectionIsAdmittedIntoTheCoreWorld(Env env) {
        final PluginHost host = host(env);
        final InstanceContainer world = core(env, host);

        final AdmissionProvider provider = host.admissionProvider();
        assertNotNull(provider);
        assertEquals(new Admission.Accept(world, 0),
                provider.admit(new AdmissionRequest(UUID.randomUUID(), "probe", 0, 0, UUID.randomUUID(),
                        InetAddress.getLoopbackAddress())));

        try (LogCapture logs = LogCapture.of(AdmissionGate.class)) {
            final Player player = env.createPlayer(world, CoreMode.SPAWN);

            assertEquals(List.of(), logs.messagesAt(Level.INFO));
            assertTrue(player.getPlayerConnection().isOnline());
            assertEquals(world, player.getInstance());
        }
    }

    @Test
    void theDiagnosticChannelEchoesThePayloadBack(Env env) {
        final PluginHost host = host(env);
        final InstanceContainer world = core(env, host);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(world, CoreMode.SPAWN);
        final Collector<PluginMessagePacket> packets = connection.trackIncoming(PluginMessagePacket.class);

        env.process().eventHandler().call(new PlayerPluginMessageEvent(player, CoreMode.ECHO_CHANNEL, PAYLOAD));

        packets.assertSingle(packet -> assertEquals(new PluginMessagePacket(CoreMode.ECHO_CHANNEL, PAYLOAD), packet));
    }

    /**
     * A connection taken through the whole configuration is put where the core mode's own listener
     * says: the column the lit chunks are centred on ({@code docs/DESIGN-PROD.md} {@code API-PROD-031}).
     */
    @Test
    void aPlayerWhoJoinsIsPutAtTheSpawn(Env env) {
        final InstanceContainer world = core(env, host(env));

        final Player player = join(env, new Wire(env.process()));

        assertEquals(world, player.getInstance());
        assertEquals(CoreMode.SPAWN, player.getRespawnPoint(), "the respawn point the configuration left");
        assertEquals(CoreMode.SPAWN, player.getPosition(), "where a player who joined stands");
    }

    /**
     * The chunks within the chunk view distance plus one of the spawn chunk are loaded and lit once
     * the core mode returns, which is before the port is bound ({@code docs/DESIGN-PROD.md}
     * {@code API-PROD-031}, §19). The light is read from the sections themselves:
     * {@code Instance#getSkyLight} would compute a missing one on the spot.
     */
    @Test
    void theChunksAroundTheSpawnAreLitBeforeAnybodyJoins(Env env) {
        final InstanceContainer world = core(env, host(env));

        final int radius = env.process().config().chunkViewDistance() + 1;
        assertEquals((2 * radius + 1) * (2 * radius + 1), world.getChunks().size(), "chunks loaded around the spawn");
        final int floor = (int) CoreMode.SPAWN.y();
        for (int x = CoreMode.SPAWN.chunkX() - radius; x <= CoreMode.SPAWN.chunkX() + radius; x++) {
            for (int z = CoreMode.SPAWN.chunkZ() - radius; z <= CoreMode.SPAWN.chunkZ() + radius; z++) {
                final Chunk chunk = world.getChunk(x, z);
                final String name = "chunk " + x + "," + z;
                assertNotNull(chunk, name);
                assertInstanceOf(LightingChunk.class, chunk, name);
                assertEquals(15, computedSkyLight(chunk, floor), name + ": the air on the floor");
                assertEquals(15, computedSkyLight(chunk, floor + 16), name + ": the air a section higher");
                assertEquals(0, computedSkyLight(chunk, floor - 1), name + ": the top layer of stone");
            }
        }
    }

    /**
     * What a player joining at the spawn is sent for the chunk it stands in carries the sky light: lit
     * air on the floor, dark stone under it.
     */
    @Test
    void theChunkAPlayerSpawnsInIsSentLit(Env env) {
        core(env, host(env));
        final Wire wire = new Wire(env.process());

        join(env, wire);

        final int chunkX = CoreMode.SPAWN.chunkX(), chunkZ = CoreMode.SPAWN.chunkZ();
        assertTrue(env.tickWhile(() -> wire.chunk(chunkX, chunkZ) == null, Duration.ofSeconds(10)),
                "the chunk of the spawn is sent");
        final LightData light = Objects.requireNonNull(wire.chunk(chunkX, chunkZ)).lightData();
        final int floor = (int) CoreMode.SPAWN.y();
        assertEquals(15, sentSkyLight(light, floor), "the air on the floor");
        assertEquals(0, sentSkyLight(light, floor - 1), "the top layer of stone");
    }

    /**
     * A chunk first generated after the start, out of the lit ring, as a walk away from the spawn
     * generates them, gets its light when its first packet is built, and the same sky light as the
     * spawn chunk was lit with.
     */
    @Test
    void aChunkGeneratedAfterTheStartIsLitWhenItIsFirstSent(Env env) {
        final InstanceContainer world = core(env, host(env));
        final int farX = CoreMode.SPAWN.chunkX() + env.process().config().chunkViewDistance() + 20;
        assertNull(world.getChunk(farX, 0), "the chunk is not one the start loaded");

        final LightData far = lightSent(world.loadChunk(farX, 0).join());

        final int floor = (int) CoreMode.SPAWN.y();
        assertEquals(15, sentSkyLight(far, floor), "the air on the floor");
        assertEquals(0, sentSkyLight(far, floor - 1), "the top layer of stone");
        final LightData spawn = lightSent(world.loadChunk(CoreMode.SPAWN.chunkX(), CoreMode.SPAWN.chunkZ()).join());
        assertEquals(spawn.skyMask(), far.skyMask(), "the sections carrying sky light");
        assertEquals(spawn.emptySkyMask(), far.emptySkyMask(), "the sections without sky light");
        for (int i = 0; i < spawn.skyLight().size(); i++)
            assertArrayEquals(spawn.skyLight().get(i), far.skyLight().get(i), "sky light array " + i);
    }

    private static int computedSkyLight(Chunk chunk, int y) {
        final Light light = chunk.getSectionAt(y).skyLight();
        assertFalse(light.requiresUpdate(), "the section holding y " + y
                + " is still waiting for its light, which would be computed only when it is sent");
        return light.getLevel(0, y & 15, 0);
    }

    private static LightData lightSent(Chunk chunk) {
        return ((ChunkDataPacket) Objects.requireNonNull(
                SendablePacket.extractServerPacket(ConnectionState.PLAY, chunk.getFullDataPacket()))).lightData();
    }

    /**
     * The sky light a chunk packet carries for the block at x 0, z 0 of its chunk and the given
     * height, read as the client reads it: the core world starts at y 0, and the mask bit of a
     * section is its index plus one, bit 0 being the section below the world.
     */
    private static int sentSkyLight(LightData light, int y) {
        final int bit = y / 16 + 1;
        assertTrue(light.skyMask().get(bit),
                "the packet carries the sky light of the section holding y " + y + ", sky mask " + light.skyMask());
        final byte[] section = light.skyLight().get(light.skyMask().get(0, bit).cardinality());
        final int index = (y & 15) << 8;
        return (section[index >>> 1] >>> ((index & 1) << 2)) & 0xF;
    }

    /**
     * Joins through the configuration the way the engine does, on a virtual thread and through the
     * whole host wiring, with nothing set on the way but what the process's listeners set.
     * {@link TestConnection#connect} sets a spawn on the player's own node, which runs after the
     * core mode's listener on the global one and would hide it.
     */
    private static Player join(Env env, Wire wire) {
        final Player player = env.process().connection().createPlayer(wire, new GameProfile(UUID.randomUUID(), "Joiner"));
        assertNotNull(player, "the uuid is free");
        CompletableFuture.runAsync(() -> {
            env.process().connection().doConfiguration(player, false);
            env.process().connection().transitionConfigToPlay(player);
        }, Thread.ofVirtual()::start).join();
        wire.setClientState(ConnectionState.PLAY);
        wire.setServerState(ConnectionState.PLAY);
        env.process().connection().updateWaitingPlayers();
        return player;
    }

    /** A connection that keeps what it is sent. */
    private static final class Wire extends PlayerConnection {

        private final List<ServerPacket> sent = new CopyOnWriteArrayList<>();

        Wire(ServerProcess process) {
            super(process);
            setClientState(ConnectionState.CONFIGURATION);
            setServerState(ConnectionState.CONFIGURATION);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
            final ServerPacket server = packet instanceof ServerPacket direct ? direct
                    : SendablePacket.extractServerPacket(getServerState(), packet);
            if (server != null) sent.add(server);
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }

        @Nullable ChunkDataPacket chunk(int chunkX, int chunkZ) {
            for (ServerPacket packet : sent) {
                if (packet instanceof ChunkDataPacket chunk && chunk.chunkX() == chunkX && chunk.chunkZ() == chunkZ)
                    return chunk;
            }
            return null;
        }
    }
}
