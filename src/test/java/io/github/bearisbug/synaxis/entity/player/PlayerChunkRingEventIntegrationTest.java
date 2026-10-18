package io.github.bearisbug.synaxis.entity.player;

import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.CoordConversion;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.event.player.PlayerChunkUnloadEvent;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.client.common.ClientSettingsPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientPlayerPositionPacket;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientTeleportConfirmPacket;
import io.github.bearisbug.synaxis.network.packet.server.SendablePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.player.ClientSettings;
import io.github.bearisbug.synaxis.network.player.GameProfile;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every chunk of the ring of a player gets a {@link PlayerChunkUnloadEvent} in the node of its instance
 * when the player leaves it: the ring its view tickets hold, whose center the chunk update limiter can
 * keep away from the position of the player.
 */
@EnvTest
public class PlayerChunkRingEventIntegrationTest {

    @Test
    public void aRemovedPlayerReportsTheRingItsTicketsHold(Env env) {
        var instance = env.createFlatInstance();
        Player player = env.createConnection().connect(instance, new Pos(0.5, 40, 0.5));
        player.addPacketToQueue(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()));
        player.addPacketToQueue(new ClientSettingsPacket(ClientSettings.defaultSettings(2)));
        env.tick();
        final int radius = player.effectiveViewDistance();

        // Back to the chunk it started from: the limiter remembers chunks 0 to 2, so the ring stays on 2
        for (double x : new double[]{16.5, 32.5, 16.5, 0.5}) {
            player.addPacketToQueue(new ClientPlayerPositionPacket(new Vec(x, 40, 0.5), true, false));
            env.tick();
        }
        assertEquals(0, player.getPosition().chunkX());
        assertTrue(player.isChunkInRing(2 + radius, 0), "the ring did not stay where the limiter left it");

        final List<Long> unloaded = trackUnloads(instance, player);
        player.acquirable().sync(p -> p.remove());

        assertEquals(ring(2, 0, radius), new HashSet<>(unloaded), "the unloaded chunks must be the ring of the tickets");
        assertEquals(ChunkRange.chunksCount(radius), unloaded.size(), "a chunk was reported twice");
    }

    @Test
    public void aRespawnReportsTheRingTheClientDrops(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        Player player = connection.connect(instance, new Pos(0, 40, 0));
        final int radius = player.effectiveViewDistance();
        player.setHealth(0);

        final List<Long> unloaded = trackUnloads(instance, player);
        var unloadPackets = connection.trackIncoming(UnloadChunkPacket.class);
        player.respawn();

        assertEquals(ring(0, 0, radius), new HashSet<>(unloaded));
        assertEquals(ChunkRange.chunksCount(radius), unloaded.size());
        unloadPackets.assertEmpty(); // The client drops its chunks by itself on a respawn
    }

    @Test
    public void aChangeOfInstanceReportsTheRingInTheOldInstance(Env env) {
        var overworld = env.createFlatInstance();
        var sameDimension = env.createFlatInstance();
        var nether = env.process().instance().createInstanceContainer(DimensionType.THE_NETHER);
        nether.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        var connection = env.createConnection();
        Player player = connection.connect(overworld, new Pos(0, 40, 0));
        final int radius = player.effectiveViewDistance();

        final List<Long> leftSameDimension = trackUnloads(overworld, player);
        player.setInstance(sameDimension, new Pos(0, 40, 0)).join();
        assertSame(sameDimension, player.getInstance());
        assertEquals(ring(0, 0, radius), new HashSet<>(leftSameDimension), "the old instance must hear of its ring");

        final List<Long> leftForTheNether = trackUnloads(sameDimension, player);
        var unloadPackets = connection.trackIncoming(UnloadChunkPacket.class);
        player.setInstance(nether, new Pos(0, 40, 0)).join();
        assertSame(nether, player.getInstance());
        assertEquals(ring(0, 0, radius), new HashSet<>(leftForTheNether),
                "a change of dimension must report the ring too");
        assertEquals(ChunkRange.chunksCount(radius), leftForTheNether.size());
        unloadPackets.assertEmpty(); // The respawn packet drops the chunks of the old dimension
    }

    /** A connection dropped in play queues the removal of the player for its tick, still in its instance. */
    @Test
    public void aDisconnectedPlayerReportsItsRingInItsInstance(Env env) {
        var instance = env.createFlatInstance();
        // A connection that goes through PlayerConnection#disconnect, unlike the one of Env#createConnection
        final DroppingConnection connection = new DroppingConnection(env.process());
        connection.setClientState(ConnectionState.PLAY);
        connection.setServerState(ConnectionState.PLAY);
        final Player player = env.process().connection()
                .createPlayer(connection, new GameProfile(UUID.randomUUID(), "Dropped"));
        player.setInstance(instance, new Pos(0, 40, 0)).join();
        env.tick();
        final int radius = player.effectiveViewDistance();

        final List<Long> unloaded = trackUnloads(instance, player);
        connection.disconnect();
        assertFalse(player.isRemoved(), "the removal waits for the tick");
        env.tick();
        env.tick();

        assertTrue(player.isRemoved(), "the dropped connection took the player out");
        assertEquals(ring(0, 0, radius), new HashSet<>(unloaded), "the instance must hear of the whole ring");
        assertEquals(ChunkRange.chunksCount(radius), unloaded.size(), "a chunk was reported twice");
    }

    /** The chunks the node of {@code instance} hears unloaded for {@code player}, from now on. */
    private static List<Long> trackUnloads(Instance instance, Player player) {
        final List<Long> unloaded = new CopyOnWriteArrayList<>();
        instance.eventNode().addListener(PlayerChunkUnloadEvent.class, event -> {
            if (event.getPlayer() == player)
                unloaded.add(CoordConversion.chunkIndex(event.getChunkX(), event.getChunkZ()));
        });
        return unloaded;
    }

    private static Set<Long> ring(int x, int z, int radius) {
        final Set<Long> chunks = new HashSet<>();
        ChunkRange.chunksInRange(x, z, radius, (chunkX, chunkZ) -> chunks.add(CoordConversion.chunkIndex(chunkX, chunkZ)));
        return chunks;
    }

    private static final class DroppingConnection extends PlayerConnection {
        private DroppingConnection(ServerProcess process) {
            super(process);
        }

        @Override
        public void sendPacket(SendablePacket packet) {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return new InetSocketAddress("localhost", 25565);
        }
    }
}
