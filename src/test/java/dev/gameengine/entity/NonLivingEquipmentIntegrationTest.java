package dev.gameengine.entity;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.network.packet.server.play.EntityEquipmentPacket;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class NonLivingEquipmentIntegrationTest {

    @Test
    public void nonLivingEntityDoesNotSendEquipment(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var viewer = connection.connect(instance, new Pos(0, 42, 0));

        // A LivingEntity with a non-living type is legal in Minestom and used for custom entities
        var arrow = new LivingEntity(EntityType.ARROW);
        arrow.setInstance(instance, new Pos(0, 42, 5000)).join();

        var tracker = connection.trackIncoming(EntityEquipmentPacket.class);
        assertTrue(arrow.addViewer(viewer));
        tracker.assertEmpty();
    }

    @Test
    public void livingEntityStillSendsEquipment(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var viewer = connection.connect(instance, new Pos(0, 42, 0));

        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 5000)).join();

        var tracker = connection.trackIncoming(EntityEquipmentPacket.class);
        assertTrue(zombie.addViewer(viewer));
        tracker.assertAny();
    }
}
