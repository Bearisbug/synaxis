package io.github.bearisbug.synaxis.app.scene;

import io.github.bearisbug.synaxis.protocol.play.PlayProtocol;
import io.github.bearisbug.synaxis.protocol.play.S2CHud;
import io.github.bearisbug.synaxis.protocol.play.S2CScene;
import io.github.bearisbug.synaxis.protocol.play.Widget;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * What one connection is still to be sent on {@code synaxis:play} ({@code docs/DESIGN-PLAY.md} §6.4.3): one
 * exit per tick, the scene first and then the HUD changes, split on op boundaries so that every
 * {@code S2C_HUD} keeps within {@value PlayProtocol#MAX_MESSAGE_BYTES} bytes and {@value S2CHud#MAX_OPS} ops.
 * <p>
 * The queue is changed on the player's tick thread and in the serial part of the tick only, which never run
 * at the same time. A hold ({@link #holdUntil}) may come from any thread: a move into another instance can
 * be asked for anywhere, so it sits in an atomic reference of its own.
 */
final class Outbox {

    private static final CompletableFuture<?> NO_HOLD = CompletableFuture.completedFuture(null);

    /** The bytes of an {@code S2C_HUD} besides its ops: the {@code msg_id} and a count of at most 32. */
    private static final int HUD_HEADER_BYTES = new S2CHud(List.of()).encode().length;

    private @Nullable S2CScene scene;
    private final List<S2CHud.Op> ops = new ArrayList<>();
    private final AtomicReference<CompletableFuture<?>> hold = new AtomicReference<>(NO_HOLD);

    /**
     * Queues the scene, replacing one still queued: the client only needs the last of two scenes entered in
     * one tick.
     *
     * @param scene the scene
     */
    void scene(S2CScene scene) {
        this.scene = scene;
    }

    /**
     * Queues a widget, dropping the changes of the same widget still queued: two sets in one tick reach the
     * client as the later one.
     *
     * @param owner    the plugin
     * @param widgetId the widget
     * @param widget   the widget
     */
    void set(String owner, String widgetId, Widget widget) {
        dropChangesOf(owner, widgetId);
        ops.add(new S2CHud.SetWidget(owner, widgetId, widget));
    }

    /**
     * Queues the removal of a widget, dropping its changes still queued.
     *
     * @param owner    the plugin
     * @param widgetId the widget
     */
    void remove(String owner, String widgetId) {
        dropChangesOf(owner, widgetId);
        ops.add(new S2CHud.RemoveWidget(owner, widgetId));
    }

    /**
     * Queues a clear of every widget of one owner, dropping that owner's changes still queued.
     *
     * @param owner the plugin
     */
    void clearOwner(String owner) {
        ops.removeIf(op -> op.owner().equals(owner));
        ops.add(new S2CHud.ClearOwner(owner));
    }

    /**
     * Holds the whole queue back until a move completes, whichever way it goes, on top of any hold already
     * in place. Any thread.
     *
     * @param move completes when the move is done
     */
    void holdUntil(CompletableFuture<?> move) {
        while (true) {
            final CompletableFuture<?> current = hold.get();
            if (hold.compareAndSet(current, current.isDone() ? move : CompletableFuture.allOf(current, move))) return;
        }
    }

    /**
     * Hands what is queued to the sink, the scene first, unless a move is still under way.
     *
     * @param sink takes the payload of each message, in order
     * @return how many messages it was handed
     */
    int flush(Consumer<byte[]> sink) {
        if (!hold.get().isDone() || (scene == null && ops.isEmpty())) return 0;
        final List<byte[]> messages = new ArrayList<>();
        if (scene != null) messages.add(scene.encode());
        List<S2CHud.Op> batch = new ArrayList<>();
        int batchBytes = HUD_HEADER_BYTES;
        for (final S2CHud.Op op : ops) {
            final int opBytes = new S2CHud(List.of(op)).encode().length - HUD_HEADER_BYTES;
            if (!batch.isEmpty() && (batch.size() == S2CHud.MAX_OPS
                    || batchBytes + opBytes > PlayProtocol.MAX_MESSAGE_BYTES)) {
                messages.add(new S2CHud(batch).encode());
                batch = new ArrayList<>();
                batchBytes = HUD_HEADER_BYTES;
            }
            batch.add(op);
            batchBytes += opBytes;
        }
        if (!batch.isEmpty()) messages.add(new S2CHud(batch).encode());
        discard();
        messages.forEach(sink);
        return messages.size();
    }

    /**
     * Drops everything queued, the hold aside.
     */
    void discard() {
        scene = null;
        ops.clear();
    }

    private void dropChangesOf(String owner, String widgetId) {
        ops.removeIf(op -> !(op instanceof S2CHud.ClearOwner) && op.owner().equals(owner)
                && op.widgetId().equals(widgetId));
    }
}
