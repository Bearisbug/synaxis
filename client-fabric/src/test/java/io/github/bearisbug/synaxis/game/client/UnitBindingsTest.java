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
     * §8: the two spawn paths — ours and the engine's entity packet — arrive in either order, so a
     * snapshot line for a unit that is not bound yet is a fact about timing, not an error.
     */
    @Test
    void aSnapshotLineForAUnitThatIsNotBoundYetIsIgnored() {
        bindings.apply(new S2CUnitState(41, List.of(new S2CUnitState.Entry(9, UnitState.MOVING, 80))));

        assertNull(bindings.get(9));
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
}
