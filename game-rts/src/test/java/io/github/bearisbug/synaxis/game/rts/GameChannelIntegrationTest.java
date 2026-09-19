package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.game.protocol.C2SCamera;
import io.github.bearisbug.synaxis.game.protocol.C2SMoveOrder;
import io.github.bearisbug.synaxis.game.protocol.C2SSelect;
import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static io.github.bearisbug.synaxis.game.rts.SliceFixture.MINE;
import static io.github.bearisbug.synaxis.game.rts.SliceFixture.NEUTRAL;
import static io.github.bearisbug.synaxis.game.rts.SliceFixture.OURS;
import static io.github.bearisbug.synaxis.game.rts.SliceFixture.THEIRS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the server does with the three inbound messages of the slice channel: decode them, judge
 * them against the {@code ACCEPT(order)} conjunction of {@code docs/DESIGN-M2.md} §11.4, spend the
 * budgets of §15 and answer every order with a result code (REQ-M2-009, {@code API-M2-003} to
 * {@code API-M2-005}).
 * <p>
 * The first five terms of the conjunction are decided where the payload arrives, so those cases
 * read the answer straight after handing the message in. The last two belong to the pathfinder, so
 * an order that gets that far is only answered once the unit task has run — which is what the
 * {@code fixture.tick(…)} calls below are: a tick, not a wait.
 */
@EnvTest
class GameChannelIntegrationTest {

    /** An order at the spawn column, which is walkable by construction. */
    private static C2SMoveOrder orderTo(SliceFixture fixture, int seq, int unitId) {
        return new C2SMoveOrder(seq, seq, unitId,
                (float) fixture.match.spawn().x(), (float) fixture.match.spawn().y(),
                (float) fixture.match.spawn().z(), 0);
    }

    @Test
    void anOrderOnAUnitOfOurOwnIsAcceptedAndSnappedToItsColumn(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final NavGrid grid = Objects.requireNonNull(fixture.match.navGrid());
        final int x = (int) fixture.match.spawn().x();
        final int z = (int) fixture.match.spawn().z();

        fixture.receive(new C2SMoveOrder(5, 5, MINE, x + 0.9f, 0f, z + 0.1f, 0));
        fixture.tick(1);

        final S2COrderResult result = fixture.channels.lastResult();
        assertEquals(OrderResultCode.OK, result.code());
        assertEquals(5, result.orderSeq());
        assertEquals(x + 0.5f, result.snappedX(), "the destination is the middle of the column");
        assertEquals(grid.surfaceY(x, z), result.snappedY(), "the destination is the standing height");
        assertEquals(z + 0.5f, result.snappedZ());
        assertEquals(Map.of("connection_id", fixture.connectionId(),
                        "match_id", fixture.match.matchId().toString(),
                        "order_seq", 5, "order_group", 5, "unit_id", MINE,
                        "path_nodes", 0, "path_ms", 0L),
                fixture.events.last("unit_order_issued"),
                "the ledger of an accepted order carries what the search cost (§20)");
    }

    @Test
    void anOrderOnAUnitThatIsNotOursComesBackNotOwner(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);

        // A neutral unit belongs to nobody, so it is nobody's to command (§9 owner)
        fixture.receive(orderTo(fixture, 1, NEUTRAL));
        assertEquals(OrderResultCode.NOT_OWNER, fixture.channels.lastResult().code());

        fixture.receive(orderTo(fixture, 2, THEIRS));
        assertEquals(OrderResultCode.NOT_OWNER, fixture.channels.lastResult().code());

        // A unit id that names nothing is not owned either, and is not an offence
        fixture.receive(orderTo(fixture, 3, 404));
        assertEquals(OrderResultCode.NOT_OWNER, fixture.channels.lastResult().code());
        assertTrue(fixture.player.isOnline(), "a gameplay refusal disconnected the player");
        final Map<String, ?> rejected = fixture.events.last("unit_order_rejected");
        assertNotNull(rejected, "a refused order left no trace in the ledger");
        assertEquals("not-owner", rejected.get("code"), "§14 names the codes, the ledger has to use those names");
    }

    @Test
    void aSeqThatIsNotGreaterThanTheLastAcceptedOneComesBackStale(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        fixture.receive(orderTo(fixture, 5, MINE));
        fixture.tick(1);
        assertEquals(OrderResultCode.OK, fixture.channels.lastResult().code());

        fixture.receive(orderTo(fixture, 5, MINE));
        assertEquals(OrderResultCode.STALE, fixture.channels.lastResult().code());

        fixture.receive(orderTo(fixture, 3, MINE));
        assertEquals(OrderResultCode.STALE, fixture.channels.lastResult().code());

        fixture.receive(orderTo(fixture, 6, MINE));
        fixture.tick(1);
        assertEquals(OrderResultCode.OK, fixture.channels.lastResult().code());
    }

    @Test
    void aRefusedOrderDoesNotMoveTheSequenceOn(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);

        // Refused for the unit, not for the sequence: seq 5 is still available afterwards
        fixture.receive(orderTo(fixture, 5, NEUTRAL));
        assertEquals(OrderResultCode.NOT_OWNER, fixture.channels.lastResult().code());

        fixture.receive(orderTo(fixture, 5, MINE));
        fixture.tick(1);
        assertEquals(OrderResultCode.OK, fixture.channels.lastResult().code());
    }

    @Test
    void aTargetOffTheMapOrOnAnObstacleComesBackOutOfBounds(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final float y = (float) fixture.match.spawn().y();

        fixture.receive(new C2SMoveOrder(1, 1, MINE, -4f, y, 4f, 0));
        assertEquals(OrderResultCode.OUT_OF_BOUNDS, fixture.channels.lastResult().code());

        fixture.receive(new C2SMoveOrder(2, 2, MINE, MapPreset.SMALL.sizeX() + 8f, y, 4f, 0));
        assertEquals(OrderResultCode.OUT_OF_BOUNDS, fixture.channels.lastResult().code());

        final int[] blocked = firstBlockedColumn(Objects.requireNonNull(fixture.match.navGrid()));
        fixture.receive(new C2SMoveOrder(3, 3, MINE, blocked[0] + 0.5f, y, blocked[1] + 0.5f, 0));
        final S2COrderResult result = fixture.channels.lastResult();
        assertEquals(OrderResultCode.OUT_OF_BOUNDS, result.code());
        assertEquals(blocked[0] + 0.5f, result.snappedX(), "a refusal echoes the requested point back");
    }

    @Test
    void goingOverTheOrderBudgetIsAnsweredNotPunished(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        for (int seq = 1; seq <= 20; seq++) fixture.receive(orderTo(fixture, seq, MINE));
        // The ten that got past the budget are waiting for a path, four searches to the tick
        fixture.tick(3);

        // Every request is answered, whatever the answer is: this is the only feedback path
        assertEquals(20, fixture.channels.of(S2COrderResult.class).size());
        final List<OrderResultCode> codes = fixture.channels.of(S2COrderResult.class).stream()
                .map(S2COrderResult::code).toList();
        assertEquals(10, codes.stream().filter(code -> code == OrderResultCode.RATE_LIMITED).count(),
                "the eleventh operation of a second onwards is refused, and only those");
        assertEquals(10, codes.stream().filter(code -> code == OrderResultCode.OK).count());
        assertTrue(fixture.player.isOnline(), "going over a gameplay budget cost the player the connection");

        fixture.advanceOneSecond();
        fixture.receive(orderTo(fixture, 21, MINE));
        fixture.tick(1);
        assertEquals(OrderResultCode.OK, fixture.channels.lastResult().code());
    }

    @Test
    void aRefusalTheClientPacesItselfIsNotWrittenToTheLedger(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);

        // A client may spend a sequence number again as often as it likes...
        fixture.receive(orderTo(fixture, 5, MINE));
        fixture.receive(orderTo(fixture, 5, MINE));
        assertEquals(OrderResultCode.STALE, fixture.channels.lastResult().code());

        // ...and it may overspend its order budget as often as it likes, far under the flood gate
        for (int seq = 6; seq <= 40; seq++) fixture.receive(orderTo(fixture, seq, MINE));
        assertEquals(OrderResultCode.RATE_LIMITED, fixture.channels.lastResult().code());
        assertTrue(fixture.player.isOnline());

        assertEquals(List.of(), fixture.events.names().stream()
                        .filter("unit_order_rejected"::equals).toList(),
                "§20 counts neither code, and writing them lets a client decide how much the ledger writes");
    }

    @Test
    void aSelectionKeepsOnlyTheUnitsTheSenderCommands(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);

        fixture.receive(new C2SSelect(List.of(NEUTRAL)));
        assertEquals(Set.of(), selection(fixture));
        assertTrue(fixture.channels.of(S2COrderResult.class).isEmpty(), "a selection is not answered, the client already drew it");

        fixture.receive(new C2SSelect(List.of(MINE, NEUTRAL, THEIRS, 404)));
        assertEquals(Set.of(MINE), selection(fixture),
                "a legal unit was dropped along with the illegal ones");
        assertEquals(Map.of("connection_id", fixture.connectionId(),
                        "match_id", fixture.match.matchId().toString(),
                        "count", 1, "rejected_count", 3),
                fixture.events.last("unit_selected"));
        assertTrue(fixture.player.isOnline());
    }

    @Test
    void theCameraAnchorIsClampedOntoTheMap(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        fixture.channels.clear();

        fixture.receive(new C2SCamera(-40f, MapPreset.SMALL.sizeZ() + 40f, 24f, 90f));

        assertEquals(0f, session(fixture).anchorX());
        assertEquals(MapPreset.SMALL.sizeZ() - 1f, session(fixture).anchorZ());
        assertTrue(fixture.channels.of(GameMessage.class).isEmpty(), "a camera report is not answered");
    }

    @Test
    void cameraReportsOverTheBudgetAreDropped(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        for (int i = 0; i < 10; i++) fixture.receive(new C2SCamera(8f, 8f, 24f, 0f));

        fixture.receive(new C2SCamera(40f, 40f, 24f, 0f));

        assertEquals(8f, session(fixture).anchorX(), "the dropped report still moved the camera");
        assertTrue(fixture.player.isOnline());
    }

    @Test
    void anUnknownMessageIdIsDroppedRatherThanPunished(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        fixture.channels.clear();

        fixture.receive((byte) 0x7F);

        assertTrue(fixture.player.isOnline(), "an id this channel does not define is not an offence");
        assertTrue(fixture.channels.of(GameMessage.class).isEmpty(), "an id nothing answers was answered");
    }

    @Test
    void aMalformedPayloadIsAProtocolError(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Collector<DisconnectPacket> disconnects = fixture.connection.trackIncoming(DisconnectPacket.class);

        // count = 9 on a message capped at 8 (TC-GAME-026)
        fixture.receive((byte) 0x02, (byte) 9, (byte) 1, (byte) 1, (byte) 1, (byte) 1,
                (byte) 1, (byte) 1, (byte) 1, (byte) 1, (byte) 1);

        assertFalse(fixture.player.isOnline());
        disconnects.assertSingle(packet -> assertEquals("synaxis:protocol-error",
                assertInstanceOf(TranslatableComponent.class, packet.message()).key()));
    }

    @Test
    void aMessageOnlyTheServerSendsIsAProtocolError(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);

        fixture.receive(new S2CUnitDespawn(List.of(MINE)));

        assertFalse(fixture.player.isOnline(), "the server accepted one of its own answers as a request");
    }

    @Test
    void threeSecondsOfFloodDisconnectTheConnection(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final Collector<DisconnectPacket> disconnects = fixture.connection.trackIncoming(DisconnectPacket.class);

        for (int second = 0; second < 3; second++) {
            for (int packet = 0; packet < 400 && fixture.player.isOnline(); packet++) {
                fixture.receive(new C2SCamera(8f, 8f, 24f, 0f));
            }
            fixture.advanceOneSecond();
        }

        assertFalse(fixture.player.isOnline(), "a scripted flood kept its connection");
        disconnects.assertSingle(packet -> assertEquals(DisconnectKeys.GAME_FLOOD,
                assertInstanceOf(TranslatableComponent.class, packet.message()).key()));
    }

    @Test
    void aPayloadBeforeThePlayPhaseIsDropped(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        fixture.player.getPlayerConnection().setServerState(ConnectionState.CONFIGURATION);
        fixture.channels.clear();

        fixture.receive(orderTo(fixture, 1, MINE));

        assertTrue(fixture.channels.of(GameMessage.class).isEmpty(), "a game message was served before the session was in game");
        assertTrue(fixture.player.isOnline());
    }

    @Test
    void aClosedConnectionGivesItsBookkeepingBack(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        fixture.receive(new C2SSelect(List.of(MINE)));
        assertEquals(1, fixture.sessions.open());

        fixture.sessions.closed(fixture.connectionId());

        assertEquals(0, fixture.sessions.open(), "the per connection bookkeeping outlived the connection");
    }

    @Test
    void aPayloadCannotPutBackTheBookkeepingOfAConnectionThatWentAway(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        fixture.sessions.closed(fixture.connectionId());
        fixture.channels.clear();

        fixture.receive(orderTo(fixture, 1, MINE));

        assertEquals(0, fixture.sessions.open(),
                "a payload made an entry that only admission makes and only the disconnect takes back");
        assertTrue(fixture.channels.of(GameMessage.class).isEmpty(), "a connection with no admission behind it was served");
    }

    @Test
    void anAdmissionThatLostTheRaceToItsOwnDisconnectMakesNoBookkeeping(Env env) {
        final SliceFixture fixture = SliceFixture.start(env);
        final UUID connectionId = fixture.connectionId();
        // The interleaving the reclamation has to survive: the connection goes away while its
        // admission is still being decided, so the disconnect runs first and finds nothing to give
        // back, and the admission callback lands on a connection that is already gone
        fixture.player.getPlayerConnection().disconnect();
        fixture.sessions.closed(connectionId);

        fixture.sessions.admitted(connectionId, OURS);

        assertEquals(0, fixture.sessions.open(),
                "an admission that lost the race to its own disconnect made an entry nobody reclaims");
    }

    private static GameSession session(SliceFixture fixture) {
        final GameSession session = fixture.sessions.get(fixture.connectionId());
        assertNotNull(session, "the connection has no bookkeeping at all");
        return session;
    }

    private static Set<Integer> selection(SliceFixture fixture) {
        return session(fixture).selection();
    }

    /**
     * @param grid the navigation mesh of the generated map
     * @return the x and z of a column a unit cannot stand on, which the map has by construction
     *         (REQ-M2-010 puts obstacles in it)
     */
    private static int[] firstBlockedColumn(NavGrid grid) {
        for (int x = 0; x < MapPreset.SMALL.sizeX(); x++) {
            for (int z = 0; z < MapPreset.SMALL.sizeZ(); z++) {
                if (!grid.isWalkable(x, z)) return new int[]{x, z};
            }
        }
        throw new AssertionError("the generated map has no obstacle to aim at");
    }
}
