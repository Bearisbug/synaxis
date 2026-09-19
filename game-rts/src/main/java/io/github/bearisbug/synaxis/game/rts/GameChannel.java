package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.protocol.C2SCamera;
import io.github.bearisbug.synaxis.game.protocol.C2SMoveOrder;
import io.github.bearisbug.synaxis.game.protocol.C2SSelect;
import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.GameMessages;
import io.github.bearisbug.synaxis.game.protocol.MalformedMessageException;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import io.github.bearisbug.synaxis.game.protocol.UnknownMessageException;
import io.github.bearisbug.synaxis.network.ConnectionState;
import io.github.bearisbug.synaxis.network.DisconnectReasons;
import io.github.bearisbug.synaxis.network.player.PlayerConnection;
import io.github.bearisbug.synaxis.plugin.DisconnectKeys;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The {@code rts:game} channel: the server side of the slice messages of {@code API-M2-003} to
 * {@code API-M2-005} (REQ-M2-009).
 * <p>
 * <b>Everything that arrives here is chosen by a client</b> — which message, how many of them and
 * how often — so this class is the high risk spot for both of the things §15 bounds. Hence the two
 * rules it is written under: every decision is the server's (the client is told the outcome, it
 * never states one), and nothing a payload does writes to disk on its own — every log line goes
 * through a {@link LogBudget}, and the only refusals that reach the event ledger are the ones the
 * order budget bounds ({@link #LEDGERED_REFUSALS}).
 * <p>
 * <b>Refusing an order and refusing a payload are different things.</b> A gameplay refusal is
 * answered in band with an {@code order_result_code} and costs nothing else (§14); only a payload
 * that does not decode, or a flood, ends a connection. The two limits behind that split live in
 * {@link GameSession}.
 * <p>
 * <b>Where an accepted order goes.</b> The last term of the {@code ACCEPT(order)} conjunction of
 * §11.4 — a path exists — cannot be judged here: a search costs too much to run inside a payload
 * handler, so it is queued and {@link Units} answers it when it runs. Everything this class can
 * decide on its own it decides before queueing, including the {@code busy} that says the queue is
 * full, so an order only waits when it really is going to be searched for.
 */
final class GameChannel {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameChannel.class);

    /**
     * The channel name inside the plugin's namespace; the host makes it {@code rts:game}.
     */
    static final String NAME = "game";

    /**
     * The refusals §20 keeps: the ones that say something about the server's answer, and the only
     * ones whose rate the server sets. {@code stale} and {@code rate-limited} are the client's own
     * retransmits and overspend — it can produce them as fast as it can send, all the way up to the
     * flood gate — and one ledger event is one append on the tick thread, so writing them would put
     * both the size of that file and a disk write per payload in the client's hands (§15).
     */
    private static final Set<OrderResultCode> LEDGERED_REFUSALS = EnumSet.of(OrderResultCode.NOT_OWNER,
            OrderResultCode.OUT_OF_BOUNDS, OrderResultCode.BUSY, OrderResultCode.NO_PATH);

    private final Match match;
    private final MapPreset preset;
    private final NavGrid navGrid;
    private final GameSessions sessions;
    private final Units units;
    private final PluginChannels channels;
    private final PluginAnalytics analytics;
    private final LongSupplier clock;

    /**
     * The budget of the drop line. Shared by every unknown {@code msg_id} there is: the client
     * invents the id, so a budget per id would be a budget per payload.
     */
    private final LogBudget drops = new LogBudget(LOGGER, "unknown game messages");

    /** The budget of the protocol error line, one per connection at most before the kick. */
    private final LogBudget refusals = new LogBudget(LOGGER, "refused game payloads");

    /** The budget of the flood line, which §14 asks to carry the connection id. */
    private final LogBudget floods = new LogBudget(LOGGER, "flooding connections");

    GameChannel(Match match, GameSessions sessions, Units units, PluginChannels channels,
                PluginAnalytics analytics, LongSupplier clock) {
        this.match = match;
        this.preset = Objects.requireNonNull(match.preset(), "the match has no map");
        this.navGrid = Objects.requireNonNull(match.navGrid(), "the match has no navigation mesh");
        this.sessions = sessions;
        this.units = units;
        this.channels = channels;
        this.analytics = analytics;
        this.clock = clock;
    }

    /**
     * Handles one payload on this channel, on the tick thread.
     *
     * @param player  the sender
     * @param payload the payload bytes
     */
    void receive(Player player, byte[] payload) {
        final PlayerConnection connection = player.getPlayerConnection();
        if (!connection.isOnline()) return;
        final UUID connectionId = connection.connectionId();
        // Admission makes the entry and the disconnect takes it back, so a payload only ever looks
        // one up: a payload that made one could put back an entry a disconnect has just reclaimed,
        // and then nothing would ever reclaim it again
        final GameSession session = sessions.get(connectionId);
        if (session == null) return;
        final long second = TimeUnit.NANOSECONDS.toSeconds(clock.getAsLong());

        // Counted before anything else: a flood is about how many payloads arrive, not about what
        // is in them, and the cheapest payload to produce is one that does not decode
        if (session.payloadFloods(second)) {
            floods.warn("Disconnecting a flooding connection: connection_id={} reason={}",
                    connectionId, DisconnectKeys.GAME_FLOOD);
            connection.kick(Component.translatable(DisconnectKeys.GAME_FLOOD));
            return;
        }
        // Only a session that is in game plays the game (API-M2-001): a payload that arrives while
        // the connection is still being configured is dropped, not answered and not punished
        if (connection.getServerState() != ConnectionState.PLAY) return;

        final GameMessage message;
        try {
            message = GameMessages.read(MsgBuf.reading(payload));
        } catch (UnknownMessageException e) {
            // §9: an id this channel does not define is a fact about the sender, not an offence
            drops.warn("Dropped an unknown game message: connection_id={} msg_id=0x{}",
                    connectionId, Integer.toHexString(e.msgId()));
            return;
        } catch (MalformedMessageException e) {
            refuse(connection, connectionId, "the game payload does not decode: " + e.getMessage());
            return;
        }
        switch (message) {
            case C2SCamera camera -> receiveCamera(session, camera, second);
            case C2SSelect select -> receiveSelect(session, connectionId, select, second);
            case C2SMoveOrder order -> receiveOrder(player, session, connectionId, order, second);
            default -> refuse(connection, connectionId,
                    "msg_id=0x" + Integer.toHexString(message.msgId()) + " is an answer of ours, not a request");
        }
    }

    /**
     * {@code API-M2-003}: the anchor is clamped onto the map and kept; reports over the budget are
     * dropped without an answer. Driving the chunk stream from it is REQ-M2-018.
     */
    private void receiveCamera(GameSession session, C2SCamera camera, long second) {
        if (!session.allowCamera(second)) return;
        session.camera(Math.clamp(camera.anchorX(), 0f, preset.sizeX() - 1f),
                Math.clamp(camera.anchorZ(), 0f, preset.sizeZ() - 1f));
    }

    /**
     * {@code API-M2-004}: the units the sender does not command are dropped from the set one by one
     * — silently, and without taking the legal ones down with them. Selection is not answered: the
     * client has already drawn its own box (REQ-M2-016).
     */
    private void receiveSelect(GameSession session, UUID connectionId, C2SSelect select, long second) {
        if (!session.allowSelect(second)) return;
        final Set<Integer> commanded = new LinkedHashSet<>();
        for (final int unitId : select.unitIds()) {
            if (commands(session, unitId)) commanded.add(unitId);
        }
        session.select(commanded);
        analytics.record("unit_selected", attributes(connectionId,
                "count", commanded.size(), "rejected_count", select.unitIds().size() - commanded.size()));
    }

    /**
     * {@code API-M2-005}: judge the order and answer it. Every request gets exactly one answer,
     * accepted or not — it is the only way a client learns that the ground it clicked is out of
     * reach.
     */
    private void receiveOrder(Player player, GameSession session, UUID connectionId,
                              C2SMoveOrder order, long second) {
        OrderResultCode code = judge(session, order, second);
        // The sixth term, the queue depth, is decided by the insertion itself: two connections can
        // hand in an order at the same moment, and a depth that was read before the insertion would
        // not be the depth it ends up at
        if (code == OrderResultCode.OK) code = units.request(player, connectionId, order);
        if (code != OrderResultCode.OK) {
            if (LEDGERED_REFUSALS.contains(code)) {
                analytics.record("unit_order_rejected", attributes(connectionId,
                        "order_seq", order.orderSeq(), "code", wireName(code)));
            }
            answer(player, new S2COrderResult(order.orderSeq(), code,
                    order.targetX(), order.targetY(), order.targetZ()));
            return;
        }
        // Accepted as far as this connection is concerned, so the sequence moves on here rather
        // than when the search finishes: two orders carrying the same seq in one tick would
        // otherwise both be taken, and "only ever greater than the last accepted one" is what the
        // client relies on to know that a retransmit costs it nothing (API-M2-005)
        session.acceptOrder(order.orderSeq());
    }

    /**
     * The {@code ACCEPT(order)} conjunction of §11.4, evaluated top down: the first term that fails
     * names the code, and the order of the terms is the order of the codes.
     *
     * @param session the sender's bookkeeping
     * @param order   the order to judge
     * @param second  the second it arrived in
     * @return {@link OrderResultCode#OK} when every term holds
     */
    private OrderResultCode judge(GameSession session, C2SMoveOrder order, long second) {
        if (order.orderSeq() <= session.acceptedOrderSeq()) return OrderResultCode.STALE;
        if (!session.allowOrder(second, order.orderGroup())) return OrderResultCode.RATE_LIMITED;
        if (!commands(session, order.unitId())) return OrderResultCode.NOT_OWNER;
        if (!standable(order.targetX(), order.targetZ())) return OrderResultCode.OUT_OF_BOUNDS;
        // The last two terms are the pathfinder's: the queue answers busy when it is full, and the
        // search itself answers no-path when it finds none (see receiveOrder)
        return OrderResultCode.OK;
    }

    /**
     * {@code owner(unit, sender)} of §9, the single judge of both selecting and ordering: a unit of
     * the sender's team, and never a neutral one. A unit id that names nothing fails it too, which
     * is why an id out of thin air is answered {@code not-owner} rather than with a code of its own.
     */
    private boolean commands(GameSession session, int unitId) {
        final int team = match.unitTeam(unitId);
        return team != 0 && team == session.team();
    }

    private boolean standable(float x, float z) {
        return preset.contains(x, z) && navGrid.isWalkable((int) Math.floor(x), (int) Math.floor(z));
    }

    private void answer(Player player, S2COrderResult result) {
        final MsgBuf buf = MsgBuf.writing();
        result.write(buf);
        channels.send(player, NAME, buf.toByteArray());
    }

    private void refuse(PlayerConnection connection, UUID connectionId, String cause) {
        refusals.warn("Refusing a game payload: connection_id={} reason={} cause={}",
                connectionId, "synaxis:protocol-error", cause);
        connection.kick(DisconnectReasons.PROTOCOL_ERROR);
    }

    /**
     * The name §14 gives a code, which is what the ledger and the client's own strings use — the
     * enum constant is the same word in the shape Java wants it in.
     */
    private static String wireName(OrderResultCode code) {
        return code.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private Map<String, Object> attributes(UUID connectionId, Object... pairs) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connection_id", connectionId);
        attributes.put("match_id", match.matchId().toString());
        for (int i = 0; i < pairs.length; i += 2) attributes.put((String) pairs[i], pairs[i + 1]);
        return attributes;
    }
}
