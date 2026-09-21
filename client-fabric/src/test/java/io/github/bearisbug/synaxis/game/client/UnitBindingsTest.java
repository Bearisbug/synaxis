package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitSpawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import io.github.bearisbug.synaxis.game.protocol.UnitState;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lifetime of the {@code unit_id ↔ entity_id} table of {@code ADR-M2-012}.
 */
class UnitBindingsTest {

    private final UnitBindings bindings = new UnitBindings();

    private static S2CUnitSpawn spawn(int unitId, int entityId, int flags) {
        return new S2CUnitSpawn(unitId, entityId, 0, 1, 100, 100, flags);
    }

    @Test
    void aSpawnBindsTheUnitToTheEntityThatCarriesItsVisuals() {
        bindings.apply(spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE));

        final UnitBindings.Binding binding = bindings.get(7);
        assertEquals(4711, binding.entityId());
        assertEquals(0, binding.archetype());
        assertTrue(binding.commandable());
        assertEquals(UnitState.IDLE, binding.state());
    }

    @Test
    void aUnitThisPlayerCannotCommandIsStillBound() {
        bindings.apply(spawn(65, 4712, 0));

        assertFalse(bindings.get(65).commandable());
    }

    @Test
    void aDespawnForgetsTheUnit() {
        bindings.apply(spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE));
        bindings.apply(new S2CUnitDespawn(List.of(7)));

        assertNull(bindings.get(7));
        assertEquals(0, bindings.all().size());
    }

    @Test
    void aSnapshotOverwritesStateAndHealth() {
        bindings.apply(spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE));
        bindings.apply(new S2CUnitState(41, List.of(new S2CUnitState.Entry(7, UnitState.MOVING, 80))));

        assertEquals(UnitState.MOVING, bindings.get(7).state());
        assertEquals(80, bindings.get(7).hp());
    }

    /**
     * §8: a snapshot line naming a unit this client has not bound is not an error and never becomes
     * one — no protocol error, no disconnect, nothing invented for it. It is still counted, because
     * on one ordered channel the binding always precedes the snapshot that names it, so a line that
     * matches nothing means something upstream is wrong and a drop nobody can see would hide it.
     */
    @Test
    void aSnapshotLineForAUnitThatIsNotBoundIsDroppedAndCounted() {
        bindings.apply(new S2CUnitState(41, List.of(new S2CUnitState.Entry(9, UnitState.MOVING, 80))));

        assertNull(bindings.get(9));
        assertEquals(1, bindings.unmatchedStateEntries());
    }

    /** The same line after the unit was unbound: a despawn is the end of the unit, not a hole. */
    @Test
    void aSnapshotLineForAUnitThatWasJustDespawnedIsDroppedAndCounted() {
        bindings.apply(spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE));
        bindings.apply(new S2CUnitDespawn(List.of(7)));

        bindings.apply(new S2CUnitState(41, List.of(new S2CUnitState.Entry(7, UnitState.MOVING, 80))));

        assertNull(bindings.get(7));
        assertEquals(1, bindings.unmatchedStateEntries());
    }

    @Test
    void aSnapshotThatNamesOnlyBoundUnitsCountsNothing() {
        bindings.apply(spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE));

        bindings.apply(new S2CUnitState(41, List.of(new S2CUnitState.Entry(7, UnitState.MOVING, 80))));

        assertEquals(0, bindings.unmatchedStateEntries());
    }

    /**
     * A despawn naming nothing is the recycling side of the same question, and it is counted for
     * the same reason: both messages ride one ordered channel, so a despawn can only name a unit
     * this client bound. One that names no unit is either a second despawn or a despawn that
     * overtook its spawn, and both are worth knowing about.
     */
    @Test
    void aDespawnOfAUnitThatIsNotBoundIsCounted() {
        bindings.apply(new S2CUnitDespawn(List.of(9)));

        assertEquals(1, bindings.despawnsOfUnboundUnits());
    }

    @Test
    void aSecondDespawnOfTheSameUnitIsCounted() {
        bindings.apply(spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE));
        bindings.apply(new S2CUnitDespawn(List.of(7)));
        bindings.apply(new S2CUnitDespawn(List.of(7)));

        assertEquals(1, bindings.despawnsOfUnboundUnits());
    }

    /**
     * The recycle never outlives the insert that follows it: a unit that left the interest set and
     * came back is a plain spawn, whatever the despawn before it did to the table.
     */
    @Test
    void aDespawnDoesNotStopTheSameUnitFromBindingAgainLater() {
        bindings.apply(new S2CUnitDespawn(List.of(7)));
        bindings.apply(spawn(7, 5000, S2CUnitSpawn.FLAG_COMMANDABLE));

        assertEquals(5000, bindings.get(7).entityId());
        assertEquals(1, bindings.all().size());
    }

    @Test
    void aSecondSpawnOfTheSameUnitRebindsItToTheNewEntity() {
        bindings.apply(spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE));
        bindings.apply(spawn(7, 5000, 0));

        assertEquals(5000, bindings.get(7).entityId());
        assertFalse(bindings.get(7).commandable());
        assertEquals(1, bindings.all().size());
    }

    @Test
    void leavingTheWorldLeavesNothingBound() {
        bindings.apply(spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE));
        bindings.clear();

        assertEquals(0, bindings.all().size());
    }

    /** The counters belong to a connection as much as the bindings do. */
    @Test
    void aNewConnectionStartsWithBothCountersAtZero() {
        bindings.apply(new S2CUnitState(41, List.of(new S2CUnitState.Entry(9, UnitState.MOVING, 80))));
        bindings.apply(new S2CUnitDespawn(List.of(9)));

        bindings.clear();

        assertEquals(0, bindings.unmatchedStateEntries());
        assertEquals(0, bindings.despawnsOfUnboundUnits());
    }
}
