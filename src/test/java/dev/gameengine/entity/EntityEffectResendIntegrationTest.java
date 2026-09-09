package dev.gameengine.entity;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.network.packet.server.play.EntityEffectPacket;
import dev.gameengine.potion.Potion;
import dev.gameengine.potion.PotionEffect;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class EntityEffectResendIntegrationTest {

    @Test
    public void newViewerReceivesActiveEffects(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var viewer = connection.connect(instance, new Pos(0, 42, 0));

        var zombie = new LivingEntity(EntityType.ZOMBIE);
        zombie.setInstance(instance, new Pos(0, 42, 5000)).join(); // Out of view distance
        zombie.addEffect(new Potion(PotionEffect.SPEED, 0, 200));

        var tracker = connection.trackIncoming(EntityEffectPacket.class);
        assertTrue(zombie.addViewer(viewer));
        tracker.assertSingle(packet -> {
            assertEquals(zombie.getEntityId(), packet.entityId());
            assertEquals(PotionEffect.SPEED, packet.potion().effect());
        });
    }

    @Test
    public void respawnResendsOwnEffects(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        player.addEffect(new Potion(PotionEffect.SPEED, 0, 200));
        player.kill();

        var tracker = connection.trackIncoming(EntityEffectPacket.class);
        player.respawn();
        tracker.assertAnyMatch(packet -> packet.entityId() == player.getEntityId()
                && packet.potion().effect() == PotionEffect.SPEED);
    }
}
