package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitSpawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import io.github.bearisbug.synaxis.game.protocol.UnitState;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p>
 * <b>Who empties it, and when.</b> Only {@link #clear()}, and only from the start of a play
 * connection — the one place that is both on this thread and ordered behind every packet of the
 * connection before it (see {@code ADR-M2-012} and {@code SynaxisClientMod}). Nothing empties it on
 * disconnect, on a world switch or at the end of a match: a binding is a handful of {@code int}s
 * and an enum, so a table nobody emptied costs bytes rather than holding a level or an entity
 * alive, and
 * the server unbinds each unit for real by sending {@code S2C_UNIT_DESPAWN} when it leaves the
 * player's interest set — which leaving the world and ending the match both do. A binding that
 * outlives its entity anyway is not a hazard either: it has no position, so it cannot be picked
 * (see {@link UnitPicker}).
 * <p>
 * <b>What it does with a message that matches nothing.</b> Counts it and carries on. Both
 * directions of this table ride one ordered channel, so a snapshot line or a despawn can only name
 * a unit this client already bound; one that names no unit means something upstream is wrong, and
 * §8 forbids answering it with a protocol error. The middle ground is the two counters below plus
 * one WARN per connection per kind — enough for anyone to find out, bounded no matter what the
 * server sends.
 */
public final class UnitBindings {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

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

    private int unmatchedStateEntries;
    private int despawnsOfUnboundUnits;

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
     * <p>
     * A unit that is unbound again later is a plain {@link #apply(S2CUnitSpawn)} away from being
     * bound again: the order this table is written in never makes a later insert lose to an earlier
     * removal.
     *
     * @param despawn the message
     */
    public void apply(S2CUnitDespawn despawn) {
        for (final int unitId : despawn.unitIds()) {
            if (byUnitId.remove(unitId) != null) continue;
            if (despawnsOfUnboundUnits++ == 0) {
                LOGGER.warn("Despawn for a unit this client never bound: unit_id={}", unitId);
            }
        }
    }

    /**
     * Overwrites state and health from one snapshot.
     * <p>
     * A line naming a unit this client has not bound changes nothing and is counted (see
     * {@link #unmatchedStateEntries()}); §8 rules out treating it as a protocol error.
     *
     * @param snapshot the message
     */
    public void apply(S2CUnitState snapshot) {
        for (final S2CUnitState.Entry entry : snapshot.entries()) {
            final Binding bound = byUnitId.get(entry.unitId());
            if (bound == null) {
                if (unmatchedStateEntries++ == 0) {
                    LOGGER.warn("Snapshot line for a unit this client has not bound: unit_id={}, server_tick={}",
                            entry.unitId(), snapshot.serverTick());
                }
                continue;
            }
            byUnitId.put(entry.unitId(), new Binding(bound.unitId(), bound.entityId(),
                    bound.archetype(), bound.team(), entry.hp(), bound.maxHp(), bound.flags(),
                    entry.unitState()));
        }
    }

    /**
     * @return how many snapshot lines on this connection named a unit that was not bound
     */
    public int unmatchedStateEntries() {
        return unmatchedStateEntries;
    }

    /**
     * @return how many despawned {@code unit_id}s on this connection were not bound
     */
    public int despawnsOfUnboundUnits() {
        return despawnsOfUnboundUnits;
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
     * Forgets everything, counters included: both are per connection, like the bindings themselves
     * and like the one WARN each of them is allowed.
     */
    public void clear() {
        byUnitId.clear();
        unmatchedStateEntries = 0;
        despawnsOfUnboundUnits = 0;
    }
}
