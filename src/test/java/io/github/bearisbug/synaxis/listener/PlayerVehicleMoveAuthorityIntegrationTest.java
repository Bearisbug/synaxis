package io.github.bearisbug.synaxis.listener;

import io.github.bearisbug.synaxis.coordinate.ChunkRange;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.EntityType;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.instance.WorldBorder;
import io.github.bearisbug.synaxis.network.packet.client.play.ClientVehicleMovePacket;
import io.github.bearisbug.synaxis.network.packet.server.play.VehicleMovePacket;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the server takes from a client's vehicle moves ({@code docs/DESIGN-PROD.md} {@code API-PROD-030}):
 * the two bounds of a player's own position packets, a move out across the world border, or farther
 * out for a vehicle the server put outside it, and a move of more than 64 blocks in one packet. Such a
 * move is turned back where the vehicle is, the vehicle and its rider staying put and the rider's
 * client sent a {@link VehicleMovePacket} with the vehicle's position, as the vanilla server corrects a
 * vehicle that moved wrongly.
 * <p>
 * Nothing in the engine lets a client mount, so the ride is made with {@link Entity#addPassenger}, as
 * a plugin would. The test thread hands the player its packets, and the chunks a move needs are
 * loaded beforehand.
 */
@EnvTest
public class PlayerVehicleMoveAuthorityIntegrationTest {

    private static final Pos SPAWN = new Pos(0.5, 40, 0.5);
    /** The view distance of the instances here: a chunk ring of radius 3 around the player's chunk. */
    private static final int VIEW = 2;

    /** A flat instance with the chunk ring of the spawn loaded. */
    private static Instance instance(Env env) {
        final Instance instance = env.createFlatInstance();
        instance.viewDistance(VIEW);
        ChunkRange.chunksInRange(0, 0, VIEW + 1, (x, z) -> instance.loadChunk(x, z).join());
        return instance;
    }

    /** A player riding a horse at {@code at}, and the vehicle moves its client is sent from then on. */
    private record Ride(Player player, Entity vehicle, Collector<VehicleMovePacket> turnedBack) {

        static Ride at(Env env, Instance instance, Pos at) {
            final TestConnection connection = env.createConnection();
            final Player player = connection.connect(instance, at);
            final Entity vehicle = new Entity(EntityType.HORSE);
            vehicle.setInstance(instance, at).join();
            vehicle.addPassenger(player);
            return new Ride(player, vehicle, connection.trackIncoming(VehicleMovePacket.class));
        }

        void drive(double x, double y, double z) {
            player.addPacketToQueue(new ClientVehicleMovePacket(new Pos(x, y, z), true));
            player.interpretPacketQueue();
        }
    }

    /** A move out across the world border is turned back where the vehicle is; one within it is taken. */
    @Test
    public void aVehicleMoveOutAcrossTheWorldBorderIsTurnedBack(Env env) {
        final Instance instance = instance(env);
        // [-32, 32) on both axes
        instance.setWorldBorder(new WorldBorder(64, 0, 0, 0, 0));
        final Ride ride = Ride.at(env, instance, SPAWN);
        final Pos riding = ride.player().getPosition();

        ride.drive(40.5, 40, 0.5);

        assertEquals(SPAWN, ride.vehicle().getPosition(), "a vehicle move to x 40.5 across the border at x 32");
        assertEquals(riding, ride.player().getPosition(), "the rider of a vehicle turned back");
        ride.turnedBack().assertSingle(packet -> assertEquals(SPAWN, packet.position()));
        ride.drive(31.5, 40, 0.5);
        assertEquals(new Pos(31.5, 40, 0.5), ride.vehicle().getPosition(), "a vehicle move to x 31.5, inside the border");
    }

    /**
     * One packet carries the vehicle 64 blocks at most, measured in a straight line: 37 blocks on each
     * of the three axes is 64.09 blocks.
     */
    @Test
    public void aVehicleMoveOfMoreThanSixtyFourBlocksIsTurnedBack(Env env) {
        final Instance instance = instance(env);
        // Past the ring of the spawn: a move 65 blocks east is turned back for its length, not for its chunk
        instance.loadChunk(4, 0).join();
        final Ride ride = Ride.at(env, instance, SPAWN);

        ride.drive(65.5, 40, 0.5);
        assertEquals(SPAWN, ride.vehicle().getPosition(), "65 blocks east in one packet");
        ride.drive(0.5, 105, 0.5);
        assertEquals(SPAWN, ride.vehicle().getPosition(), "65 blocks up in one packet");
        ride.drive(37.5, 77, 37.5);
        assertEquals(SPAWN, ride.vehicle().getPosition(), "37 blocks along each axis in one packet");
        ride.turnedBack().assertCount(3);
        ride.drive(64.5, 40, 0.5);
        assertEquals(new Pos(64.5, 40, 0.5), ride.vehicle().getPosition(), "64 blocks east in one packet");
    }

    /**
     * A vehicle the server put outside the border gets no farther out on the x or the z axis; it is
     * driven along the border and back in. The border ends at the wall the vanilla client collides with:
     * at x 32, the border of 63 blocks across rounded outwards.
     */
    @Test
    public void aVehicleOutsideTheBorderGetsNoFartherOut(Env env) {
        final Instance instance = instance(env);
        // [-32, 32) on both axes
        instance.setWorldBorder(new WorldBorder(64, 0, 0, 0, 0));
        final Ride ride = Ride.at(env, instance, SPAWN);
        ride.vehicle().teleport(new Pos(42, 40, 0.5)).join();

        ride.drive(47, 40, 0.5);
        assertEquals(new Pos(42, 40, 0.5), ride.vehicle().getPosition(), "5 blocks farther out, from 10 blocks outside the border");
        ride.turnedBack().assertSingle(packet -> assertEquals(new Pos(42, 40, 0.5), packet.position()));
        ride.drive(42, 40, 5.5);
        assertEquals(new Pos(42, 40, 5.5), ride.vehicle().getPosition(), "5 blocks along the border");
        ride.drive(31.5, 40, 5.5);
        assertEquals(new Pos(31.5, 40, 5.5), ride.vehicle().getPosition(), "back inside");

        instance.setWorldBorder(new WorldBorder(63, 0, 0, 0, 0));
        ride.drive(31.6, 40, 5.5);
        assertEquals(new Pos(31.6, 40, 5.5), ride.vehicle().getPosition(), "x 31.6, past the border at 31.5, short of the wall at 32");
        ride.drive(32.1, 40, 5.5);
        assertEquals(new Pos(31.6, 40, 5.5), ride.vehicle().getPosition(), "x 32.1, past the wall at 32");
    }
}
