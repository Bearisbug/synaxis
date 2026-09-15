package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.event.entity.EntityTickEvent;
import io.github.bearisbug.synaxis.item.ItemStack;
import io.github.bearisbug.synaxis.item.Material;
import io.github.bearisbug.synaxis.network.packet.server.play.DestroyEntitiesPacket;
import io.github.bearisbug.synaxis.timer.TimeUnit;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.time.temporal.TemporalUnit;
import java.util.List;

import static io.github.bearisbug.synaxis.testing.TestUtils.waitUntilCleared;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityRemovalIntegrationTest {

    @Test
    public void entityNeverPlacedInAnInstanceStaysUsable() {
        // Such an entity belongs to no server process, so its events reach no listener; that must
        // not stop it from being equipped, pushed around or thrown away.
        var entity = new LivingEntity(EntityType.ZOMBIE);
        assertDoesNotThrow(() -> entity.setEquipment(EquipmentSlot.HELMET, ItemStack.of(Material.DIAMOND_HELMET)));
        assertEquals(ItemStack.of(Material.DIAMOND_HELMET), entity.getEquipment(EquipmentSlot.HELMET));
        assertDoesNotThrow(() -> entity.setVelocity(new Vec(1, 0, 0)));
        assertDoesNotThrow(() -> entity.remove());
        assertTrue(entity.isRemoved());
    }

    @Test
    public void destructionPacket(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 40, 0));

        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 40, 0)).join();

        var tracker = connection.trackIncoming(DestroyEntitiesPacket.class);
        entity.remove();
        tracker.assertSingle(packet -> assertEquals(List.of(entity.getEntityId()), packet.entityIds()));
    }

    @Test
    public void instanceRemoval(Env env) {
        var instance = env.createFlatInstance();
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 40, 0)).join();
        assertFalse(entity.isRemoved());

        entity.remove();
        assertTrue(entity.isRemoved());
        assertFalse(instance.getEntities().contains(entity), "Entity must not be in the instance anymore");
    }

    @Test
    public void tickTimedRemoval(Env env) throws InterruptedException {
        var instance = env.createFlatInstance();
        var entity = new TestEntity(2, TimeUnit.SERVER_TICK);
        entity.setInstance(instance, new Pos(0, 40, 0)).join();

        assertFalse(entity.isRemoved());
        assertEquals(0, entity.getAliveTicks());

        Thread.sleep(150); // Ensure that time is not used for tick scheduling

        env.tick();
        assertFalse(entity.isRemoved());
        assertEquals(1, entity.getAliveTicks());

        env.tick();
        assertTrue(entity.isRemoved());
        assertEquals(1, entity.getAliveTicks());
    }

    @Test
    public void entityGC(Env env) {
        // Ensure that entities do not stay in memory after they are removed
        var instance = env.createFlatInstance();
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setInstance(instance, new Pos(0, 40, 0)).join();
        entity.remove();

        var ref = new WeakReference<>(entity);
        //noinspection UnusedAssignment
        entity = null;
        env.tick(); // Required to remove the entity from the thread dispatcher
        waitUntilCleared(ref);
    }

    @Test
    public void entityNodeGC(Env env) {
        // Ensure that the entities GCed when a local listener is present
        var node = env.process().eventHandler();
        var entity = new Entity(env.process(), EntityType.ZOMBIE);
        entity.eventNode().addListener(EntityTickEvent.class, _ -> {
        });
        node.call(new EntityTickEvent(entity));

        var ref = new WeakReference<>(entity);
        entity.remove();
        //noinspection UnusedAssignment
        entity = null;
        env.tick();
        waitUntilCleared(ref);
    }

    static final class TestEntity extends Entity {
        public TestEntity(long delay, TemporalUnit unit) {
            super(EntityType.ZOMBIE);
            scheduleRemove(delay, unit);
        }
    }
}
