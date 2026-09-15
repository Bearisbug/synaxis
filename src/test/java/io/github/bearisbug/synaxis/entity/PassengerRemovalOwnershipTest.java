package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.ServerConfig;
import io.github.bearisbug.synaxis.ServerProcess;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.instance.block.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A vehicle and its passenger can be ticked by different threads, so removing the passenger has to
 * detach it from a vehicle the caller does not own. Asserting the vehicle's ownership there breaks
 * {@link Entity#remove()} in the middle: the despawn event is already out but the entity is never
 * marked removed, so it keeps ticking and can never be removed again.
 */
public class PassengerRemovalOwnershipTest {

    private static final int CHUNKS = 8;

    @Test
    @Timeout(60)
    public void passengerRemovesItselfFromAForeignVehicle() {
        final ServerProcess process = ServerProcess.of(ServerConfig.builder()
                .insideTest(true)
                .dispatcherThreads(2)
                .build());
        try {
            process.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            process.dispatcher().start();
            final InstanceContainer instance = process.instance().createInstanceContainer();
            instance.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.STONE));

            final List<Entity> entities = new ArrayList<>(CHUNKS);
            for (int chunkX = 0; chunkX < CHUNKS; chunkX++) {
                var entity = new Entity(EntityType.ZOMBIE);
                entity.setInstance(instance, new Pos(chunkX * 16, 42, 0)).join();
                entities.add(entity);
            }
            process.ticker().tick(System.nanoTime()); // Assigns every entity to its partition thread

            final Entity vehicle = entities.getFirst();
            final Entity passenger = entities.stream()
                    .filter(e -> e.acquirable().assignedThread() != vehicle.acquirable().assignedThread())
                    .findFirst()
                    .orElseGet(() -> fail("the two dispatcher threads did not split the spawn chunks"));

            vehicle.acquirable().sync(v -> v.addPassenger(passenger));
            assertNotSame(vehicle.acquirable().assignedThread(), passenger.acquirable().assignedThread(),
                    "the passenger must still belong to the other tick thread");

            assertDoesNotThrow(() -> passenger.acquirable().sync(Entity::remove));
            assertTrue(passenger.isRemoved());
            assertNull(passenger.getVehicle());
            assertTrue(vehicle.getPassengers().isEmpty(), "the vehicle still carries the removed passenger");
        } finally {
            process.stop();
        }
    }
}
