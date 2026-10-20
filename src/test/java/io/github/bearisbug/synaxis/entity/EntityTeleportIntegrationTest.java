package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.RelativeFlags;
import io.github.bearisbug.synaxis.instance.Chunk;
import io.github.bearisbug.synaxis.instance.ChunkLoader;
import io.github.bearisbug.synaxis.instance.EntityTracker;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityHeadLookPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityPositionSyncPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.PlayerPositionAndLookPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityTeleportIntegrationTest {

    /** Holds the chunks from chunk x 10 on back until the gate opens, the generator fills them then. */
    private record GatedChunkLoader(CountDownLatch gate) implements ChunkLoader {
        @Override
        public @Nullable Chunk loadChunk(Instance instance, int chunkX, int chunkZ) {
            if (chunkX >= 10) {
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
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

    @Test
    public void entityChunkTeleport(Env env) {
        var instance = env.createFlatInstance();
        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        assertEquals(instance, entity.getInstance());
        assertEquals(new Pos(0, 42, 0), entity.getPosition());

        entity.teleport(new Pos(1, 42, 1)).join();
        assertEquals(new Pos(1, 42, 1), entity.getPosition());
    }

    @Test
    public void entityTeleport(Env env) {
        var instance = env.createFlatInstance();
        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        assertEquals(instance, entity.getInstance());
        assertEquals(new Pos(0, 42, 0), entity.getPosition());

        entity.teleport(new Pos(52, 42, 52)).join();
        assertEquals(new Pos(52, 42, 52), entity.getPosition());
    }

    @Test
    public void playerChunkTeleport(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));
        assertEquals(instance, player.getInstance());
        assertEquals(new Pos(0, 40, 0), player.getPosition());

        var viewerConnection = env.createConnection();
        viewerConnection.connect(instance, new Pos(0, 40, 0));

        var tracker = connection.trackIncoming(ServerPacket.class);
        var viewerTracker = viewerConnection.trackIncoming(ServerPacket.class);
        var teleportPosition = new Pos(1, 42, 1).withYaw(5);
        player.teleport(teleportPosition).join();
        assertEquals(teleportPosition, player.getPosition());

        // Verify received packet(s)
        tracker.assertSingle(PlayerPositionAndLookPacket.class,
                packet -> assertEquals(teleportPosition, packet.position()));
        // Verify broadcast packet(s): the position synchronization of a living entity carries its head, and the turn
        // sends it once more
        viewerTracker.assertCount(3);
        viewerTracker.assertAnyMatch(packet -> {
            if (!(packet instanceof EntityPositionSyncPacket syncPacket)) return false;
            assertEquals(player.getEntityId(), syncPacket.entityId());
            assertEquals(teleportPosition, syncPacket.position());
            assertEquals(teleportPosition.yaw(), syncPacket.yaw());
            return true;
        });
        viewerTracker.assertAnyMatch(packet -> {
            if (!(packet instanceof EntityHeadLookPacket headLookPacket)) return false;
            assertEquals(player.getEntityId(), headLookPacket.entityId());
            assertEquals(teleportPosition.yaw(), headLookPacket.yaw());
            return true;
        });
    }

    @Test
    public void teleportResetsIndependentHeadRotation(Env env) {
        var instance = env.createFlatInstance();
        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();

        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 42, 0));
        entity.setView(0, 0, 90);

        var tracker = connection.trackIncoming(ServerPacket.class);
        entity.teleport(new Pos(1, 42, 1)).join();

        tracker.assertCount(2);
        tracker.assertCount(1, EntityPositionSyncPacket.class::isInstance);
        tracker.assertCount(1, EntityHeadLookPacket.class::isInstance);
        tracker.assertAnyMatch(packet -> {
            if (!(packet instanceof EntityHeadLookPacket headLookPacket)) return false;
            assertEquals(entity.getEntityId(), headLookPacket.entityId());
            assertEquals(0, headLookPacket.yaw());
            return true;
        });
    }

    @Test
    public void playerTeleport(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 40, 0));
        assertEquals(instance, player.getInstance());
        assertEquals(new Pos(0, 40, 0), player.getPosition());

        var viewerConnection = env.createConnection();
        viewerConnection.connect(instance, new Pos(0, 40, 0));

        var teleportPosition = new Pos(4999, 42, 4999);
        player.teleport(teleportPosition).join();
        assertEquals(teleportPosition, player.getPosition());
    }

    @Test
    public void playerTeleportWithFlagsTest(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 0, 0));

        player.teleport(new Pos(10, 10, 10, 90, 0)).join();
        assertEquals(new Pos(10, 10, 10, 90, 0), player.getPosition());

        player.teleport(new Pos(0, 0, 0, 0, 0), null, RelativeFlags.ALL).join();
        assertEquals(new Pos(10, 10, 10, 90, 0), player.getPosition());

        player.teleport(new Pos(5, 10, 2, 5, 5), null, RelativeFlags.VIEW).join();
        assertEquals(new Pos(5, 10, 2, 95, 5), player.getPosition());
    }

    @Test
    public void entityTeleportToInfinity(Env env) throws ExecutionException, InterruptedException, TimeoutException {
        var instance = env.createFlatInstance();
        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 42, 0)).join();
        assertEquals(instance, entity.getInstance());
        assertEquals(new Pos(0, 42, 0), entity.getPosition());

        entity.teleport(new Pos(Double.POSITIVE_INFINITY, 42, 52)).join();
        CompletableFuture.runAsync(() -> entity.tick(0 /* 0 is fine here, it's just a delta*/))
                .get(10, TimeUnit.SECONDS);
        // This should not hang forever

        // The position should have been capped at 2 billion.
        assertEquals(new Pos(Entity.MAX_COORDINATE, 42, 52), entity.getPosition());
    }

    @Test
    public void aTeleportIntoAChunkThatDoesNotLoadLeavesTheEntityWhereItWas(Env env) {
        var instance = env.createFlatInstance();
        instance.enableAutoChunkLoad(false);
        instance.loadChunk(0, 0).join();
        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0.5, 42, 0.5)).join();
        var chunk = entity.getChunk();

        var thrown = assertThrows(CompletionException.class, () -> entity.teleport(new Pos(4000, 42, 4000)).join());

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertEquals(new Pos(0.5, 42, 0.5), entity.getPosition());
        assertSame(chunk, entity.getChunk());
        assertTrue(instance.getEntityTracker().chunkEntities(0, 0, EntityTracker.Target.ENTITIES).contains(entity),
                "the tracker moved the entity to the chunk it never got into");
    }

    /** Removed while the target chunk loads: the teleport completes without touching the entity again. */
    @Test
    public void aTeleportOfAnEntityRemovedWhileTheChunkLoadsCompletes(Env env) {
        final CountDownLatch gate = new CountDownLatch(1);
        final Instance instance = env.createFlatInstance(new GatedChunkLoader(gate));
        final Entity entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0.5, 42, 0.5)).join();

        final CompletableFuture<Void> teleport = entity.teleport(new Pos(200.5, 42, 0.5));
        assertFalse(teleport.isDone(), "the target chunk was not held back");
        entity.remove();
        gate.countDown();

        assertDoesNotThrow(() -> teleport.get(10, TimeUnit.SECONDS));
        assertNull(entity.getInstance());
    }

    /**
     * Sent to another instance while the target chunk loads: the teleport completes and the entity stays where the
     * other instance put it, although that instance has the target chunk loaded too.
     */
    @Test
    public void aTeleportOfAnEntityThatChangedInstanceWhileTheChunkLoadsLeavesItThere(Env env) {
        final CountDownLatch gate = new CountDownLatch(1);
        final Instance instance = env.createFlatInstance(new GatedChunkLoader(gate));
        final Instance other = env.createFlatInstance();
        other.loadChunk(12, 0).join();
        final Entity entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, new Pos(0.5, 42, 0.5)).join();

        final CompletableFuture<Void> teleport = entity.teleport(new Pos(200.5, 42, 0.5));
        assertFalse(teleport.isDone(), "the target chunk was not held back");
        entity.setInstance(other, new Pos(1.5, 42, 1.5)).join();
        gate.countDown();

        assertDoesNotThrow(() -> teleport.get(10, TimeUnit.SECONDS));
        assertSame(other, entity.getInstance());
        assertEquals(new Pos(1.5, 42, 1.5), entity.getPosition());
    }
}
