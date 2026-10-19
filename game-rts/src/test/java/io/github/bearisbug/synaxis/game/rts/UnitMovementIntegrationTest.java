package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.event.EventFilter;
import io.github.bearisbug.synaxis.event.entity.EntityTeleportEvent;
import io.github.bearisbug.synaxis.game.protocol.C2SMoveOrder;
import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import io.github.bearisbug.synaxis.game.protocol.UnitState;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static io.github.bearisbug.synaxis.game.rts.SliceFixture.MINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An order that got past the judgement of {@link GameChannel}: the search that answers it, the walk
 * it produces and the three budgets that bound both (REQ-M2-017), plus the entry point the walk
 * goes through (REQ-M2-025).
 */
@EnvTest
class UnitMovementIntegrationTest {

    /** How many ticks a twenty block walk is given before the case calls it stuck. */
    private static final int WALK_TIMEOUT_TICKS = 2_000;

    @Test
    void aUnitWalksToWhereItWasOrderedAndGoesIdleThere(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Unit unit = Objects.requireNonNull(fixture.units.byId(MINE));
        final Pos target = fixture.standNear(20, 0);

        fixture.receive(order(1, MINE, target));
        fixture.tick(1);

        final S2COrderResult result = fixture.channels.lastResult();
        assertEquals(OrderResultCode.OK, result.code());
        assertEquals(target.x(), result.snappedX(), "the answer carries the destination it snapped to");
        assertEquals(target.z(), result.snappedZ());
        assertEquals(UnitState.MOVING, unit.state());

        walkUntilIdle(fixture, unit);

        assertEquals(UnitState.IDLE, unit.state(), "the unit never arrived");
        assertTrue(unit.entity().getPosition().distance(target) < 0.1,
                "it stopped " + unit.entity().getPosition().distance(target) + " blocks short of its destination");
        final Map<String, ?> completed = fixture.events.last("unit_order_completed");
        assertNotNull(completed, "an order that finished left no trace in the ledger");
        assertEquals(1, completed.get("order_seq"));
    }

    @Test
    void theWalkIsMadeOfAdvancesRatherThanJumpsAndTeleportsNothing(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Unit unit = Objects.requireNonNull(fixture.units.byId(MINE));
        final Collector<EntityTeleportEvent> teleports =
                env.trackEvent(EntityTeleportEvent.class, EventFilter.ENTITY, unit.entity());
        final double step = unit.archetype().speed() * Units.ADVANCE_INTERVAL_TICKS;

        fixture.receive(order(1, MINE, fixture.standNear(20, 0)));
        fixture.tick(1);

        Pos previous = unit.entity().getPosition();
        int advances = 0;
        for (int tick = 0; tick < WALK_TIMEOUT_TICKS && unit.state() == UnitState.MOVING; tick++) {
            fixture.tick(1);
            final Pos position = unit.entity().getPosition();
            if (position.equals(previous)) continue;
            assertTrue(position.distance(previous) <= step + 1e-6,
                    "a unit covered " + position.distance(previous) + " blocks in one advance, budget is " + step);
            previous = position;
            advances++;
        }
        assertTrue(advances > 50, "a twenty block walk has to take more than " + advances + " advances");
        // REQ-M2-025: the advance goes through Entity#moveTo, which states a position rather than
        // teleporting to it, so nothing along the way dispatches a teleport event
        teleports.assertEmpty();
    }

    @Test
    void anOrderIntoTheWalledOffPocketComesBackNoPath(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Unit unit = Objects.requireNonNull(fixture.units.byId(MINE));
        final Pos walledIn = insideThePocket(fixture);

        fixture.receive(order(1, MINE, walledIn));
        fixture.tick(1);

        final S2COrderResult result = fixture.channels.lastResult();
        assertEquals(OrderResultCode.NO_PATH, result.code());
        assertEquals((float) walledIn.x(), result.snappedX(), "a refusal echoes the requested point back");
        assertEquals(UnitState.IDLE, unit.state(), "a unit with nowhere to go set off anyway");
        final Map<String, ?> rejected = Objects.requireNonNull(fixture.events.last("unit_order_rejected"));
        assertEquals("no-path", rejected.get("code"));
        // §20: the search that answered this is the most expensive one the tick runs, and RB-M2-003
        // reads the count to tell a target nothing joins from a search that ran out of budget
        assertTrue((Integer) rejected.get("path_nodes") > Pathfinder.MAX_EXPANSIONS,
                "a refusal has to carry what the search spent: " + rejected);
    }

    @Test
    void theQueueRunsFourSearchesATickAndAnswersBusyOnceItIsFull(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Pos target = fixture.standNear(6, 0);

        // Four operations of eight packets each fill the queue exactly, and stay inside the ten
        // operations a second the order budget allows
        for (int seq = 1; seq <= Units.MAX_QUEUE_DEPTH + 1; seq++) {
            fixture.receive(new C2SMoveOrder(seq, (seq - 1) / 8, MINE,
                    (float) target.x(), (float) target.y(), (float) target.z(), 0));
        }

        final List<S2COrderResult> answered = fixture.channels.of(S2COrderResult.class);
        assertEquals(1, answered.size(), "an order that is queued is not answered before its search runs");
        assertEquals(OrderResultCode.BUSY, answered.getFirst().code());
        assertEquals(Units.MAX_QUEUE_DEPTH + 1, answered.getFirst().orderSeq(), "the wrong order was refused");
        assertEquals("busy", Objects.requireNonNull(fixture.events.last("unit_order_rejected")).get("code"));

        fixture.tick(1);
        assertEquals(Units.REQUESTS_PER_TICK, fixture.channels.of(S2COrderResult.class).size() - 1,
                "a tick ran more searches than §15 allows it");

        fixture.tick(Units.MAX_QUEUE_DEPTH / Units.REQUESTS_PER_TICK);
        assertEquals(Units.MAX_QUEUE_DEPTH + 1, fixture.channels.of(S2COrderResult.class).size(),
                "every order the queue took has to be answered, or the client waits forever");
    }

    @Test
    void aConnectionThatWentAwayLeavesNoOrderRunning(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Unit unit = Objects.requireNonNull(fixture.units.byId(MINE));
        fixture.receive(order(1, MINE, fixture.standNear(20, 0)));
        fixture.tick(1);
        assertEquals(UnitState.MOVING, unit.state());

        fixture.sessions.closed(fixture.connectionId());
        fixture.units.closed(fixture.connectionId());
        fixture.tick(1);

        assertEquals(UnitState.IDLE, unit.state(), "a unit kept walking for a connection that is gone");
        assertEquals("session-closed",
                Objects.requireNonNull(fixture.events.last("unit_order_superseded")).get("reason"));
    }

    @Test
    void aNewOrderReplacesThePathTheUnitWasOnAndSaysSoInTheLedger(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Unit unit = Objects.requireNonNull(fixture.units.byId(MINE));
        fixture.receive(order(1, MINE, fixture.standNear(20, 0)));
        fixture.tick(3);

        fixture.receive(order(2, MINE, fixture.standNear(-20, 0)));
        fixture.tick(1);

        assertEquals(UnitState.MOVING, unit.state(), "a replacing order left the unit standing still");
        final Map<String, ?> superseded = Objects.requireNonNull(fixture.events.last("unit_order_superseded"));
        assertEquals("replaced", superseded.get("reason"));
        assertEquals(1, superseded.get("order_seq"), "the order that was replaced is the one written off");
    }

    @Test
    void aMovingUnitIsReportedAsMovingInTheSnapshot(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        fixture.receive(order(1, MINE, fixture.standNear(20, 0)));
        fixture.tick(1);
        fixture.channels.clear();

        fixture.tick(Units.ADVANCE_INTERVAL_TICKS);

        final S2CUnitState snapshot = fixture.channels.of(S2CUnitState.class).getFirst();
        assertEquals(UnitState.MOVING, snapshot.entries().stream()
                .filter(entry -> entry.unitId() == MINE).findFirst().orElseThrow().unitState());
        assertFalse(snapshot.entries().stream()
                        .anyMatch(entry -> entry.unitId() != MINE && entry.unitState() != UnitState.IDLE),
                "a unit nobody ordered anywhere is moving");
    }

    /**
     * Ordered onto the very point it stands on, a unit has no direction to go: it keeps the one it faces
     * rather than taking the view of the waypoint, yaw 0 and pitch 0 as the pathfinder writes it.
     */
    @Test
    void aUnitOrderedOntoThePointItStandsOnKeepsItsFacing(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Unit unit = Objects.requireNonNull(fixture.units.byId(MINE));
        final Pos cell = unit.entity().getPosition();
        unit.entity().moveTo(cell.withView(37, 0));

        unit.follow(List.of(cell.withView(0, 0)), new Unit.Order(UUID.randomUUID(), 1, 0));
        unit.advance(2);

        final Pos position = unit.entity().getPosition();
        assertEquals(37f, position.yaw(), "the unit turned south on the spot");
        assertEquals(0f, position.pitch(), "the unit tipped over on the spot");
    }

    private static C2SMoveOrder order(int seq, int unitId, Pos target) {
        return new C2SMoveOrder(seq, seq, unitId, (float) target.x(), (float) target.y(), (float) target.z(), 0);
    }

    private static void walkUntilIdle(SliceFixture fixture, Unit unit) {
        for (int tick = 0; tick < WALK_TIMEOUT_TICKS && unit.state() == UnitState.MOVING; tick++) {
            fixture.tick(1);
        }
    }

    /** A walkable column of the map's walled off pocket, which nothing outside it can reach. */
    private static Pos insideThePocket(SliceFixture fixture) {
        final MapTerrain terrain = new MapTerrain(fixture.preset);
        for (int x = 0; x < fixture.preset.sizeX(); x++) {
            for (int z = 0; z < fixture.preset.sizeZ(); z++) {
                if (terrain.isInPocketArea(x, z) && fixture.grid.isWalkable(x, z))
                    return new Pos(x + 0.5, fixture.grid.surfaceY(x, z), z + 0.5);
            }
        }
        throw new AssertionError("the generated map has no walled off area");
    }
}
