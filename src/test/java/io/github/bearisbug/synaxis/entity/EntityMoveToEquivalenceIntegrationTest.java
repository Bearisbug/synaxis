package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Entity#moveTo(Pos)} is the public name of {@code refreshPosition(pos, false, true)}, so the two
 * must stay indistinguishable from the wire: same packet types, same order, same fields.
 */
@EnvTest
public class EntityMoveToEquivalenceIntegrationTest {

    private static final Pos SPAWN = new Pos(15.5, 40, 0.5);

    @Test
    public void moveToProducesTheSamePacketsAsRefreshPositionWithinAChunk(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        var connection = env.createConnection();
        connection.connect(instance, SPAWN);
        var entity = spawn(instance, SPAWN);

        var packets = assertSamePacketSequence(connection, entity, new Pos(15.9, 40, 1.2).withYaw(30));
        assertFalse(packets.isEmpty(), "a move seen by a viewer has to send something");
    }

    @Test
    public void moveToProducesTheSamePacketsAsRefreshPositionAcrossAChunkBorder(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        instance.loadChunk(1, 0).join();
        var connection = env.createConnection();
        connection.connect(instance, SPAWN);
        var entity = spawn(instance, SPAWN);

        var packets = assertSamePacketSequence(connection, entity, new Pos(16.5, 40, 0.5).withYaw(90));
        assertFalse(packets.isEmpty(), "a move seen by a viewer has to send something");
        assertEquals(1, entity.getChunk().getChunkX(), "the entity has to end up in the neighbouring chunk");
    }

    @Test
    public void moveToProducesTheSamePacketsAsRefreshPositionOverATeleportDistance(Env env) {
        var instance = env.createFlatInstance();
        instance.loadChunk(0, 0).join();
        instance.loadChunk(2, 0).join();
        var connection = env.createConnection();
        connection.connect(instance, SPAWN);
        var entity = spawn(instance, SPAWN);

        // Farther than 8 blocks, which is the branch sending an absolute EntityTeleportPacket
        var packets = assertSamePacketSequence(connection, entity, new Pos(40.5, 40, 0.5));
        assertFalse(packets.isEmpty(), "a move seen by a viewer has to send something");
    }

    @Test
    public void moveToProducesTheSamePacketsAsRefreshPositionWithoutViewers(Env env) {
        var watchedInstance = env.createFlatInstance();
        watchedInstance.loadChunk(0, 0).join();
        var connection = env.createConnection();
        connection.connect(watchedInstance, SPAWN);

        var emptyInstance = env.createFlatInstance();
        emptyInstance.loadChunk(0, 0).join();
        emptyInstance.loadChunk(1, 0).join();
        var entity = spawn(emptyInstance, SPAWN);
        assertTrue(entity.getViewers().isEmpty(), "the entity is alone in its instance");

        var packets = assertSamePacketSequence(connection, entity, new Pos(16.5, 40, 0.5).withYaw(90));
        assertTrue(packets.isEmpty(), "a move nobody watches sends nothing");
    }

    /**
     * Moves the entity to {@code target} twice from the very same state — once through the internal primitive,
     * once through the public entry point — and asserts both windows captured the same packets.
     *
     * @return the packets the internal primitive sent, so the caller can assert the comparison was not vacuous
     */
    private static List<ServerPacket> assertSamePacketSequence(TestConnection connection, Entity entity, Pos target) {
        final Pos start = entity.getPosition();

        final List<ServerPacket> viaRefreshPosition = capture(connection, () -> entity.refreshPosition(target, false, true));
        assertEquals(target, entity.getPosition());

        // Rewind, so that the second window starts from the state the first one started from
        entity.refreshPosition(start, false, true);
        assertEquals(start, entity.getPosition());

        final List<ServerPacket> viaMoveTo = capture(connection, () -> entity.moveTo(target));
        assertEquals(target, entity.getPosition());

        assertEquals(viaRefreshPosition, viaMoveTo,
                "moveTo(pos) has to be refreshPosition(pos, false, true) down to the packets it sends");
        return viaRefreshPosition;
    }

    private static List<ServerPacket> capture(TestConnection connection, Runnable movement) {
        var tracker = connection.trackIncoming(ServerPacket.class);
        movement.run();
        return tracker.collect();
    }

    private static Entity spawn(Instance instance, Pos pos) {
        var entity = new Entity(EntityTypes.ZOMBIE);
        entity.setInstance(instance, pos).join();
        return entity;
    }
}
