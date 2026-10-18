package io.github.bearisbug.synaxis.entity;

import net.kyori.adventure.text.Component;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.damage.Damage;
import io.github.bearisbug.synaxis.entity.damage.DamageType;
import io.github.bearisbug.synaxis.entity.metadata.display.TextDisplayMeta;
import io.github.bearisbug.synaxis.network.packet.server.play.EntityMetaDataPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SetPassengersPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    public void switchingAVehicleSendsItsPassengersAgain(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 42, 0));
        var vehicle = new Entity(EntityType.HORSE);
        vehicle.setInstance(instance, new Pos(2, 42, 0)).join();
        var passenger = new Entity(EntityType.ZOMBIE);
        passenger.setInstance(instance, new Pos(2, 42, 0)).join();
        vehicle.addPassenger(passenger);

        var passengers = connection.trackIncoming(SetPassengersPacket.class);
        vehicle.switchEntityType(EntityType.PIG);

        // The destruction dismounts the passenger on the client, without this it shows up on foot
        passengers.assertSingle(packet -> {
            assertEquals(vehicle.getEntityId(), packet.vehicleEntityId());
            assertEquals(List.of(passenger.getEntityId()), packet.passengersId());
        });
    }

    @Test
    public void switchingAPassengerSendsTheVehiclePassengersAgain(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 42, 0));
        var vehicle = new Entity(EntityType.HORSE);
        vehicle.setInstance(instance, new Pos(2, 42, 0)).join();
        var passenger = new Entity(EntityType.ZOMBIE);
        passenger.setInstance(instance, new Pos(2, 42, 0)).join();
        vehicle.addPassenger(passenger);

        var passengers = connection.trackIncoming(SetPassengersPacket.class);
        passenger.switchEntityType(EntityType.SKELETON);

        passengers.assertSingle(packet -> {
            assertEquals(vehicle.getEntityId(), packet.vehicleEntityId());
            assertEquals(List.of(passenger.getEntityId()), packet.passengersId());
        });
    }

    @Test
    public void aMetaTakenBeforeTheSwitchRefusesWrites(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 42, 0));
        var entity = new Entity(EntityType.TEXT_DISPLAY);
        entity.setInstance(instance, new Pos(2, 42, 0)).join();
        var label = (TextDisplayMeta) entity.getEntityMeta();

        entity.switchEntityType(EntityType.ITEM_DISPLAY);
        var metadata = connection.trackIncoming(EntityMetaDataPacket.class);

        // Index 23 is the text of a text display and the item of an item display: sent with the old
        // layout, the client drops the connection of every viewer
        assertThrows(IllegalStateException.class, () -> label.setText(Component.text("stale")));
        metadata.assertNoneMatch(packet -> packet.entityId() == entity.getEntityId());
    }

    @Test
    public void aSwitchInsideABatchDropsTheWritesOfTheOldMeta(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        connection.connect(instance, new Pos(0, 42, 0));
        var entity = new Entity(EntityType.TEXT_DISPLAY);
        entity.setInstance(instance, new Pos(2, 42, 0)).join();

        var metadata = connection.trackIncoming(EntityMetaDataPacket.class);
        var thrown = assertThrows(RuntimeException.class, () -> entity.editEntityMeta(TextDisplayMeta.class, meta -> {
            meta.setText(Component.text("before"));
            entity.switchEntityType(EntityType.ITEM_DISPLAY);
            meta.setText(Component.text("after"));
        }));

        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        // Neither the write batched before the switch nor the one after it goes out with the old layout
        metadata.assertNoneMatch(packet -> packet.entityId() == entity.getEntityId()
                && packet.entries().containsKey(23)
                && packet.entries().get(23).type() == Metadata.TYPE_CHAT);
    }
}
