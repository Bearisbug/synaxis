package io.github.bearisbug.synaxis.game.client;

import io.github.bearisbug.synaxis.game.protocol.C2SMoveOrder;
import io.github.bearisbug.synaxis.game.protocol.C2SSelect;
import io.github.bearisbug.synaxis.game.protocol.GameMessage;
import io.github.bearisbug.synaxis.game.protocol.OrderResultCode;
import io.github.bearisbug.synaxis.game.protocol.S2COrderResult;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitDespawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitSpawn;
import io.github.bearisbug.synaxis.game.protocol.S2CUnitState;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selection and ordering on the client ({@code REQ-M2-019}, {@code API-M2-004} and
 * {@code API-M2-005}).
 * <p>
 * A click reaches this class as the ray it produced, which is what keeps the input layer of stage
 * 6b out of here and lets every decision below be driven by a test: which unit a ray picks, what
 * goes on the wire, and what the answers do to the local selection.
 * <p>
 * <b>The client judges nothing.</b> It reports the unit it picked even when the {@code flags} say
 * somebody else owns it, because §19 puts that judgement on the server alone — the server drops
 * such an id from the selection silently and answers {@code not-owner} to an order for it, and the
 * second of those is what this corrects its own selection against (§14). M2 single selects, so the
 * selection is one unit or none and {@code order_group} is each order's own {@code order_seq} (§9).
 * <p>
 * Everything here runs on the client thread that handles play packets and rendering, including
 * {@link #reset()} — which is why it is wired to the <em>start</em> of a play connection rather
 * than to the disconnect event, whose listeners Fabric runs on a netty thread.
 */
public final class RtsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("synaxis");

    /**
     * How far a click reaches, in blocks.
     * <p>
     * Past this the units are not on screen to be clicked: the client culls a display entity at
     * {@code view_range × 64 × clamp(renderDistance / 8, 1, 2.5) × entityDistanceScaling} blocks
     * from the camera (§15), and the render distance fog closes even earlier, at
     * {@code 16 × renderDistance} blocks out horizontally — 160 at render distance 10.
     */
    public static final double REACH = 256.0;

    /**
     * §15: ten selections and ten order operations a second, counted the way the server counts
     * them — a fixed window per whole second of {@link System#nanoTime()}, not a sliding one.
     * <p>
     * <b>This throttle is why the client rarely hears {@code rate-limited}, not a promise that it
     * never will.</b> The two ends count in windows of the same length that begin at different
     * moments, so a burst straddling a boundary here can land inside one window there; §14 has the
     * client handle the code either way, and {@link OrderFeedback} does.
     * <p>
     * M2 single selects, so one click is one packet and counting packets is counting operations.
     * When stage 6b adds a group selection the order budget has to start counting
     * {@code order_group}s instead, as {@code API-M2-005} spells out.
     */
    public static final int BUDGET_PER_SECOND = 10;

    /**
     * Where an outbound message goes. The production answer is one call to Fabric's play sender.
     */
    @FunctionalInterface
    public interface Sink {

        /**
         * Sends one message on {@code rts:game}.
         *
         * @param message the message
         */
        void send(GameMessage message);
    }

    private final ClientWorldView world;
    private final LongSupplier clock;
    private final Sink sink;
    private final UnitBindings bindings = new UnitBindings();

    private Integer selected;
    private int orderSeq;
    private OrderFeedback feedback;

    private long selectSecond = Long.MIN_VALUE;
    private int selectCount;
    private long orderSecond = Long.MIN_VALUE;
    private int orderCount;

    /**
     * @param world where rendered positions and ground points come from
     * @param clock nanosecond ticker the per second budgets are counted against, normally
     *              {@link System#nanoTime()}
     * @param sink  where outbound messages go
     */
    public RtsClient(ClientWorldView world, LongSupplier clock, Sink sink) {
        this.world = world;
        this.clock = clock;
        this.sink = sink;
    }

    /**
     * Takes one inbound message off {@code rts:game}.
     *
     * @param message the decoded message
     */
    public void receive(GameMessage message) {
        switch (message) {
            case S2CUnitSpawn spawn -> bindings.apply(spawn);
            case S2CUnitState snapshot -> bindings.apply(snapshot);
            case S2CUnitDespawn despawn -> {
                bindings.apply(despawn);
                if (selected != null && despawn.unitIds().contains(selected)) selected = null;
            }
            case S2COrderResult result -> {
                // The answer names an order_seq, not a unit, and this does not match the two up.
                // That holds only while nothing can change the selection between an order going
                // out and its answer coming back. The input layer of stage 6b can: a player who
                // reselects inside one round trip would have a late not-owner clear the unit they
                // have just picked, and the order after that go nowhere. Closing it means
                // remembering which unit each order_seq named (§28.2).
                if (result.code() == OrderResultCode.NOT_OWNER) selected = null;
                show(new OrderFeedback(result.code(),
                        new Vec3(result.snappedX(), result.snappedY(), result.snappedZ())));
            }
            default -> LOGGER.warn("Dropped a serverbound message arriving from the server: msg_id=0x{}",
                    Integer.toHexString(message.msgId()));
        }
    }

    /**
     * Handles a click that means "select what is under the cursor".
     * <p>
     * A click over the budget of §15 does nothing at all, local selection included. The message
     * replaces the whole selected set, so a click that changed the selection here and never
     * reached the server would leave the two ends disagreeing until the next one got through —
     * and the server drops an over budget selection silently, without telling anyone.
     *
     * @param origin    where the click's ray starts
     * @param direction which way it points
     */
    public void selectAt(Vec3 origin, Vec3 direction) {
        if (!allowSelect(second())) return;
        final UnitBindings.Binding hit = UnitPicker.pick(origin, direction, REACH, bindings, world);
        selected = hit == null ? null : hit.unitId();
        sink.send(new C2SSelect(selected == null ? List.of() : List.of(selected)));
    }

    /**
     * Handles a click that means "go there".
     * <p>
     * A click with nothing selected, or one that reaches no ground, produces no packet: the server
     * answers every order it receives, and an order nobody meant is an answer nobody can read.
     * <p>
     * A click over the budget of §15 produces no packet either, and answers itself with
     * {@code rate-limited} — the same code the server would have sent back, which §14 pairs with
     * "the client should be throttling". It spends no {@code order_seq}: nothing went out, and §8
     * asks only that what does go out keeps rising.
     *
     * @param origin    where the click's ray starts
     * @param direction which way it points
     */
    public void orderAt(Vec3 origin, Vec3 direction) {
        if (selected == null) return;
        final Vec3 target = world.groundHit(origin, direction, REACH);
        if (target == null) return;
        if (!allowOrder(second())) {
            show(new OrderFeedback(OrderResultCode.RATE_LIMITED, target));
            return;
        }
        final int seq = ++orderSeq;
        sink.send(new C2SMoveOrder(seq, seq, selected,
                (float) target.x, (float) target.y, (float) target.z, 0));
    }

    /**
     * Starts a connection from nothing: no bindings, no selection, an order sequence back at the
     * beginning — which is what §9 means by per connection — and both budgets and the last answer
     * with it.
     */
    public void reset() {
        bindings.clear();
        selected = null;
        orderSeq = 0;
        feedback = null;
        selectSecond = Long.MIN_VALUE;
        selectCount = 0;
        orderSecond = Long.MIN_VALUE;
        orderCount = 0;
    }

    /**
     * Takes an answer that has something to show, and leaves the last one alone when it has not.
     *
     * @param answer what the code and the point come to
     */
    private void show(OrderFeedback answer) {
        if (answer.marker() != OrderFeedback.Marker.SILENT) feedback = answer;
    }

    /**
     * @return the whole second the click being handled falls in
     */
    private long second() {
        return TimeUnit.NANOSECONDS.toSeconds(clock.getAsLong());
    }

    private boolean allowSelect(long second) {
        if (second != selectSecond) {
            selectSecond = second;
            selectCount = 0;
        }
        return ++selectCount <= BUDGET_PER_SECOND;
    }

    private boolean allowOrder(long second) {
        if (second != orderSecond) {
            orderSecond = second;
            orderCount = 0;
        }
        return ++orderCount <= BUDGET_PER_SECOND;
    }

    /**
     * @return the units this client has bound
     */
    public UnitBindings bindings() {
        return bindings;
    }

    /**
     * The one unit this player has picked, which stage 6b draws the selection box around — §5 and
     * §19 keep that box local, because entity metadata is global and a glow would put it on
     * everybody's screen.
     *
     * @return the selected unit, or {@code null} when nothing is selected
     */
    public Integer selected() {
        return selected;
    }

    /**
     * @return the last answer worth showing, or {@code null} when this connection has had none
     */
    public OrderFeedback feedback() {
        return feedback;
    }
}
