package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.attribute.Attribute;
import io.github.bearisbug.synaxis.network.packet.server.play.UpdateHealthPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.thread.TickThread;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnvTest
public class LivingEntityHealthIntegrationTest {

    @Test
    public void livingEntitySpawnsAtFullHealth() {
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        assertEquals(20f, zombie.getHealth(), "a living entity must not spawn with a single health point");
        assertEquals(20f, zombie.getLivingEntityMeta().getHealth(), "health metadata was never written");
    }

    @Test
    public void playerHealthPacketIsClamped(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        var tracker = connection.trackIncoming(UpdateHealthPacket.class);
        player.setHealth(1000f);
        tracker.assertAllMatch(packet -> packet.health() == 20f);
        assertEquals(20f, player.getHealth());
    }

    @Test
    public void aNaNHealthIsRefused() {
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        assertThrows(IllegalArgumentException.class, () -> zombie.setHealth(Float.NaN));
        assertEquals(20f, zombie.getHealth());
    }

    /** A NaN maximum used to make every later setHealth throw, healing and respawning included. */
    @Test
    public void aNaNMaximumHealthIsItsMinimum() {
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        assertDoesNotThrow(() -> zombie.getAttribute(Attribute.MAX_HEALTH).setBaseValue(Double.NaN));
        assertEquals(Attribute.MAX_HEALTH.minValue(), zombie.getAttributeValue(Attribute.MAX_HEALTH));
        assertDoesNotThrow(zombie::heal);
        assertEquals((float) Attribute.MAX_HEALTH.minValue(), zombie.getHealth());
    }

    @Test
    public void loweringMaxHealthClampsCurrentHealth() {
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        assertEquals(20f, zombie.getHealth());

        zombie.getAttribute(Attribute.MAX_HEALTH).setBaseValue(5);
        assertEquals(5f, zombie.getHealth(), "current health was left above the new maximum");
    }

    /**
     * Before a tick thread owns the entity, a thread that is not a tick thread may write its health
     * (a first {@code PlayerSpawnEvent} runs on the tick scheduler thread), and lowers it at once.
     */
    @Test
    public void loweringMaxHealthBeforeATickThreadOwnsTheEntityClampsAtOnce() {
        var zombie = new LivingEntity(EntityType.ZOMBIE);
        // On a thread of its own: neither the creating thread nor a tick thread
        CompletableFuture.runAsync(() -> zombie.getAttribute(Attribute.MAX_HEALTH).setBaseValue(5),
                Thread.ofVirtual()::start).join();
        assertEquals(5f, zombie.getHealth(), "current health was left above the new maximum");
    }

    /**
     * A thread that does not own the entity lowers its maximum health: the health is left to the
     * thread owning it, which lowers it at its next tick.
     */
    @Test
    public void loweringMaxHealthFromAnotherThreadClampsOnTheOwningThread(Env env) {
        var instance = env.createFlatInstance();
        var zombie = new LivingEntity(EntityType.ZOMBIE) {
            volatile @Nullable Thread loweredOn;

            @Override
            public void setHealth(float health) {
                if (health < getHealth()) loweredOn = Thread.currentThread();
                super.setHealth(health);
            }
        };
        zombie.setInstance(instance, new Pos(0, 42, 0)).join();
        env.tick(); // Let the dispatcher assign a tick thread
        final TickThread owner = zombie.acquirable().assignedThread();
        assertNotNull(owner);

        zombie.getAttribute(Attribute.MAX_HEALTH).setBaseValue(5); // From this thread, which does not own it
        assertEquals(20f, zombie.getHealth(), "lowered by a thread that does not own the zombie");
        env.tick();
        assertEquals(5f, zombie.getHealth(), "current health was left above the new maximum");
        assertSame(owner, zombie.loweredOn);
    }
}
