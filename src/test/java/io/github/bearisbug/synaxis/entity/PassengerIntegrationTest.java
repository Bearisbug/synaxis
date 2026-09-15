package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.network.packet.server.play.SetPassengersPacket;
import io.github.bearisbug.synaxis.network.packet.server.play.SpawnEntityPacket;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnvTest
public class PassengerIntegrationTest {

    @Test
    public void passenger(Env env) {
        var instance = env.createFlatInstance();
        var vehicle = new Entity(EntityType.ZOMBIE);
        var passenger = new Entity(EntityType.ZOMBIE);

        vehicle.setInstance(instance, new Pos(0, 40, 0)).join();
        passenger.setInstance(instance, new Pos(0, 40, 0)).join();

        assertEquals(0, vehicle.getPassengers().size());
        assertNull(passenger.getVehicle());

        vehicle.addPassenger(passenger);
        assertEquals(1, vehicle.getPassengers().size());
        assertEquals(vehicle, passenger.getVehicle());
    }

    @Test
    public void passengerTeleport(Env env) {
        var instance = env.createFlatInstance();
        var vehicle = new Entity(EntityType.ZOMBIE);
        var passenger = new Entity(EntityType.ZOMBIE);

        vehicle.setInstance(instance, new Pos(0, 40, 0)).join();
        passenger.setInstance(instance, new Pos(0, 40, 5000)).join();

        assertEquals(0, vehicle.getPassengers().size());
        assertNull(passenger.getVehicle());

        vehicle.addPassenger(passenger);
        assertEquals(1, vehicle.getPassengers().size());
        assertEquals(vehicle, passenger.getVehicle());

        assertTrue(passenger.getDistance(vehicle) < 3);
    }

    @Test
    public void passengerPacketOrder(Env env) {
        var instance = env.createFlatInstance();
        var vehicle = new Entity(EntityType.ZOMBIE);
        vehicle.setInstance(instance, new Pos(0, 40, 0)).join();
        // Add 3 passengers to vehicle to test Entity#updateNewViewer recursion
        var passenger1 = new Entity(EntityType.ZOMBIE);
        var passenger2 = new Entity(EntityType.ZOMBIE);
        var passenger3 = new Entity(EntityType.ZOMBIE);
        vehicle.addPassenger(passenger1);
        passenger1.addPassenger(passenger2);
        passenger2.addPassenger(passenger3);

        var connection = env.createConnection();
        var spawnTracker = connection.trackIncoming(SpawnEntityPacket.class);
        var passengerTracker = connection.trackIncoming(SetPassengersPacket.class);

        connection.connect(instance, new Pos(0, 40, 0));

        // Passenger packet order will be sent backwards down the chain of passenger vehicles
        passengerTracker.assertCount(3);
        var passengerPackets = passengerTracker.collect();
        assertEquals(List.of(passenger3.getEntityId(), passenger2.getEntityId(), passenger1.getEntityId()),
                passengerPackets.stream().map(packet -> packet.passengersId().getFirst()).toList());

        // Ensure spawn packets are never sent more than once per entity, and that
        // Entity#updateNewViewer walked the chain in order
        spawnTracker.assertCount(4);
        var spawnPackets = spawnTracker.collect();
        assertEquals(List.of(vehicle.getEntityId(), passenger1.getEntityId(),
                        passenger2.getEntityId(), passenger3.getEntityId()),
                spawnPackets.stream().map(SpawnEntityPacket::entityId).toList());
    }

    @Test
    public void passengersOnCamelsAtDifferentHeights(Env env) {
        var instance = env.createFlatInstance();
        var camel1 = new Entity(EntityType.CAMEL);
        var camel2 = new Entity(EntityType.CAMEL);
        var rider1 = new Entity(EntityType.ZOMBIE);
        var rider2 = new Entity(EntityType.ZOMBIE);

        camel1.setInstance(instance, new Pos(0, 42, 0)).join();
        camel2.setInstance(instance, new Pos(0, 42, 0)).join();

        camel2.setPose(EntityPose.SITTING);
        camel1.addPassenger(rider1);
        camel2.addPassenger(rider2);

        assertNotEquals(rider2.getPosition().y(), rider1.getPosition().y());
    }

    @Test
    public void passengerOffsetProcessedCorrectly(Env env) {
        var instance = env.createFlatInstance();
        var vehicle = new Entity(EntityType.ZOMBIE);
        var passenger = new Entity(EntityType.SKELETON);

        vehicle.setInstance(instance, new Pos(0, 42, 0, 0, 0)).join();
        vehicle.addPassenger(passenger);

        // Default case

        List<Vec> passengerAttachments = vehicle.getEntityType().entityAttachments("PASSENGER");
        List<Vec> vehicleAttachments = passenger.getEntityType().entityAttachments("VEHICLE");
        assertNotNull(passengerAttachments);
        assertNotNull(vehicleAttachments);

        Vec passengerAttachment = passengerAttachments.getFirst();
        Vec vehicleAttachment = vehicleAttachments.getFirst();
        Pos assumedPosition = vehicle.getPosition().add(passengerAttachment).sub(vehicleAttachment);
        assertEquals(passenger.getPosition(), assumedPosition);
    }
}
