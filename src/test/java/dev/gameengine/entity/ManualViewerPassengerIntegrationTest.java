package dev.gameengine.entity;

import dev.gameengine.coordinate.Pos;
import dev.gameengine.network.packet.server.play.SetPassengersPacket;
import dev.gameengine.testing.Env;
import dev.gameengine.testing.EnvTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class ManualViewerPassengerIntegrationTest {

    @Test
    public void manualAddViewerSendsThePassengers(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        // Far enough away that the entities are not automatically viewable
        var player = connection.connect(instance, new Pos(0, 42, 5000));

        var vehicle = new Entity(EntityType.OAK_BOAT);
        vehicle.setInstance(instance, new Pos(0, 42, 0)).join();
        var passenger = new Entity(EntityType.ZOMBIE);
        passenger.setInstance(instance, new Pos(0, 42, 0)).join();
        vehicle.addPassenger(passenger);

        var tracker = connection.trackIncoming(SetPassengersPacket.class);
        assertTrue(vehicle.addViewer(player));
        tracker.assertAnyMatch(packet -> packet.vehicleEntityId() == vehicle.getEntityId()
                && packet.passengersId().contains(passenger.getEntityId()));
    }

    @Test
    public void respawnResendsThePassengers(Env env) {
        var instance = env.createFlatInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, new Pos(0, 42, 0));

        var vehicle = new Entity(EntityType.OAK_BOAT);
        vehicle.setInstance(instance, new Pos(0, 42, 1)).join();
        var passenger = new Entity(EntityType.ZOMBIE);
        passenger.setInstance(instance, new Pos(0, 42, 1)).join();
        vehicle.addPassenger(passenger);
        env.tick();

        player.acquirable().sync(_ -> player.kill());
        var tracker = connection.trackIncoming(SetPassengersPacket.class);
        player.acquirable().sync(_ -> player.respawn());
        tracker.assertAnyMatch(packet -> packet.vehicleEntityId() == vehicle.getEntityId()
                && packet.passengersId().contains(passenger.getEntityId()));
    }
}
