package net.minestom.server.entity;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.damage.Damage;
import net.minestom.server.entity.damage.DamageType;
import net.minestom.server.network.packet.server.play.EntityMetaDataPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class SwitchEntityTypeIntegrationTest {

    @Test
    public void metaChangeAfterSwitchMustNotReachSelf(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));
        player.switchEntityType(EntityType.SHEEP);

        var tracker = connection.trackIncoming(EntityMetaDataPacket.class);
        player.setSneaking(true);
        tracker.assertEmpty();
    }

    @Test
    public void metaChangeStillReachesAnUnswitchedPlayer(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        var tracker = connection.trackIncoming(EntityMetaDataPacket.class);
        player.setSneaking(true);
        tracker.assertAnyMatch(packet -> packet.entityId() == player.getEntityId());
    }

    @Test
    public void damageAfterSwitchDoesNotThrow(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        player.switchEntityType(EntityType.ZOMBIE);
        assertDoesNotThrow(() -> player.damage(new Damage(DamageType.GENERIC, null, null, null, 1f)));
        assertEquals(19f, player.getHealth());
    }

    @Test
    public void playerMetaIsNullAfterSwitch(Env env) {
        var instance = env.createFlatInstance();
        var player = env.createPlayer(instance, new Pos(0, 42, 0));
        player.switchEntityType(EntityType.ZOMBIE);
        assertNull(player.getPlayerMeta());
        assertDoesNotThrow(() -> player.refreshSettings(player.getSettings()));
    }

    @Test
    public void switchKeepsTheSharedMetadata() {
        var entity = new Entity(EntityType.ZOMBIE);
        entity.setGlowing(true);
        entity.setNoGravity(true);

        entity.switchEntityType(EntityType.SHEEP);

        assertTrue(entity.isGlowing(), "switchEntityType dropped the shared entity metadata");
        assertTrue(entity.hasNoGravity());
    }
}
