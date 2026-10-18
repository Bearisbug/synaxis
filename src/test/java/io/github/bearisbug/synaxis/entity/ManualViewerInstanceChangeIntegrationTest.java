package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.network.packet.server.play.DestroyEntitiesPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual viewing is recorded on the viewed entity only: a player leaving its instance has to be dropped
 * from it there, the client keeps a frozen entity otherwise, and the server a reference to the player.
 */
@EnvTest
public class ManualViewerInstanceChangeIntegrationTest {

    @Test
    public void aPlayerChangingInstanceIsDroppedFromItsManualViews(Env env) {
        var instance = env.createFlatInstance();
        var other = env.createFlatInstance();
        var hologram = new Entity(EntityType.ARMOR_STAND);
        hologram.setAutoViewable(false);
        hologram.setInstance(instance, new Pos(0, 42, 0)).join();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 2));
        assertTrue(hologram.addViewer(player));

        // Same dimension: no respawn clears the entities of the client
        var destroys = connection.trackIncoming(DestroyEntitiesPacket.class);
        player.setInstance(other, new Pos(0, 42, 2)).join();

        destroys.assertAnyMatch(packet -> packet.entityIds().contains(hologram.getEntityId()));
        assertFalse(hologram.isViewer(player));
        assertFalse(hologram.removeViewer(player), "the player is still registered as a manual viewer");
    }

    @Test
    public void aManualViewerHasToShareTheInstance(Env env) {
        var instance = env.createFlatInstance();
        var other = env.createFlatInstance();
        var hologram = new Entity(EntityType.ARMOR_STAND);
        hologram.setInstance(instance, new Pos(0, 42, 0)).join();
        var player = env.createPlayer(other, new Pos(0, 42, 0));

        assertThrows(IllegalArgumentException.class, () -> hologram.addViewer(player));
        assertFalse(hologram.isViewer(player));
    }
}
