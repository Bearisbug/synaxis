package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.S2CUnitSpawn;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The picking maths of {@code ADR-M2-012}: a ray against boxes the mod builds itself, out of the
 * rendered position of an entity and the archetype's size. No {@code interaction} entity and no
 * snapshot coordinate takes part, which is exactly what these tests pin down.
 */
class UnitPickerTest {

    /** The archetype every M2 unit carries; its box is 0.9 by 0.9 blocks. */
    private static final int ARCHETYPE = 0;

    private final UnitBindings bindings = new UnitBindings();
    private final FakeWorldView world = new FakeWorldView();

    private void bind(int unitId, int entityId, int archetype) {
        bindings.apply(new S2CUnitSpawn(unitId, entityId, archetype, 1, 100, 100,
                S2CUnitSpawn.FLAG_COMMANDABLE));
    }

    private UnitBindings.Binding pick(Vec3 origin, Vec3 direction, double range) {
        return UnitPicker.pick(origin, direction, range, bindings, world);
    }

    @Test
    void aRayThroughAUnitPicksIt() {
        bind(7, 4711, ARCHETYPE);
        world.at(4711, 10.0, 64.0, 0.0);

        assertEquals(7, pick(new Vec3(0.0, 64.5, 0.0), new Vec3(1.0, 0.0, 0.0), 64.0).unitId());
    }

    @Test
    void aRayThatPassesBesideTheBoxPicksNothing() {
        bind(7, 4711, ARCHETYPE);
        world.at(4711, 10.0, 64.0, 0.0);

        assertNull(pick(new Vec3(0.0, 70.0, 0.0), new Vec3(1.0, 0.0, 0.0), 64.0));
    }

    @Test
    void theNearestUnitOnTheRayWins() {
        bind(7, 4711, ARCHETYPE);
        bind(9, 4712, ARCHETYPE);
        world.at(4711, 20.0, 64.0, 0.0).at(4712, 10.0, 64.0, 0.0);

        assertEquals(9, pick(new Vec3(0.0, 64.5, 0.0), new Vec3(1.0, 0.0, 0.0), 64.0).unitId());
    }

    @Test
    void aUnitFartherAwayThanTheRangePicksNothing() {
        bind(7, 4711, ARCHETYPE);
        world.at(4711, 10.0, 64.0, 0.0);

        assertNull(pick(new Vec3(0.0, 64.5, 0.0), new Vec3(1.0, 0.0, 0.0), 5.0));
    }

    /**
     * §8: the binding and the engine's own spawn packet arrive in either order, and until both are
     * in the unit has no box — the client cannot invent where it is.
     */
    @Test
    void aBoundUnitWhoseEntityHasNotArrivedIsNotPickable() {
        bind(7, 4711, ARCHETYPE);

        assertNull(pick(new Vec3(0.0, 64.5, 0.0), new Vec3(1.0, 0.0, 0.0), 64.0));
    }

    @Test
    void aUnitOfAnArchetypeThisBuildDoesNotKnowIsNotPickable() {
        bind(7, 4711, 5);
        world.at(4711, 10.0, 64.0, 0.0);

        assertNull(pick(new Vec3(0.0, 64.5, 0.0), new Vec3(1.0, 0.0, 0.0), 64.0));
    }

    /**
     * The whole point of {@code ADR-M2-012}: the box follows the position the renderer is drawing
     * at, so a unit that has moved is picked where it is drawn and nowhere else.
     */
    @Test
    void theBoxFollowsTheRenderedPositionRatherThanAnythingTheSnapshotSaid() {
        bind(7, 4711, ARCHETYPE);
        world.at(4711, 10.0, 64.0, 0.0);
        final Vec3 eye = new Vec3(0.0, 64.5, 0.0);

        assertEquals(7, pick(eye, new Vec3(1.0, 0.0, 0.0), 64.0).unitId());

        world.at(4711, 10.0, 64.0, 30.0);
        assertNull(pick(eye, new Vec3(1.0, 0.0, 0.0), 64.0));
        assertEquals(7, pick(eye, new Vec3(10.0, 0.0, 30.0), 64.0).unitId());
    }

    @Test
    void anUnnormalisedDirectionMeasuresTheSameRange() {
        bind(7, 4711, ARCHETYPE);
        world.at(4711, 10.0, 64.0, 0.0);

        assertNull(pick(new Vec3(0.0, 64.5, 0.0), new Vec3(100.0, 0.0, 0.0), 5.0));
    }
}
