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
     * {@code view_range × 64} blocks (§15), and 4.0 is the {@code archetype_view_range} every M2
     * archetype carries.
     */
    public static final double REACH = 256.0;

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
    private final Sink sink;
    private final UnitBindings bindings = new UnitBindings();

    private Integer selected;
    private int orderSeq;

    /**
     * @param world where rendered positions and ground points come from
     * @param sink  where outbound messages go
     */
    public RtsClient(ClientWorldView world, Sink sink) {
        this.world = world;
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
            }
            default -> LOGGER.warn("Dropped a serverbound message arriving from the server: msg_id=0x{}",
                    Integer.toHexString(message.msgId()));
        }
    }

    /**
     * Handles a click that means "select what is under the cursor".
     *
     * @param origin    where the click's ray starts
     * @param direction which way it points
     */
    public void selectAt(Vec3 origin, Vec3 direction) {
        final UnitBindings.Binding hit = UnitPicker.pick(origin, direction, REACH, bindings, world);
        selected = hit == null ? null : hit.unitId();
        sink.send(new C2SSelect(selected == null ? List.of() : List.of(selected)));
    }

    /**
     * Handles a click that means "go there".
     * <p>
     * A click with nothing selected, or one that reaches no ground, produces no packet: the server
     * answers every order it receives, and an order nobody meant is an answer nobody can read.
     *
     * @param origin    where the click's ray starts
     * @param direction which way it points
     */
    public void orderAt(Vec3 origin, Vec3 direction) {
        if (selected == null) return;
        final Vec3 target = world.groundHit(origin, direction, REACH);
        if (target == null) return;
        final int seq = ++orderSeq;
        sink.send(new C2SMoveOrder(seq, seq, selected,
                (float) target.x, (float) target.y, (float) target.z, 0));
    }

    /**
     * Starts a connection from nothing: no bindings, no selection, and an order sequence back at
     * the beginning, which is what §9 means by per connection.
     */
    public void reset() {
        bindings.clear();
        selected = null;
        orderSeq = 0;
    }

    /**
     * @return the units this client has bound
     */
    public UnitBindings bindings() {
        return bindings;
    }

    /**
     * @return the selected unit, or {@code null} when nothing is selected
     */
    public Integer selected() {
        return selected;
    }
}
