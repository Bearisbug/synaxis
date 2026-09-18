package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.protocol.C2SCamera;
import io.github.bearisbug.synaxis.game.protocol.C2SMoveOrder;
import io.github.bearisbug.synaxis.game.protocol.C2SSelect;
import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.GameMessages;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.instance.InstanceContainer;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.packet.server.common.DisconnectPacket;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.PayloadHandler;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import io.github.bearisbug.synaxis.testing.Collector;
import io.github.bearisbug.synaxis.testing.Env;
import io.github.bearisbug.synaxis.testing.EnvTest;
import io.github.bearisbug.synaxis.testing.TestConnection;
import net.kyori.adventure.text.TranslatableComponent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the server does with the three inbound messages of the slice channel before any unit moves:
 * decode them, judge them against the {@code ACCEPT(order)} conjunction of
 * {@code docs/DESIGN-M2.md} §11.4, spend the budgets of §15 and answer every order with a result
 * code (REQ-M2-009, {@code API-M2-003} to {@code API-M2-005}).
 * <p>
 * Units themselves arrive with REQ-M2-016, so the unit table is filled here directly: what is under
 * test is the judgement, and the judgement only ever reads a unit's team.
 * <p>
 * The clock is handed in rather than read from the system, because every budget in here is "per
 * second" and a test that waited seconds out would spend them on every build.
 */
@EnvTest
class GameChannelIntegrationTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameChannelIntegrationTest.class);

    /** The team the admission provider hands a connecting player (M2 has one). */
    private static final int OURS = 1;

    /** A unit of ours, a neutral one and one of the other team, as {@code TC-GAME-039} needs. */
    private static final int MINE = 1;
    private static final int NEUTRAL = 2;
    private static final int THEIRS = 3;

    private final AtomicLong nanos = new AtomicLong();

    /**
     * The channel capability of the host, reduced to what this plugin does with it: keep every
     * payload that went out, decoded. Registration is the plugin's business rather than the
     * channel's, and {@code RtsPluginIntegrationTest} is where it is asserted.
     */
    private static final class RecordedChannels implements PluginChannels {

        private final List<GameMessage> sent = new ArrayList<>();

        @Override
        public void register(String name, PayloadHandler handler) {
            throw new UnsupportedOperationException("the channel under test is handed its sends, not its name");
        }

        @Override
        public void send(Player player, String name, byte[] payload) {
            sent.add(GameMessages.read(MsgBuf.reading(payload)));
        }

        S2COrderResult lastResult() {
            assertFalse(sent.isEmpty(), "the server answered nothing at all");
            return assertInstanceOf(S2COrderResult.class, sent.getLast());
        }
    }

    /** Everything one test needs: a running match, a connected player and the channel between. */
    private record Fixture(Match match, GameChannel channel, RecordedChannels channels,
                           RecordedEvents events, TestConnection connection, Player player) {

        void receive(GameMessage message) {
            final MsgBuf buf = MsgBuf.writing();
            message.write(buf);
            channel.receive(player, buf.toByteArray());
        }

        void receive(byte... payload) {
            channel.receive(player, payload);
        }
    }

    private Fixture start(Env env) {
        final RecordedEvents events = new RecordedEvents();
        final Match match = new Match(env.process(), MatchSupport.registerMatchDimension(env), events, LOGGER);
        match.load(MapPreset.SMALL.id());
        final InstanceContainer instance = Objects.requireNonNull(match.instance(), "the match did not come up");
        match.registerUnit(MINE, OURS);
        match.registerUnit(NEUTRAL, 0);
        match.registerUnit(THEIRS, 2);

        final RecordedChannels channels = new RecordedChannels();
        final GameChannel channel = new GameChannel(match, channels, events, nanos::get);
        final TestConnection connection = env.createConnection();
        final Player player = connection.connect(instance, match.spawn());
        channel.admitted(player.getPlayerConnection().connectionId(), OURS);
        return new Fixture(match, channel, channels, events, connection, player);
    }

    private void advanceOneSecond() {
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
    }

    /** An order at the spawn column, which is walkable by construction. */
    private static C2SMoveOrder orderTo(Fixture fixture, int seq, int unitId) {
        return new C2SMoveOrder(seq, seq, unitId,
                (float) fixture.match().spawn().x(), (float) fixture.match().spawn().y(),
                (float) fixture.match().spawn().z(), 0);
    }

    @Test
    void anOrderOnAUnitOfOurOwnIsAcceptedAndSnappedToItsColumn(Env env) {
        final Fixture fixture = start(env);
        final NavGrid grid = Objects.requireNonNull(fixture.match().navGrid());
        final int x = (int) fixture.match().spawn().x();
        final int z = (int) fixture.match().spawn().z();

        fixture.receive(new C2SMoveOrder(5, 5, MINE, x + 0.9f, 0f, z + 0.1f, 0));

        final S2COrderResult result = fixture.channels().lastResult();
        assertEquals(OrderResultCode.OK, result.code());
        assertEquals(5, result.orderSeq());
        assertEquals(x + 0.5f, result.snappedX(), "the destination is the middle of the column");
        assertEquals(grid.surfaceY(x, z), result.snappedY(), "the destination is the standing height");
        assertEquals(z + 0.5f, result.snappedZ());
        assertEquals(Map.of("connection_id", fixture.player().getPlayerConnection().connectionId(),
                        "match_id", fixture.match().matchId().toString(),
                        "order_seq", 5, "order_group", 5, "unit_id", MINE),
                fixture.events().last("unit_order_issued"));
    }

    @Test
    void anOrderOnAUnitThatIsNotOursComesBackNotOwner(Env env) {
        final Fixture fixture = start(env);

        // A neutral unit belongs to nobody, so it is nobody's to command (§9 owner)
        fixture.receive(orderTo(fixture, 1, NEUTRAL));
        assertEquals(OrderResultCode.NOT_OWNER, fixture.channels().lastResult().code());

        fixture.receive(orderTo(fixture, 2, THEIRS));
        assertEquals(OrderResultCode.NOT_OWNER, fixture.channels().lastResult().code());

        // A unit id that names nothing is not owned either, and is not an offence
        fixture.receive(orderTo(fixture, 3, 404));
        assertEquals(OrderResultCode.NOT_OWNER, fixture.channels().lastResult().code());
        assertTrue(fixture.player().isOnline(), "a gameplay refusal disconnected the player");
        final Map<String, ?> rejected = fixture.events().last("unit_order_rejected");
        assertNotNull(rejected, "a refused order left no trace in the ledger");
        assertEquals("not-owner", rejected.get("code"), "§14 names the codes, the ledger has to use those names");
    }

    @Test
    void aSeqThatIsNotGreaterThanTheLastAcceptedOneComesBackStale(Env env) {
        final Fixture fixture = start(env);
        fixture.receive(orderTo(fixture, 5, MINE));
        assertEquals(OrderResultCode.OK, fixture.channels().lastResult().code());

        fixture.receive(orderTo(fixture, 5, MINE));
        assertEquals(OrderResultCode.STALE, fixture.channels().lastResult().code());

        fixture.receive(orderTo(fixture, 3, MINE));
        assertEquals(OrderResultCode.STALE, fixture.channels().lastResult().code());

        fixture.receive(orderTo(fixture, 6, MINE));
        assertEquals(OrderResultCode.OK, fixture.channels().lastResult().code());
    }

    @Test
    void aRefusedOrderDoesNotMoveTheSequenceOn(Env env) {
        final Fixture fixture = start(env);

        // Refused for the unit, not for the sequence: seq 5 is still available afterwards
        fixture.receive(orderTo(fixture, 5, NEUTRAL));
        assertEquals(OrderResultCode.NOT_OWNER, fixture.channels().lastResult().code());

        fixture.receive(orderTo(fixture, 5, MINE));
        assertEquals(OrderResultCode.OK, fixture.channels().lastResult().code());
    }

    @Test
    void aTargetOffTheMapOrOnAnObstacleComesBackOutOfBounds(Env env) {
        final Fixture fixture = start(env);
        final float y = (float) fixture.match().spawn().y();

        fixture.receive(new C2SMoveOrder(1, 1, MINE, -4f, y, 4f, 0));
        assertEquals(OrderResultCode.OUT_OF_BOUNDS, fixture.channels().lastResult().code());

        fixture.receive(new C2SMoveOrder(2, 2, MINE, MapPreset.SMALL.sizeX() + 8f, y, 4f, 0));
        assertEquals(OrderResultCode.OUT_OF_BOUNDS, fixture.channels().lastResult().code());

        final int[] blocked = firstBlockedColumn(Objects.requireNonNull(fixture.match().navGrid()));
        fixture.receive(new C2SMoveOrder(3, 3, MINE, blocked[0] + 0.5f, y, blocked[1] + 0.5f, 0));
        final S2COrderResult result = fixture.channels().lastResult();
        assertEquals(OrderResultCode.OUT_OF_BOUNDS, result.code());
        assertEquals(blocked[0] + 0.5f, result.snappedX(), "a refusal echoes the requested point back");
    }

    @Test
    void goingOverTheOrderBudgetIsAnsweredNotPunished(Env env) {
        final Fixture fixture = start(env);
        for (int seq = 1; seq <= 20; seq++) fixture.receive(orderTo(fixture, seq, MINE));

        // Every request is answered, whatever the answer is: this is the only feedback path
        assertEquals(20, fixture.channels().sent.size());
        final List<OrderResultCode> codes = fixture.channels().sent.stream()
                .map(message -> ((S2COrderResult) message).code()).toList();
        assertEquals(List.of(), codes.subList(0, 10).stream()
                .filter(code -> code == OrderResultCode.RATE_LIMITED).toList());
        assertEquals(10, codes.subList(10, 20).stream()
                .filter(code -> code == OrderResultCode.RATE_LIMITED).count());
        assertTrue(fixture.player().isOnline(), "going over a gameplay budget cost the player the connection");

        advanceOneSecond();
        fixture.receive(orderTo(fixture, 21, MINE));
        assertEquals(OrderResultCode.OK, fixture.channels().lastResult().code());
    }

    @Test
    void aRefusalTheClientPacesItselfIsNotWrittenToTheLedger(Env env) {
        final Fixture fixture = start(env);

        // A client may spend a sequence number again as often as it likes...
        fixture.receive(orderTo(fixture, 5, MINE));
        fixture.receive(orderTo(fixture, 5, MINE));
        assertEquals(OrderResultCode.STALE, fixture.channels().lastResult().code());

        // ...and it may overspend its order budget as often as it likes, far under the flood gate
        for (int seq = 6; seq <= 40; seq++) fixture.receive(orderTo(fixture, seq, MINE));
        assertEquals(OrderResultCode.RATE_LIMITED, fixture.channels().lastResult().code());
        assertTrue(fixture.player().isOnline());

        assertEquals(List.of(), fixture.events().names().stream()
                        .filter("unit_order_rejected"::equals).toList(),
                "§20 counts neither code, and writing them lets a client decide how much the ledger writes");
    }

    @Test
    void aSelectionKeepsOnlyTheUnitsTheSenderCommands(Env env) {
        final Fixture fixture = start(env);

        fixture.receive(new C2SSelect(List.of(NEUTRAL)));
        assertEquals(Set.of(), selection(fixture));
        assertTrue(fixture.channels().sent.isEmpty(), "a selection is not answered, the client already drew it");

        fixture.receive(new C2SSelect(List.of(MINE, NEUTRAL, THEIRS, 404)));
        assertEquals(Set.of(MINE), selection(fixture),
                "a legal unit was dropped along with the illegal ones");
        assertEquals(Map.of("connection_id", fixture.player().getPlayerConnection().connectionId(),
                        "match_id", fixture.match().matchId().toString(),
                        "count", 1, "rejected_count", 3),
                fixture.events().last("unit_selected"));
        assertTrue(fixture.player().isOnline());
    }

    @Test
    void theCameraAnchorIsClampedOntoTheMap(Env env) {
        final Fixture fixture = start(env);

        fixture.receive(new C2SCamera(-40f, MapPreset.SMALL.sizeZ() + 40f, 24f, 90f));

        assertEquals(0f, session(fixture).anchorX());
        assertEquals(MapPreset.SMALL.sizeZ() - 1f, session(fixture).anchorZ());
        assertTrue(fixture.channels().sent.isEmpty(), "a camera report is not answered");
    }

    @Test
    void cameraReportsOverTheBudgetAreDropped(Env env) {
        final Fixture fixture = start(env);
        for (int i = 0; i < 10; i++) fixture.receive(new C2SCamera(8f, 8f, 24f, 0f));

        fixture.receive(new C2SCamera(40f, 40f, 24f, 0f));

        assertEquals(8f, session(fixture).anchorX(), "the dropped report still moved the camera");
        assertTrue(fixture.player().isOnline());
    }

    @Test
    void anUnknownMessageIdIsDroppedRatherThanPunished(Env env) {
        final Fixture fixture = start(env);

        fixture.receive((byte) 0x7F);

        assertTrue(fixture.player().isOnline(), "an id this channel does not define is not an offence");
        assertTrue(fixture.channels().sent.isEmpty());
    }

    @Test
    void aMalformedPayloadIsAProtocolError(Env env) {
        final Fixture fixture = start(env);
        final Collector<DisconnectPacket> disconnects = fixture.connection().trackIncoming(DisconnectPacket.class);

        // count = 9 on a message capped at 8 (TC-GAME-026)
        fixture.receive((byte) 0x02, (byte) 9, (byte) 1, (byte) 1, (byte) 1, (byte) 1,
                (byte) 1, (byte) 1, (byte) 1, (byte) 1, (byte) 1);

        assertFalse(fixture.player().isOnline());
        disconnects.assertSingle(packet -> assertEquals("synaxis:protocol-error",
                assertInstanceOf(TranslatableComponent.class, packet.message()).key()));
    }

    @Test
    void aMessageOnlyTheServerSendsIsAProtocolError(Env env) {
        final Fixture fixture = start(env);

        fixture.receive(new S2CUnitDespawn(List.of(MINE)));

        assertFalse(fixture.player().isOnline(), "the server accepted one of its own answers as a request");
    }

    @Test
    void threeSecondsOfFloodDisconnectTheConnection(Env env) {
        final Fixture fixture = start(env);
        final Collector<DisconnectPacket> disconnects = fixture.connection().trackIncoming(DisconnectPacket.class);

        for (int second = 0; second < 3; second++) {
            for (int packet = 0; packet < 400 && fixture.player().isOnline(); packet++) {
                fixture.receive(new C2SCamera(8f, 8f, 24f, 0f));
            }
            advanceOneSecond();
        }

        assertFalse(fixture.player().isOnline(), "a scripted flood kept its connection");
        disconnects.assertSingle(packet -> assertEquals(DisconnectKeys.GAME_FLOOD,
                assertInstanceOf(TranslatableComponent.class, packet.message()).key()));
    }

    @Test
    void aPayloadBeforeThePlayPhaseIsDropped(Env env) {
        final Fixture fixture = start(env);
        fixture.player().getPlayerConnection().setServerState(ConnectionState.CONFIGURATION);

        fixture.receive(orderTo(fixture, 1, MINE));

        assertTrue(fixture.channels().sent.isEmpty(), "a game message was served before the session was in game");
        assertTrue(fixture.player().isOnline());
    }

    @Test
    void aClosedConnectionGivesItsBookkeepingBack(Env env) {
        final Fixture fixture = start(env);
        fixture.receive(new C2SSelect(List.of(MINE)));
        assertEquals(1, fixture.channel().openSessions());

        fixture.channel().closed(fixture.player().getPlayerConnection().connectionId());

        assertEquals(0, fixture.channel().openSessions(), "the per connection bookkeeping outlived the connection");
    }

    @Test
    void aPayloadCannotPutBackTheBookkeepingOfAConnectionThatWentAway(Env env) {
        final Fixture fixture = start(env);
        fixture.channel().closed(fixture.player().getPlayerConnection().connectionId());

        fixture.receive(orderTo(fixture, 1, MINE));

        assertEquals(0, fixture.channel().openSessions(),
                "a payload made an entry that only admission makes and only the disconnect takes back");
        assertTrue(fixture.channels().sent.isEmpty(), "a connection with no admission behind it was served");
    }

    private static GameSession session(Fixture fixture) {
        final GameSession session = fixture.channel().session(fixture.player().getPlayerConnection().connectionId());
        assertNotNull(session, "the connection has no bookkeeping at all");
        return session;
    }

    private static Set<Integer> selection(Fixture fixture) {
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
