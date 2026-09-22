package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.coordinate.Vec;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.protocol.UnitState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One unit of the match: our own model, plus the display entity that makes it visible
 * ({@code docs/DESIGN-M2.md} §7, state machine in §11.4).
 * <p>
 * {@code unit_id} and {@code entity_id} are two different numbers and are never mixed: the first is
 * ours and unique inside the match, the second is the engine's. The client binds them when
 * {@code S2C_UNIT_SPAWN} arrives.
 * <p>
 * Everything here is touched from the tick loop only: the order judgement reads the team on the
 * tick thread that interprets the payload, and the advance runs from the scheduler, which the same
 * loop drives before it ticks any chunk.
 */
final class Unit {

    /** M2 shows health and never spends it (§9), so every unit has the same full bar. */
    static final int MAX_HP = 100;

    /** §11.4: the unit has arrived once it is closer than this to its last waypoint. */
    private static final double ARRIVAL_DISTANCE = 0.1;

    /**
     * The order a moving unit is carrying out, kept so that the ledger can say which one completed
     * and which one was superseded before it could (§20).
     *
     * @param connectionId who gave it
     * @param orderSeq     its sequence number on that connection
     * @param issuedNanos  when it was accepted, for {@code elapsed_ms}
     */
    record Order(UUID connectionId, int orderSeq, long issuedNanos) {
    }

    private final int unitId;
    private final Archetype archetype;
    private final int team;
    private final UnitEntity entity;

    /**
     * The players that have been sent this unit's {@code S2C_UNIT_SPAWN} and not yet its
     * {@code S2C_UNIT_DESPAWN}: the interest set as last announced, which the snapshot diffs
     * against the display entity's viewer set ({@code API-M2-006}).
     */
    private final Set<Player> announcedTo = new HashSet<>();

    private UnitState state = UnitState.IDLE;
    private List<Pos> waypoints = List.of();
    private int nextWaypoint;
    private @Nullable Order order;

    Unit(int unitId, Archetype archetype, int team, UnitEntity entity) {
        this.unitId = unitId;
        this.archetype = archetype;
        this.team = team;
        this.entity = entity;
    }

    int unitId() {
        return unitId;
    }

    Archetype archetype() {
        return archetype;
    }

    int team() {
        return team;
    }

    UnitEntity entity() {
        return entity;
    }

    Set<Player> announcedTo() {
        return announcedTo;
    }

    UnitState state() {
        return state;
    }

    /**
     * The order this unit is carrying out.
     *
     * @return it, or null when the unit is idle
     */
    @Nullable Order order() {
        return order;
    }

    /**
     * Puts the unit on a path, replacing whatever it was doing (§11.4, both the {@code idle →
     * moving} edge and the accepting branch of the {@code moving} self transition).
     *
     * @param path  the waypoints to walk, the last one being the destination
     * @param given the order that asked for it
     * @return the order that was replaced, null when the unit was idle
     */
    @Nullable Order follow(List<Pos> path, Order given) {
        final Order replaced = this.order;
        this.waypoints = List.copyOf(path);
        this.nextWaypoint = 0;
        this.order = given;
        this.state = UnitState.MOVING;
        return replaced;
    }

    /**
     * Stops the unit where it stands without moving it.
     *
     * @return the order that will never complete now, null when the unit was idle
     */
    @Nullable Order stop() {
        final Order abandoned = this.order;
        this.waypoints = List.of();
        this.nextWaypoint = 0;
        this.order = null;
        this.state = UnitState.IDLE;
        return abandoned;
    }

    /**
     * Walks the unit along its path for one advance and writes the result straight into the display
     * entity, through the public {@code Entity#moveTo(Pos)} of REQ-M2-025 — the primitive that
     * moves an entity without dispatching an event and without loading a chunk behind the scenes.
     *
     * @param ticks how many ticks this advance stands for
     * @return the order that completed with it, null when the unit is still on its way or was not
     *         moving at all
     */
    @Nullable Order advance(int ticks) {
        if (state != UnitState.MOVING || waypoints.isEmpty()) return stop();
        double budget = (double) archetype.speed() * ticks;
        Pos position = entity.getPosition();
        while (nextWaypoint < waypoints.size() && budget > 0) {
            final Pos target = waypoints.get(nextWaypoint);
            final Vec direction = target.sub(position).asVec();
            final double distance = direction.length();
            if (distance <= budget) {
                position = target.withDirection(direction);
                budget -= distance;
                nextWaypoint++;
            } else {
                position = position.add(direction.normalize().mul(budget)).withDirection(direction);
                budget = 0;
            }
        }
        entity.moveTo(position);
        if (position.distance(waypoints.getLast()) >= ARRIVAL_DISTANCE) return null;
        return stop();
    }
}
