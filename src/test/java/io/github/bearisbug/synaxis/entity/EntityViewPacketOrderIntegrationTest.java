package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.packet.server.ServerPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.DestroyEntitiesPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnEntityPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Showing and hiding an entity register under the two view mutexes and send their packets after releasing
 * them, so a show and a hide of the same pair on two threads can send in the other order than they
 * registered. Whatever the order, the client has to end up as the registration says.
 */
@EnvTest
public class EntityViewPacketOrderIntegrationTest {

    /** Stops in the packets of the next show or hide, once it is registered, until released. */
    private static final class ProbeEntity extends Entity {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean stopInShow;
        volatile boolean stopInHide;

        ProbeEntity() {
            super(EntityType.ZOMBIE);
        }

        @Override
        public void updateNewViewer(Player player) {
            if (stopInShow) {
                stopInShow = false;
                stop();
            }
            super.updateNewViewer(player);
        }

        @Override
        public void updateOldViewer(Player player) {
            if (stopInHide) {
                stopInHide = false;
                stop();
            }
            super.updateOldViewer(player);
        }

        private void stop() {
            entered.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Test
    public void anEntityRemovedWhileItsSpawnIsOnItsWayEndsDestroyed(Env env) throws InterruptedException {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        var probe = new ProbeEntity();
        probe.setInstance(instance, new Pos(2, 42, 0)).join();
        probe.viewEngine.viewableOption.removal.accept(player);
        assertFalse(probe.isViewer(player));

        var packets = connection.trackIncoming();
        probe.stopInShow = true;
        var show = Thread.ofPlatform().start(() -> probe.viewEngine.viewableOption.addition.accept(player));
        assertTrue(probe.entered.await(10, TimeUnit.SECONDS));
        // Registered, not sent yet: the removal hides it and sends the destruction first
        probe.remove();
        probe.release.countDown();
        show.join(10_000);

        assertInstanceOf(DestroyEntitiesPacket.class, lastSpawnOrDestroy(packets.collect(), probe),
                "the spawn went out after the destruction of an entity that is gone");
    }

    @Test
    public void anEntityShownWhileItsDestructionIsOnItsWayEndsSpawned(Env env) throws InterruptedException {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        var probe = new ProbeEntity();
        probe.setInstance(instance, new Pos(2, 42, 0)).join();
        assertTrue(probe.isViewer(player));

        var packets = connection.trackIncoming();
        probe.stopInHide = true;
        var hide = Thread.ofPlatform().start(() -> probe.viewEngine.viewableOption.removal.accept(player));
        assertTrue(probe.entered.await(10, TimeUnit.SECONDS));
        // Unregistered, not sent yet: the show registers it again and sends the spawn first
        probe.viewEngine.viewableOption.addition.accept(player);
        probe.release.countDown();
        hide.join(10_000);

        assertTrue(probe.isViewer(player));
        assertInstanceOf(SpawnEntityPacket.class, lastSpawnOrDestroy(packets.collect(), probe),
                "the player is registered as seeing an entity its client destroyed");
    }

    private static ServerPacket lastSpawnOrDestroy(List<ServerPacket> packets, Entity entity) {
        return packets.stream()
                .filter(packet -> (packet instanceof SpawnEntityPacket spawn && spawn.entityId() == entity.getEntityId())
                        || (packet instanceof DestroyEntitiesPacket destroy && destroy.entityIds().contains(entity.getEntityId())))
                .reduce((_, second) -> second)
                .orElseThrow();
    }
}
