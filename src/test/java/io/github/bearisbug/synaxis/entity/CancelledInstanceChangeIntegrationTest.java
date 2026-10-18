package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.instance.AddEntityToInstanceEvent;
import io.github.bearisbug.synaxis.event.player.PlayerSpawnEvent;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.block.Block;
import io.github.bearisbug.synaxis.network.packet.server.play.RespawnPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UnloadChunkPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateViewDistancePacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.world.DimensionType;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class CancelledInstanceChangeIntegrationTest {

    @Test
    public void cancelledSpawnReturnsAFailedFuture(Env env) {
        var instance = env.createFlatInstance();
        env.process().eventHandler().addListener(AddEntityToInstanceEvent.class, event -> event.setCancelled(true));

        var entity = new Entity(EntityType.ZOMBIE);
        var future = entity.setInstance(instance, new Pos(0, 42, 0));

        assertNotNull(future, "setInstance must never return null");
        assertThrows(CompletionException.class, future::join);
        assertNull(entity.getInstance());
    }

    @Test
    public void cancelledPlayerSpawnFailsAndKeepsThePlayerInItsInstance(Env env) {
        var origin = env.createFlatInstance();
        var target = env.createFlatInstance();
        var player = env.createPlayer(origin, new Pos(0, 42, 0));
        env.process().eventHandler().addListener(AddEntityToInstanceEvent.class, event -> event.setCancelled(true));
        var spawns = env.trackEvent(PlayerSpawnEvent.class, EventFilter.PLAYER, player);

        final CompletableFuture<Void> change = player.setInstance(target, new Pos(0, 42, 0));

        final CompletionException failure = assertThrows(CompletionException.class, change::join);
        assertInstanceOf(AddEntityToInstanceEvent.CancelledException.class, failure.getCause());
        assertSame(origin, player.getInstance(), "a cancelled spawn must not half-move the player");
        spawns.assertEmpty();
    }

    /** Asked before the client hears of the new dimension: a cancelled change sends it nothing. */
    @Test
    public void aCancelledChangeOfDimensionSendsTheClientNothing(Env env) {
        var origin = env.createFlatInstance();
        var nether = env.process().instance().createInstanceContainer(DimensionType.THE_NETHER);
        nether.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));
        nether.viewDistance(origin.viewDistance() - 1);
        var connection = env.createConnection();
        var player = connection.connect(origin, new Pos(0, 42, 0));
        env.process().eventHandler().addListener(AddEntityToInstanceEvent.class, event -> event.setCancelled(true));
        var respawns = connection.trackIncoming(RespawnPacket.class);
        var unloads = connection.trackIncoming(UnloadChunkPacket.class);
        var viewDistances = connection.trackIncoming(UpdateViewDistancePacket.class);

        final CompletableFuture<Void> change = player.setInstance(nether, new Pos(0, 42, 0));

        assertThrows(CompletionException.class, change::join);
        respawns.assertEmpty();
        unloads.assertEmpty();
        viewDistances.assertEmpty();
        assertSame(origin, player.getInstance());
    }

    /** An instance unregistered while the chunks of the change load is refused at the spawn, before any packet. */
    @Test
    public void anInstanceUnregisteredWhileItsChunksLoadIsRefusedAtTheSpawn(Env env) {
        var origin = env.createFlatInstance();
        var gate = new CountDownLatch(1);
        var target = env.createFlatInstance(new GatedChunkLoader(gate));
        var connection = env.createConnection();
        var player = connection.connect(origin, new Pos(0, 42, 0));
        var respawns = connection.trackIncoming(RespawnPacket.class);
        var unloads = connection.trackIncoming(UnloadChunkPacket.class);

        final CompletableFuture<Void> change;
        try {
            // Far enough that the old ring is left entirely, which would be unloaded at the spawn
            change = player.setInstance(target, new Pos(320, 42, 320));
            env.destroyInstance(target);
        } finally {
            gate.countDown();
        }

        final CompletionException failure = assertThrows(CompletionException.class, change::join);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        respawns.assertEmpty();
        unloads.assertEmpty();
        assertSame(origin, player.getInstance());
    }

    /** Holds every chunk load back until the gate opens, then lets the generator fill the chunk. */
    private record GatedChunkLoader(CountDownLatch gate) implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return null;
        }

        @Override
        public void saveChunk(Chunk chunk) {
        }

        @Override
        public boolean supportsParallelLoading() {
            return true;
        }
    }
}
