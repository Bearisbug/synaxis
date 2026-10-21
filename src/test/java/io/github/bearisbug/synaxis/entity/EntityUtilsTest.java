package io.github.bearisbug.synaxis.entity;

import io.github.bearisbug.synaxis.coordinate.Point;
import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class EntityUtilsTest {

    /**
     * A vehicle of {@code vehicle} facing {@code yaw}, carrying {@code passengers} riders, and the offset of the rider
     * of type {@code passenger} at {@code index}.
     */
    private record Case(EntityType vehicle, boolean sitting, EntityType passenger, int passengers, int index,
                        float yaw, Vec expected) {
    }

    private static final List<Case> CASES = List.of(
            new Case(EntityType.CAMEL, false, EntityType.ZOMBIE, 1, 0, 30f, new Vec(-0.24999999999999997, 1.300000011920929, 0.43301270189221935)),
            new Case(EntityType.CAMEL, false, EntityType.PIG, 2, 1, 30f, new Vec(0.24999999999999994, 2.0, -0.4330127018922193)),
            new Case(EntityType.CAMEL, true, EntityType.ZOMBIE, 1, 0, -45f, new Vec(0.35355339059327373, 0.06999999284744263, 0.3535533905932738)),
            new Case(EntityType.CAMEL_HUSK, false, EntityType.ZOMBIE, 2, 1, 90f, new Vec(0.7, 1.300000011920929, -4.286263797015736e-17)),
            new Case(EntityType.HAPPY_GHAST, false, EntityType.ZOMBIE, 1, 0, 30f, new Vec(-0.8499999999999999, 3.300000011920929, 1.4722431864335457)),
            new Case(EntityType.HAPPY_GHAST, false, EntityType.PIG, 1, 5, 30f, new Vec(1.4722431864335457, 4.0, 0.8499999999999999)),
            new Case(EntityType.OAK_BOAT, false, EntityType.ZOMBIE, 1, 0, 30f, new Vec(0.0, 0.1875, 0.0)),
            new Case(EntityType.OAK_BOAT, false, EntityType.PIG, 2, 1, 30f, new Vec(0.19999999999999996, 0.1875, -0.34641016151377546)),
            new Case(EntityType.OAK_CHEST_BOAT, false, EntityType.ZOMBIE, 1, 0, 120f, new Vec(-0.1299038105676658, 0.1875, -0.07499999999999997)),
            new Case(EntityType.OAK_CHEST_BOAT, false, EntityType.ZOMBIE, 2, 0, 120f, new Vec(-0.17320508075688776, 0.1875, -0.09999999999999998)),
            new Case(EntityType.BAMBOO_RAFT, false, EntityType.ZOMBIE, 2, 0, 30f, new Vec(-0.09999999999999999, 0.6328757697871253, 0.17320508075688776)),
            new Case(EntityType.BAMBOO_RAFT, false, EntityType.ZOMBIE, 2, 1, 30f, new Vec(0.29999999999999993, 0.6328757697871253, -0.5196152422706632)),
            new Case(EntityType.HORSE, false, EntityType.ZOMBIE, 1, 0, 30f, new Vec(0.0, 0.7437500357627869, 0.0)),
            new Case(EntityType.ITEM, false, EntityType.PIG, 1, 0, 30f, new Vec(0.0, 0.25, 0.0))
    );

    @Test
    public void passengerOffsetOfEachKindOfVehicle() {
        assertAll(CASES.stream().map(c -> (Executable) () -> {
            final Point offset = EntityUtils.getPassengerPositionOffset(vehicle(c), new Entity(c.passenger()), c.index());
            assertEquals(c.expected().x(), offset.x(), 1e-9, c + " x");
            assertEquals(c.expected().y(), offset.y(), 1e-9, c + " y");
            assertEquals(c.expected().z(), offset.z(), 1e-9, c + " z");
        }));
    }

    private static Entity vehicle(Case c) {
        final List<Entity> riders = new ArrayList<>();
        for (int i = 0; i < c.passengers(); i++) riders.add(new Entity(c.passenger()));
        final Pos facing = new Pos(0, 40, 0, c.yaw(), 0);
        final EntityPose vehiclePose = c.sitting() ? EntityPose.SITTING : EntityPose.STANDING;
        return new Entity(c.vehicle()) {
            @Override
            public Pos getPosition() {
                return facing;
            }

            @Override
            public EntityPose getPose() {
                return vehiclePose;
            }

            @Override
            public List<Entity> getPassengers() {
                return riders;
            }
        };
    }
}
