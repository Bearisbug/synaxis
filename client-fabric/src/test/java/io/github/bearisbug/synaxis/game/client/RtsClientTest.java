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
    private final RtsClient client = new RtsClient(world, sent::add);

    private void spawn(int unitId, int entityId, int flags) {
        client.receive(new S2CUnitSpawn(unitId, entityId, 0, 1, 100, 100, flags));
        world.at(entityId, 10.0, 64.0, 0.0);
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
}
