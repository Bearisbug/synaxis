package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.network.packet.server.play.UpdateHealthPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
