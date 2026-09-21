package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.C2SMoveOrder;
import io.github.bearisbug.synaxis.game.protocol.C2SSelect;
import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitSpawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import io.github.bearisbug.synaxis.game.protocol.UnitState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The orchestration half of {@code REQ-M2-019}: what a click turns into on the wire, and what an
 * answer turns into locally. The click itself belongs to the input layer of stage 6b — here a
 * click is the ray it produces.
 */
class RtsClientTest {

    private static final Vec3 EYE = new Vec3(0.0, 64.5, 0.0);
    private static final Vec3 TOWARDS_THE_UNIT = new Vec3(1.0, 0.0, 0.0);
    private static final Vec3 NOWHERE = new Vec3(0.0, 1.0, 0.0);

    private final List<GameMessage> sent = new ArrayList<>();
    private final FakeWorldView world = new FakeWorldView();

    /** The clock the budgets of §15 are counted against; tests move it by whole seconds. */
    private long nanos = TimeUnit.SECONDS.toNanos(1_000);

    private final RtsClient client = new RtsClient(world, () -> nanos, sent::add);

    private void spawn(int unitId, int entityId, int flags) {
        client.receive(new S2CUnitSpawn(unitId, entityId, 0, 1, 100, 100, flags));
        world.at(entityId, 10.0, 64.0, 0.0);
    }

    private void secondsLater(long seconds) {
        nanos += TimeUnit.SECONDS.toNanos(seconds);
    }

    @Test
    void clickingAUnitSelectsItAndReportsThatSelection() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);

        client.selectAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(new C2SSelect(List.of(7))), sent);
        assertEquals(Integer.valueOf(7), client.selected());
    }

    @Test
    void clickingNothingClearsTheSelectionOnBothEnds() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        sent.clear();

        client.selectAt(EYE, NOWHERE);

        assertEquals(List.of(new C2SSelect(List.of())), sent);
        assertNull(client.selected());
    }

    /**
     * §19: a unit somebody else owns is reported as picked and dropped by the server, which is the
     * only place that judgement is made. The client does not pre-empt it.
     */
    @Test
    void clickingAUnitThisPlayerDoesNotOwnStillReportsIt() {
        spawn(65, 4712, 0);

        client.selectAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(new C2SSelect(List.of(65))), sent);
    }

    @Test
    void anOrderCarriesTheGroundPointAndOneUnit() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.ground(new Vec3(12.5, 64.0, -30.25));
        sent.clear();

        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(new C2SMoveOrder(1, 1, 7, 12.5f, 64.0f, -30.25f, 0)), sent);
    }

    /**
     * §9: {@code order_seq} is per connection and strictly increasing, and M2 single selects, so
     * {@code order_group} is that same number.
     */
    @Test
    void everyOrderOnAConnectionCarriesTheNextSequenceNumber() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.ground(new Vec3(1.0, 64.0, 1.0));
        sent.clear();

        client.orderAt(EYE, TOWARDS_THE_UNIT);
        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(1, 2), sent.stream().map(m -> ((C2SMoveOrder) m).orderSeq()).toList());
        assertEquals(List.of(1, 2), sent.stream().map(m -> ((C2SMoveOrder) m).orderGroup()).toList());
    }

    @Test
    void orderingWithNothingSelectedSendsNothing() {
        world.ground(new Vec3(1.0, 64.0, 1.0));

        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(), sent);
    }

    @Test
    void anOrderThatHitsNoGroundSendsNothing() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        sent.clear();

        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(), sent);
    }

    /** §14: {@code not-owner} is the answer the client corrects its own selection against. */
    @Test
    void aNotOwnerAnswerCorrectsTheLocalSelection() {
        spawn(65, 4712, 0);
        client.selectAt(EYE, TOWARDS_THE_UNIT);

        client.receive(new S2COrderResult(1, OrderResultCode.NOT_OWNER, 1.0f, 64.0f, 1.0f));

        assertNull(client.selected());
    }

    @Test
    void anAcceptedOrderLeavesTheSelectionAlone() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);

        client.receive(new S2COrderResult(1, OrderResultCode.OK, 1.0f, 64.0f, 1.0f));

        assertEquals(Integer.valueOf(7), client.selected());
    }

    @Test
    void aSelectedUnitLeavingTheInterestSetClearsTheSelection() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);

        client.receive(new S2CUnitDespawn(List.of(7)));

        assertNull(client.selected());
        assertNull(client.bindings().get(7));
    }

    @Test
    void aDespawnThatDoesNotNameTheSelectedUnitLeavesItSelected() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);

        client.receive(new S2CUnitDespawn(List.of(9)));

        assertEquals(Integer.valueOf(7), client.selected());
    }

    /**
     * A selection is a {@code unit_id}, so losing the entity — a world switch, an unloaded chunk —
     * costs the player the ability to click the unit again, not the order they are giving it. The
     * server answers for the unit either way; the client does not silently swallow the order.
     */
    @Test
    void aSelectedUnitWhoseEntityWentAwayCanStillBeOrdered() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.forget(4711).ground(new Vec3(1.0, 64.0, 1.0));
        sent.clear();

        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(new C2SMoveOrder(1, 1, 7, 1.0f, 64.0f, 1.0f, 0)), sent);
    }

    @Test
    void aSnapshotReachesTheBindingTable() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);

        client.receive(new S2CUnitState(41, List.of(new S2CUnitState.Entry(7, UnitState.MOVING, 90))));

        assertEquals(UnitState.MOVING, client.bindings().get(7).state());
    }

    @Test
    void aNewConnectionStartsWithNoBindingsNoSelectionAndSequenceOne() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.ground(new Vec3(1.0, 64.0, 1.0));
        client.orderAt(EYE, TOWARDS_THE_UNIT);

        client.reset();
        assertNull(client.selected());
        assertNull(client.bindings().get(7));

        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        sent.clear();
        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(1, ((C2SMoveOrder) sent.getFirst()).orderSeq());
    }

    @Test
    void theOrdersRayIsTheOneTheClickProduced() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.ground(new Vec3(1.0, 64.0, 1.0));

        client.orderAt(EYE, NOWHERE);

        assertEquals(EYE, world.lastGroundOrigin());
        assertEquals(NOWHERE, world.lastGroundDirection());
    }

    /**
     * §15 gives a connection ten selections a second and the server drops the rest silently. A
     * dropped selection would leave the two ends disagreeing about what is selected — the message
     * replaces the whole set — so the click that cannot be reported does not happen at all.
     */
    @Test
    void theEleventhSelectionOfASecondIsNotSentAndDoesNotChangeTheSelection() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        for (int i = 0; i < 10; i++) client.selectAt(EYE, TOWARDS_THE_UNIT);
        sent.clear();

        client.selectAt(EYE, NOWHERE);

        assertEquals(List.of(), sent);
        assertEquals(Integer.valueOf(7), client.selected());
    }

    @Test
    void theNextSecondGivesTheSelectionBudgetBack() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        for (int i = 0; i < 11; i++) client.selectAt(EYE, TOWARDS_THE_UNIT);
        sent.clear();

        secondsLater(1);
        client.selectAt(EYE, NOWHERE);

        assertEquals(List.of(new C2SSelect(List.of())), sent);
        assertNull(client.selected());
    }

    /**
     * §14: an order over the budget is the one rejection the client is told to keep to itself —
     * the server would answer {@code rate-limited} and §14 adds that the client should have
     * throttled. It answers itself with the same code so that the click still leaves a mark.
     */
    @Test
    void theEleventhOrderOfASecondIsAnsweredWithoutTheServer() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.ground(new Vec3(1.0, 64.0, 1.0));
        for (int i = 0; i < 10; i++) client.orderAt(EYE, TOWARDS_THE_UNIT);
        sent.clear();

        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(), sent);
        assertEquals(OrderResultCode.RATE_LIMITED, client.feedback().code());
        assertEquals(new Vec3(1.0, 64.0, 1.0), client.feedback().point());
    }

    /**
     * §8 has {@code order_seq} strictly increasing and nothing else; an order that never went out
     * must not spend one, or the server's answer to the next order would name a number the client
     * cannot match to a click.
     */
    @Test
    void anOrderTheClientThrottledDoesNotSpendASequenceNumber() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.ground(new Vec3(1.0, 64.0, 1.0));
        for (int i = 0; i < 11; i++) client.orderAt(EYE, TOWARDS_THE_UNIT);
        sent.clear();

        secondsLater(1);
        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(11, ((C2SMoveOrder) sent.getFirst()).orderSeq());
    }

    /**
     * The two budgets are counted apart, as they are on the server: a player clicking units does
     * not spend the orders they are about to give.
     */
    @Test
    void selectingDoesNotSpendTheOrderBudget() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        for (int i = 0; i < 10; i++) client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.ground(new Vec3(1.0, 64.0, 1.0));
        sent.clear();

        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(new C2SMoveOrder(1, 1, 7, 1.0f, 64.0f, 1.0f, 0)), sent);
    }

    @Test
    void anAcceptedOrderMarksTheSnappedPoint() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);

        client.receive(new S2COrderResult(1, OrderResultCode.OK, 1.5f, 64.0f, -2.5f));

        assertEquals(OrderFeedback.Marker.GREEN, client.feedback().marker());
        assertEquals(new Vec3(1.5, 64.0, -2.5), client.feedback().point());
    }

    /** §14: {@code stale} is a retransmission or a reordering, and the player is told nothing. */
    @Test
    void aStaleAnswerLeavesTheLastFeedbackWhereItWas() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.receive(new S2COrderResult(1, OrderResultCode.OK, 1.5f, 64.0f, -2.5f));

        client.receive(new S2COrderResult(1, OrderResultCode.STALE, 9.0f, 64.0f, 9.0f));

        assertEquals(OrderResultCode.OK, client.feedback().code());
    }

    @Test
    void aNewConnectionStartsWithNoFeedbackAndBothBudgetsBack() {
        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        world.ground(new Vec3(1.0, 64.0, 1.0));
        for (int i = 0; i < 11; i++) client.orderAt(EYE, TOWARDS_THE_UNIT);
        for (int i = 0; i < 11; i++) client.selectAt(EYE, TOWARDS_THE_UNIT);

        client.reset();
        assertNull(client.feedback());

        spawn(7, 4711, S2CUnitSpawn.FLAG_COMMANDABLE);
        client.selectAt(EYE, TOWARDS_THE_UNIT);
        sent.clear();
        client.orderAt(EYE, TOWARDS_THE_UNIT);

        assertEquals(List.of(new C2SMoveOrder(1, 1, 7, 1.0f, 64.0f, 1.0f, 0)), sent);
    }
}
