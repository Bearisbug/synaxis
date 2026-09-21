package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitSpawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import io.github.bearisbug.synaxis.game.protocol.UnitState;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code unit_id ↔ entity_id} index {@code ADR-M2-012} leaves to the client, plus the little
 * the snapshot adds to it.
 * <p>
 * It holds no position. Positions arrive on the engine's own entity packets and are read back out
 * of the renderer at the moment of a click ({@link ClientWorldView#renderPosition(int)}), which is
 * why {@code API-M2-006} removed coordinates from the snapshot altogether: a second copy here
 * would be a second time base, and the box would drift away from the model whenever a unit moved.
 * <p>
 * Lives on the client thread that handles play packets and is touched from nowhere else — the map
 * is a plain {@link LinkedHashMap} and {@link #clear()} is as much a part of that as the puts are.
 */
public final class UnitBindings {

    /**
     * One unit as this client knows it.
     *
     * @param unitId   the match scoped id every message names the unit by
     * @param entityId the engine entity carrying its visuals and its position
     * @param archetype the archetype number, which indexes the picking box table
     * @param team     the team the unit belongs to, 0 for neutral
     * @param hp       current health, as of the last snapshot
     * @param maxHp    maximum health
     * @param flags    the {@code flags} u8 of {@code S2C_UNIT_SPAWN}, evaluated for this connection
     * @param state    what the unit is doing, as of the last snapshot
     */
    public record Binding(int unitId, int entityId, int archetype, int team,
                          int hp, int maxHp, int flags, UnitState state) {

        /**
         * Whether this player may select and order this unit.
         *
         * @return the {@code owner(unit, sender)} the server evaluated for this connection (§9)
         */
        public boolean commandable() {
            return (flags & S2CUnitSpawn.FLAG_COMMANDABLE) != 0;
        }
    }

    private final Map<Integer, Binding> byUnitId = new LinkedHashMap<>();

    /**
     * Binds a unit that entered this player's interest set, replacing whatever was bound before —
     * a unit that left and came back may be carried by a different entity.
     *
     * @param spawn the message
     */
    public void apply(S2CUnitSpawn spawn) {
        byUnitId.put(spawn.unitId(), new Binding(spawn.unitId(), spawn.entityId(), spawn.archetype(),
                spawn.team(), spawn.hp(), spawn.maxHp(), spawn.flags(), UnitState.IDLE));
    }

    /**
     * Forgets units that left the interest set or the match.
     *
     * @param despawn the message
     */
    public void apply(S2CUnitDespawn despawn) {
        for (final int unitId : despawn.unitIds()) byUnitId.remove(unitId);
    }

    /**
     * Overwrites state and health from one snapshot.
     * <p>
     * A line naming a unit this client has not bound is dropped rather than reported: §8 leaves the
     * arrival order of a binding and the engine's spawn packet undefined, so that is timing, not an
     * error.
     *
     * @param snapshot the message
     */
    public void apply(S2CUnitState snapshot) {
        for (final S2CUnitState.Entry entry : snapshot.entries()) {
            byUnitId.computeIfPresent(entry.unitId(), (_, bound) -> new Binding(bound.unitId(),
                    bound.entityId(), bound.archetype(), bound.team(), entry.hp(), bound.maxHp(),
                    bound.flags(), entry.unitState()));
        }
    }

    /**
     * @param unitId the unit to look up
     * @return what is bound for it, or {@code null} if nothing is
     */
    public Binding get(int unitId) {
        return byUnitId.get(unitId);
    }

    /**
     * @return every bound unit, in the order they were first bound
     */
    public Collection<Binding> all() {
        return byUnitId.values();
    }

    /**
     * Forgets everything, which is what leaving a world means.
     */
    public void clear() {
        byUnitId.clear();
    }
}
