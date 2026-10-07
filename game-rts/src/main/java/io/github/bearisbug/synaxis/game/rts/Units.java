package io.github.bearisbug.synaxis.game.rts;

import io.github.bearisbug.synaxis.coordinate.Pos;
import io.github.bearisbug.synaxis.entity.Entity;
import io.github.bearisbug.synaxis.entity.Player;
import io.github.bearisbug.synaxis.game.protocol.C2SMoveOrder;
import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.MsgBuf;
import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitSpawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import io.github.bearisbug.synaxis.instance.Instance;
import io.github.bearisbug.synaxis.plugin.LogBudget;
import io.github.bearisbug.synaxis.plugin.PluginAnalytics;
import io.github.bearisbug.synaxis.plugin.PluginChannels;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The units of the match and the three things that drive them: the pathfinding queue, the advance
 * along the waypoints, and the 10 Hz snapshot (REQ-M2-016, REQ-M2-017).
 * <p>
 * <b>Where this runs.</b> The searching, the advancing and the snapshot are one scheduled task,
 * which the engine advances at the top of a tick, before any chunk or entity is ticked; the payload
 * handler that fills the queue runs later in the same tick, on the thread of the sending player.
 * Only the queue and the list of connections that went away are therefore shared, and both are
 * concurrent and drained by the tick — the unit state itself is written from the tick and from
 * nowhere else, which is what lets a search read the mesh and move an entity without taking a
 * lock.
 * <p>
 * <b>The three budgets of §15.</b> A search is capped at {@link Pathfinder#MAX_EXPANSIONS} nodes,
 * at most {@value #REQUESTS_PER_TICK} of them run in a tick, and a queue deeper than
 * {@value #MAX_QUEUE_DEPTH} answers {@code busy} instead of growing. The first two bound the work
 * one tick does; the third is what keeps the answer honest when the first two make the server slow
 * — a client that is told {@code busy} can retry, one that is told nothing cannot.
 * <p>
 * <b>The interest set is the engine's, not ours.</b> Every snapshot reads each display entity's
 * viewer set through the public {@code Entity#getViewers()} and diffs it against the players the
 * unit was last announced to: a player new to the set gets {@code S2C_UNIT_SPAWN}, one gone from it
 * gets {@code S2C_UNIT_DESPAWN}, both ahead of the {@code S2C_UNIT_STATE} of the same tick. The
 * engine's own viewer hooks are {@code @ApiStatus.Internal} and stay untouched (the aggregate rule
 * of {@code docs/DESIGN-M2.md} §24), which puts the announcement at most one snapshot interval
 * behind the engine's spawn packet and on the tick thread with everything else here. No radius is
 * computed: the engine culls by chunk rings, and a second opinion would show units the client has
 * already been told to destroy ({@code API-M2-006}).
 */
final class Units {

    private static final Logger LOGGER = LoggerFactory.getLogger(Units.class);

    /** §12.2: the advance and the snapshot both run every other tick, which is 10 Hz. */
    static final int ADVANCE_INTERVAL_TICKS = 2;

    /** §15: how many searches one tick may run. */
    static final int REQUESTS_PER_TICK = 4;

    /** §11.4: a queue deeper than this answers {@code busy}. */
    static final int MAX_QUEUE_DEPTH = 32;

    /**
     * §15: the most lines one {@code S2C_UNIT_STATE} carries. A view holding more units than this
     * is sent in turns, so every unit is refreshed within ⌈n ÷ 60⌉ snapshots.
     */
    static final int SNAPSHOT_LINES = 60;

    /**
     * One order waiting for a path. It keeps the player rather than looking it up later: the answer
     * has to reach the connection that asked even if the unit is gone by then.
     */
    private record Request(Player player, UUID connectionId, C2SMoveOrder order) {

        int targetColumnX() {
            return (int) Math.floor(order.targetX());
        }

        int targetColumnZ() {
            return (int) Math.floor(order.targetZ());
        }
    }

    private final Match match;
    private final Instance instance;
    private final Pathfinder pathfinder;
    private final GameSessions sessions;
    private final PluginChannels channels;
    private final PluginAnalytics analytics;
    private final LongSupplier clock;

    /** Sorted so that a snapshot lists the units of a match in the same order every time. */
    private final NavigableMap<Integer, Unit> units = new ConcurrentSkipListMap<>();

    /**
     * The orders waiting for a path. Bounded rather than checked and then filled: two connections
     * may hand in an order at the same time, and the {@code busy} of §11.4 has to be decided by the
     * very operation that inserts, or the depth the design caps is only a suggestion.
     */
    private final BlockingQueue<Request> queue = new LinkedBlockingQueue<>(MAX_QUEUE_DEPTH);

    /** Connections that went away, drained by the tick so that the units are only touched there. */
    private final Queue<UUID> departed = new ConcurrentLinkedQueue<>();

    /** The budget of the line that reports a unit that could not be advanced. */
    private final LogBudget stuck = new LogBudget(LOGGER, "units that could not be advanced");

    /**
     * Per viewer, the {@code unit_id} its last snapshot ended on when its view was too big for one;
     * the next turn starts at the first unit after it. Only the snapshot touches it, on the tick.
     */
    private final Map<Player, Integer> turnEnds = new HashMap<>();

    private int nextUnitId = 1;
    private long ticks;

    Units(Match match, Instance instance, NavGrid grid, MapPreset preset, GameSessions sessions,
          PluginChannels channels, PluginAnalytics analytics, LongSupplier clock) {
        this.match = match;
        this.instance = instance;
        this.pathfinder = new Pathfinder(grid, preset);
        this.sessions = sessions;
        this.channels = channels;
        this.analytics = analytics;
        this.clock = clock;
    }

    /**
     * Puts a unit on the board (§11.4 {@code [*] → idle}): the model, the display entity built to
     * the five rules of REQ-M2-016, and the entry in the match's unit table that every
     * {@code owner(unit, sender)} test reads.
     *
     * @param archetype the template it is built from
     * @param team      the team it fights for, 0 for a neutral unit nobody commands
     * @param position  where it stands
     * @return the unit
     */
    Unit spawn(Archetype archetype, int team, Pos position) {
        final int unitId = nextUnitId++;
        final UnitEntity entity = new UnitEntity(instance.process(), archetype);
        final Unit unit = new Unit(unitId, archetype, team, entity);
        units.put(unitId, unit);
        match.registerUnit(unitId, team);
        final CompletableFuture<Void> placed = entity.setInstance(instance, position);
        // The whole map is in memory and no chunk is ever loaded again, so placing an entity does
        // not wait for anything; a spawn that did would be waiting for a chunk that never comes
        if (!placed.isDone()) throw new IllegalStateException("unit " + unitId + " was spawned outside the map");
        placed.join();
        return unit;
    }

    /**
     * A unit by its id.
     *
     * @param unitId the id to look up
     * @return the unit, null when no unit of the match carries that id
     */
    @Nullable Unit byId(int unitId) {
        return units.get(unitId);
    }

    /**
     * Takes an order that passed every term the channel can judge on its own, which leaves the
     * queue depth and the path. Its answer — an {@code ok} with the snapped destination, or a
     * {@code no-path} — is sent when the search runs, at most
     * {@link #MAX_QUEUE_DEPTH} / {@value #REQUESTS_PER_TICK} ticks later.
     *
     * @param player       the sender
     * @param connectionId its connection
     * @param order        the order
     * @return {@link OrderResultCode#OK} when the order is queued, {@link OrderResultCode#BUSY}
     *         when the queue is full and the caller has to answer that instead
     */
    OrderResultCode request(Player player, UUID connectionId, C2SMoveOrder order) {
        return queue.offer(new Request(player, connectionId, order))
                ? OrderResultCode.OK : OrderResultCode.BUSY;
    }

    /**
     * One tick of the match: run what the queue budget allows, then, every other tick, advance the
     * units that are moving and tell every player what it can see.
     */
    void tick() {
        ticks++;
        for (UUID gone = departed.poll(); gone != null; gone = departed.poll()) reclaim(gone);
        for (int i = 0; i < REQUESTS_PER_TICK; i++) {
            final Request request = queue.poll();
            if (request == null) break;
            resolve(request);
        }
        if (ticks % ADVANCE_INTERVAL_TICKS != 0) return;
        advance();
        snapshot();
    }

    /**
     * Drops what is held for a connection that went away: its queued orders have nobody to answer
     * to, and the orders its units are carrying out will never complete (§20
     * {@code unit_order_superseded}).
     *
     * @param connectionId the connection that went away
     */
    void closed(UUID connectionId) {
        departed.add(connectionId);
    }

    /** Drops what a connection left behind, on the tick, which is the only place units are moved. */
    private void reclaim(UUID connectionId) {
        queue.removeIf(request -> request.connectionId().equals(connectionId));
        for (final Unit unit : units.values()) {
            final Unit.Order order = unit.order();
            if (order != null && order.connectionId().equals(connectionId)) superseded(unit.stop(), "session-closed");
        }
    }

    /**
     * Takes every unit off the board when the match ends (§11.4 {@code → removed}): every player
     * still holding a unit gets its last {@code S2C_UNIT_DESPAWN}, the display entities go, and
     * every order still in flight is written off.
     * <p>
     * Runs in {@code onDisable}, on a thread of the host that owns no tick thread (API-PROD-007):
     * a unit a tick thread owns is removed under that thread's lock, one no tick thread owns yet is
     * removed directly, since {@code sync} on it only accepts the thread that created it.
     */
    void end() {
        queue.clear();
        for (final Unit unit : units.values()) {
            superseded(unit.stop(), "match-ended");
            for (final Player player : unit.announcedTo()) despawn(unit, player);
            unit.announcedTo().clear();
            final UnitEntity entity = unit.entity();
            if (entity.acquirable().assignedThread() == null) entity.remove();
            else entity.acquirable().sync(Entity::remove);
        }
        units.clear();
        match.unregisterUnits();
    }

    /**
     * Brings the announced interest set of one unit up to the engine's viewer set: spawns to the
     * players that joined it, despawns to the players that left it.
     *
     * @param unit the unit
     * @return its viewer set as read for this snapshot
     */
    private Set<Player> announce(Unit unit) {
        final Set<Player> viewers = Set.copyOf(unit.entity().getViewers());
        final Set<Player> told = unit.announcedTo();
        for (final Player viewer : viewers) {
            if (told.add(viewer)) spawn(unit, viewer);
        }
        for (final Iterator<Player> it = told.iterator(); it.hasNext(); ) {
            final Player player = it.next();
            if (viewers.contains(player)) continue;
            it.remove();
            despawn(unit, player);
        }
        return viewers;
    }

    private void spawn(Unit unit, Player player) {
        final boolean commandable = unit.team() != 0
                && unit.team() == sessions.team(player.getPlayerConnection().connectionId());
        send(player, new S2CUnitSpawn(unit.unitId(), unit.entity().getEntityId(), unit.archetype().id(),
                unit.team(), Unit.MAX_HP, Unit.MAX_HP, commandable ? S2CUnitSpawn.FLAG_COMMANDABLE : 0));
    }

    private void despawn(Unit unit, Player player) {
        send(player, new S2CUnitDespawn(List.of(unit.unitId())));
    }

    /** Runs one queued search and answers the order that asked for it. */
    private void resolve(Request request) {
        final C2SMoveOrder order = request.order();
        final Unit unit = units.get(order.unitId());
        // The unit went away while the order waited: there is no path to walk and nothing to move
        if (unit == null) {
            refuse(request, 0, 0);
            return;
        }
        final long started = clock.getAsLong();
        final Pathfinder.Path path = pathfinder.find(unit.entity().getPosition(),
                request.targetColumnX(), request.targetColumnZ(), unit.archetype().radius());
        final long pathMs = TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - started);
        if (!path.found()) {
            refuse(request, path.nodes(), pathMs);
            return;
        }
        superseded(unit.follow(path.waypoints(),
                new Unit.Order(request.connectionId(), order.orderSeq(), clock.getAsLong())), "replaced");
        analytics.record("unit_order_issued", attributes(request.connectionId(),
                "order_seq", order.orderSeq(), "order_group", order.orderGroup(),
                "unit_id", order.unitId(), "path_nodes", path.nodes(), "path_ms", pathMs));
        final Pos destination = path.waypoints().getLast();
        send(request.player(), new S2COrderResult(order.orderSeq(), OrderResultCode.OK,
                (float) destination.x(), (float) destination.y(), (float) destination.z()));
    }

    /** Walks every moving unit one advance further and closes the orders that completed. */
    private void advance() {
        for (final Unit unit : units.values()) {
            try {
                final Unit.Order completed = unit.advance(ADVANCE_INTERVAL_TICKS);
                if (completed == null) continue;
                analytics.record("unit_order_completed", attributes(completed.connectionId(),
                        "order_seq", completed.orderSeq(),
                        "elapsed_ms", TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - completed.issuedNanos())));
            } catch (RuntimeException e) {
                // A waypoint outside the generated map throws out of refreshCoordinate rather than
                // failing silently (ADR-M2-013). One unit that cannot be advanced stops; the rest
                // of the match keeps running, and the unit is left idle rather than half moved
                stuck.error(e, "Could not advance a unit: match_id={} unit_id={}",
                        match.matchId(), unit.unitId());
                unit.stop();
            }
        }
    }

    /**
     * Sends every player the state of its turn of the units it can see ({@link #turn}), after
     * announcing the units that entered or left its interest set since the last snapshot. The
     * interest sets differ from player to player, so the packets do too and the engine's grouped
     * packet cache does not apply ({@code API-M2-006}); the snapshot is built by walking the units
     * once and bucketing by viewer rather than by asking each player which units it sees.
     */
    private void snapshot() {
        final Map<Player, List<S2CUnitState.Entry>> byViewer = new HashMap<>();
        for (final Unit unit : units.values()) {
            final S2CUnitState.Entry entry = new S2CUnitState.Entry(unit.unitId(), unit.state(), Unit.MAX_HP);
            for (final Player viewer : announce(unit)) {
                byViewer.computeIfAbsent(viewer, _ -> new ArrayList<>()).add(entry);
            }
        }
        // A player that sees nothing, or has gone, starts its turns over the next time it sees more
        turnEnds.keySet().retainAll(byViewer.keySet());
        final int serverTick = (int) (ticks % Integer.MAX_VALUE);
        for (final Map.Entry<Player, List<S2CUnitState.Entry>> viewer : byViewer.entrySet()) {
            send(viewer.getKey(), new S2CUnitState(serverTick, turn(viewer.getKey(), viewer.getValue())));
        }
    }

    /**
     * The lines of one player's snapshot (§15): the whole view when it fits in
     * {@value #SNAPSHOT_LINES}, otherwise the next {@value #SNAPSHOT_LINES} units after the one the
     * last snapshot ended on, wrapping round to the lowest {@code unit_id}. The turn is kept by
     * {@code unit_id} rather than by position, so a unit entering the view ahead of another's turn
     * puts that turn back by one line, and a unit leaving it brings the turn a line closer.
     *
     * @param viewer the player
     * @param view   the units it sees, in ascending {@code unit_id}
     * @return the lines to send it
     */
    private List<S2CUnitState.Entry> turn(Player viewer, List<S2CUnitState.Entry> view) {
        if (view.size() <= SNAPSHOT_LINES) return view;
        final Integer endedOn = turnEnds.get(viewer);
        int start = 0;
        if (endedOn != null) {
            while (start < view.size() && view.get(start).unitId() <= endedOn) start++;
            if (start == view.size()) start = 0;
        }
        final List<S2CUnitState.Entry> lines = new ArrayList<>(SNAPSHOT_LINES);
        for (int i = 0; i < SNAPSHOT_LINES; i++) lines.add(view.get((start + i) % view.size()));
        turnEnds.put(viewer, lines.getLast().unitId());
        return lines;
    }

    /**
     * Answers the one refusal that can only be found out down here: the destination is walkable and
     * the sender does command that unit, but no route joins the two. Like every refusal it carries
     * the target the client asked for back rather than a snapped one (§9).
     * <p>
     * It is the only refusal that costs a search, so it is the only one that reports what the search
     * spent: a count over {@link Pathfinder#MAX_EXPANSIONS} is a search that ran out of budget
     * rather than a target nothing joins, which is the first thing {@code RB-M2-003} has to tell
     * apart (§20).
     *
     * @param request the order to refuse
     * @param nodes   how many nodes its search expanded, 0 when none ran
     * @param pathMs  how long its search took
     */
    private void refuse(Request request, int nodes, long pathMs) {
        final C2SMoveOrder order = request.order();
        analytics.record("unit_order_rejected", attributes(request.connectionId(),
                "order_seq", order.orderSeq(), "code", "no-path",
                "path_nodes", nodes, "path_ms", pathMs));
        send(request.player(), new S2COrderResult(order.orderSeq(), OrderResultCode.NO_PATH,
                order.targetX(), order.targetY(), order.targetZ()));
    }

    /**
     * Writes off an order that can no longer complete.
     *
     * @param order  the order, null when there was none, in which case nothing is written
     * @param reason why it cannot complete, one of the four names of §20
     */
    private void superseded(@Nullable Unit.Order order, String reason) {
        if (order == null) return;
        analytics.record("unit_order_superseded", attributes(order.connectionId(),
                "order_seq", order.orderSeq(), "reason", reason));
    }

    private void send(Player player, GameMessage message) {
        final MsgBuf buf = MsgBuf.writing();
        message.write(buf);
        channels.send(player, GameChannel.NAME, buf.toByteArray());
    }

    private Map<String, Object> attributes(UUID connectionId, Object... pairs) {
        final Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("connection_id", connectionId);
        attributes.put("match_id", match.matchId().toString());
        for (int i = 0; i < pairs.length; i += 2) attributes.put((String) pairs[i], pairs[i + 1]);
        return attributes;
    }
}
