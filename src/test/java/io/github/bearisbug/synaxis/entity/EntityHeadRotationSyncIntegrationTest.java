package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityHeadLookPacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The head rotation goes out with the scheduled position synchronization of an entity that has a head
 * ({@code docs/DESIGN-PROD.md} {@code API-PROD-020}): the head look packets of a move are sheddable, a
 * connection over its outbound backlog drops them, and the next synchronization puts the head back.
 */
@EnvTest
public class EntityHeadRotationSyncIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 42, 0.5);

    private static List<EntityHeadLookPacket> of(Collector<EntityHeadLookPacket> packets, Entity entity) {
        return packets.collect().stream().filter(packet -> packet.entityId() == entity.getEntityId()).toList();
    }

    /** The head look the turn sent is not counted: only the synchronization 5 ticks later is watched. */
    @Test
    public void aLivingEntitySendsItsHeadRotationWithItsPositionSync(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        connection.connect(instance, SPAWN);
        final EntityCreature zombie = new EntityCreature(EntityType.ZOMBIE);
        zombie.setNoGravity(true);
        zombie.setSynchronizationTicks(5);
        zombie.setInstance(instance, SPAWN.add(2, 0, 0)).join();
        zombie.setView(0, 0, 90);
        env.tick();

        final Collector<EntityHeadLookPacket> heads = connection.trackIncoming(EntityHeadLookPacket.class);
        for (int i = 0; i < 5; i++) env.tick();

        final List<EntityHeadLookPacket> sent = of(heads, zombie);
        assertFalse(sent.isEmpty(), "the position synchronization of a zombie carries no head rotation");
        assertTrue(sent.stream().allMatch(packet -> packet.yaw() == 90), sent::toString);
    }

    @Test
    public void anEntityWithoutAHeadSendsNoHeadRotationWithItsPositionSync(Env env) {
        final Instance instance = env.createFlatInstance();
        final TestConnection connection = env.createConnection();
        connection.connect(instance, SPAWN);
        final Entity display = new Entity(EntityType.ITEM_DISPLAY);
        display.setNoGravity(true);
        display.setSynchronizationTicks(5);
        display.setInstance(instance, SPAWN.add(2, 0, 0)).join();
        env.tick();

        final Collector<EntityHeadLookPacket> heads = connection.trackIncoming(EntityHeadLookPacket.class);
        for (int i = 0; i < 5; i++) env.tick();

        assertEquals(List.of(), of(heads, display));
    }
}
