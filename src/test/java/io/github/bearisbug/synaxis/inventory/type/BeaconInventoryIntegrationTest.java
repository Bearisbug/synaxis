package io.github.bearisbug.synaxis.inventory.type;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.inventory.InventoryProperty;
import io.github.bearisbug.synaxis.network.packet.server.play.WindowPropertyPacket;
import io.github.bearisbug.synaxis.potion.PotionEffect;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The 26.2 client decodes a beacon potion property as 0 for none and otherwise the effect registry id + 1.
 */
@EnvTest
public class BeaconInventoryIntegrationTest {

    @Test
    public void potionPropertiesAreSentTheWayTheClientReadsThem(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        var beacon = new BeaconInventory("beacon");
        player.openInventory(beacon);

        var first = connection.trackIncoming(WindowPropertyPacket.class);
        beacon.setFirstPotionEffect(PotionEffect.SPEED);
        first.assertSingle(packet -> {
            assertEquals(InventoryProperty.BEACON_FIRST_POTION.getProperty(), packet.property());
            assertEquals(PotionEffect.SPEED.id() + 1, packet.value());
        });

        var second = connection.trackIncoming(WindowPropertyPacket.class);
        beacon.setSecondPotionEffect(PotionEffect.REGENERATION);
        second.assertSingle(packet -> {
            assertEquals(InventoryProperty.BEACON_SECOND_POTION.getProperty(), packet.property());
            assertEquals(PotionEffect.REGENERATION.id() + 1, packet.value());
        });

        var none = connection.trackIncoming(WindowPropertyPacket.class);
        beacon.setFirstPotionEffect(null);
        beacon.setSecondPotionEffect(null);
        none.assertCount(2, packet -> packet.value() == 0);
    }
}
